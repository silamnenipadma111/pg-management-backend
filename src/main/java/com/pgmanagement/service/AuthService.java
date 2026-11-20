package com.pgmanagement.service;

import com.pgmanagement.config.AppProperties;
import com.pgmanagement.dto.AuthDtos.*;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.User;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.repository.UserRepository;
import com.pgmanagement.security.LoginAttemptService;
import com.pgmanagement.security.SessionService;
import com.pgmanagement.security.SessionService.ActiveSession;
import com.pgmanagement.security.SessionService.IssuedSession;
import com.pgmanagement.service.MailService.Mail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

@Service
public class AuthService {

    /** A freshly created session: the raw token goes into the cookie, the response into the body. */
    public record SignedIn(String sessionToken, SessionResponse response) {
    }

    static final String RESET_REQUESTED = "If an account exists for this email address, we have sent a link to "
            + "reset the password. Please check your inbox.";
    static final String VERIFICATION_REQUESTED = "If this account still needs verification, we have sent a new "
            + "link. Please check your inbox.";

    private static final String INVALID_CREDENTIALS = "Invalid email or password";
    private static final String RESET_PASSWORD_PATH = "/reset-password?token=";
    /** A new link is mailed to the same address at most once per cooldown. */
    private static final Duration MAIL_COOLDOWN = Duration.ofMinutes(2);
    private static final int BCRYPT_MAX_BYTES = 72;
    /** Shorter fragments (e.g. "raj") appear in passwords by coincidence. */
    private static final int MIN_IDENTITY_FRAGMENT = 4;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;
    private final AuthTokenService tokenService;
    private final LoginAttemptService loginAttempts;
    private final MailService mailService;
    private final AppProperties appProperties;
    /** Compared against when the email is unknown, so both cases take the same time. */
    private final String timingEqualizerHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, SessionService sessionService,
                       AuthTokenService tokenService, LoginAttemptService loginAttempts, MailService mailService,
                       AppProperties appProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
        this.tokenService = tokenService;
        this.loginAttempts = loginAttempts;
        this.mailService = mailService;
        this.appProperties = appProperties;
        this.timingEqualizerHash = passwordEncoder.encode("timing-equalizer-not-a-real-password");
    }

    // ---------------------------------------------------------------- sign up

    @Transactional
    public SignupResponse signup(SignupRequest req) {
        if (req.role() == Enums.UserRole.SUPPORT) {
            throw ApiException.badRequest("Customer care accounts cannot be created via signup");
        }
        rejectPasswordWithIdentity(req.password(), req.name(), req.email());
        boolean verify = emailVerificationRequired();
        User user = createUser(req.name(), req.email(), req.phone(), req.password(), req.role(), req.gender());
        user.setEmailVerified(!verify);
        String account = req.role() == Enums.UserRole.OWNER ? "PG owner" : "customer";
        if (verify) {
            sendVerificationMail(user, "Your " + account + " account has been created with this email address.");
            return new SignupResponse(user.getEmail(), true,
                    "Account created. We sent a verification link to " + user.getEmail()
                            + ". Please confirm your email address before signing in.");
        }
        Mail mail = Mail.of(user.getName(), user.getEmail(), "Your account has been created",
                        "Welcome! Your " + account + " account has been created with this email address.",
                        "You can sign in now to " + (req.role() == Enums.UserRole.OWNER
                                ? "register your PG and manage bookings and rent."
                                : "find a PG, book a bed and pay your rent online."))
                .withAction("Sign in", link("/signin"));
        AfterCommit.run(() -> mailService.sendAsync(mail));
        return new SignupResponse(user.getEmail(), false, "Account created. Please sign in.");
    }

    /** Creates an account that is immediately usable (seed data, accounts created by customer care). */
    @Transactional
    public User createUser(String name, String email, String phone, String rawPassword, Enums.UserRole role,
                           Enums.Gender gender) {
        if (userRepository.existsByEmailIgnoreCaseAndRole(email.trim(), role)) {
            throw ApiException.conflict("An account with this email already exists");
        }
        User user = new User();
        user.setName(name.trim());
        user.setEmail(email.trim().toLowerCase(Locale.ROOT));
        user.setPhone(phone.trim());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        user.setGender(gender);
        return userRepository.save(user);
    }

    @Transactional
    public MessageResponse verifyEmail(String token) {
        User user = tokenService.consume(token, Enums.AuthTokenPurpose.EMAIL_VERIFICATION)
                .orElseThrow(() -> ApiException.badRequest(
                        "This verification link is invalid or has expired. Please request a new one.",
                        ApiException.CODE_INVALID_TOKEN));
        user.setEmailVerified(true);
        String account = user.getRole() == Enums.UserRole.OWNER ? "PG owner" : "customer";
        Mail mail = Mail.of(user.getName(), user.getEmail(), "Welcome to PG-STAY.IN",
                        "Your email address is verified and your " + account + " account is now active.",
                        "You can sign in to " + (user.getRole() == Enums.UserRole.OWNER
                                ? "register your PG and manage bookings and rent."
                                : "find a PG, book a bed and pay your rent online."))
                .withAction("Sign in", link("/signin"));
        AfterCommit.run(() -> mailService.sendAsync(mail));
        return new MessageResponse("Your email address is verified. You can sign in now.");
    }

    /** Always answers the same way, so the response does not reveal which addresses have an account. */
    @Transactional
    public MessageResponse resendVerification(String email) {
        userRepository.findAllByEmailIgnoreCase(email.trim()).stream()
                .filter(user -> !user.isEmailVerified())
                .filter(user -> !tokenService.issuedWithin(user, Enums.AuthTokenPurpose.EMAIL_VERIFICATION, MAIL_COOLDOWN))
                .forEach(user -> sendVerificationMail(user, "Here is a new link to confirm your email address."));
        return new MessageResponse(VERIFICATION_REQUESTED);
    }

    // ---------------------------------------------------------------- sign in / out

    /**
     * @param previousSessionToken session cookie the browser already had, if any; it is revoked so that
     *                             signing in always starts a brand new session
     */
    @Transactional
    public SignedIn signin(SigninRequest req, String ipAddress, String userAgent, String previousSessionToken) {
        String email = req.email().trim();
        long locked = loginAttempts.secondsUntilUnlocked(email);
        if (locked > 0) {
            throw ApiException.tooManyRequests("Too many failed sign-in attempts. Please try again in "
                    + Math.max(1, (locked + 59) / 60) + " minute(s) or reset your password.", locked);
        }
        User user = userRepository.findAllByEmailIgnoreCase(email).stream()
                .filter(u -> req.role() == null || u.getRole() == req.role())
                .findFirst().orElse(null);
        boolean passwordOk = passwordMatches(req.password(), user == null ? timingEqualizerHash : user.getPasswordHash());
        if (user == null || !passwordOk) {
            loginAttempts.recordFailure(email);
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }
        if (!user.isEmailVerified() && emailVerificationRequired()) {
            throw ApiException.forbidden("Please verify your email address before signing in. "
                    + "Open the link we sent to " + user.getEmail() + " or request a new one.",
                    ApiException.CODE_EMAIL_NOT_VERIFIED);
        }
        loginAttempts.recordSuccess(email);
        sessionService.revoke(previousSessionToken);
        IssuedSession session = sessionService.create(user, ipAddress, userAgent);
        return new SignedIn(session.token(),
                new SessionResponse(DtoMapper.toUserDto(user), session.csrfToken(), session.expiresAt()));
    }

    /** The current session, used by the website to restore the signed-in state after a page reload. */
    @Transactional(readOnly = true)
    public SessionResponse currentSession(ActiveSession session) {
        User user = userRepository.findById(session.user().id()).orElseThrow(() -> ApiException.notFound("User"));
        return new SessionResponse(DtoMapper.toUserDto(user), session.csrfToken(), session.expiresAt());
    }

    @Transactional
    public void signout(String sessionToken) {
        sessionService.revoke(sessionToken);
    }

    // ---------------------------------------------------------------- password reset

    /** Always answers the same way, so the response does not reveal which addresses have an account. */
    @Transactional
    public MessageResponse forgotPassword(String email) {
        userRepository.findAllByEmailIgnoreCase(email.trim()).stream()
                .filter(user -> !tokenService.issuedWithin(user, Enums.AuthTokenPurpose.PASSWORD_RESET, MAIL_COOLDOWN))
                .forEach(this::sendPasswordResetMail);
        return new MessageResponse(RESET_REQUESTED);
    }

    @Transactional
    public MessageResponse resetPassword(ResetPasswordRequest req) {
        User user = tokenService.consume(req.token(), Enums.AuthTokenPurpose.PASSWORD_RESET)
                .orElseThrow(() -> ApiException.badRequest(
                        "This password reset link is invalid or has expired. Please request a new one.",
                        ApiException.CODE_INVALID_TOKEN));
        rejectPasswordWithIdentity(req.password(), user.getName(), user.getEmail());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        // the link was delivered to this mailbox, so the address is proven as well
        user.setEmailVerified(true);
        sessionService.revokeAll(user.getId());
        loginAttempts.recordSuccess(user.getEmail());
        Mail mail = Mail.of(user.getName(), user.getEmail(), "Your password was changed",
                "The password of your PG-STAY.IN account was just changed and you have been signed out on all devices.",
                "If this was not you, reply to this email or write to " + appProperties.supportEmail()
                        + " immediately.");
        AfterCommit.run(() -> mailService.sendAsync(mail));
        return new MessageResponse("Your password has been changed. Please sign in with the new password.");
    }

    private void sendPasswordResetMail(User user) {
        Duration validFor = appProperties.auth().passwordResetTtl();
        String token = tokenService.issue(user, Enums.AuthTokenPurpose.PASSWORD_RESET, validFor);
        Mail mail = Mail.of(user.getName(), user.getEmail(), "Reset your password",
                        "We received a request to set a new password for your PG-STAY.IN account.",
                        "The link is valid for " + validFor.toMinutes()
                                + " minutes and can be used once. If you did not ask for it, you can ignore this email.")
                .withAction("Choose a new password", link(RESET_PASSWORD_PATH + token));
        AfterCommit.run(() -> mailService.sendAsync(mail));
    }

    /**
     * Welcome mail for an owner whose account was created by customer care: instead of sending a password by
     * email, the owner gets a link to choose their own.
     */
    @Transactional
    public void sendAccountSetupMail(User owner, String pgName) {
        Duration validFor = appProperties.auth().verificationTtl();
        String token = tokenService.issue(owner, Enums.AuthTokenPurpose.PASSWORD_RESET, validFor);
        Mail mail = Mail.of(owner.getName(), owner.getEmail(), "Your PG is listed - set your password",
                        pgName + " was registered on PG-STAY.IN by our customer care team and a PG owner account "
                                + "was created for this email address.",
                        "Choose your password to sign in and manage bookings, beds and rent. The link is valid for "
                                + validFor.toHours() + " hours.")
                .withAction("Choose your password", link(RESET_PASSWORD_PATH + token));
        AfterCommit.run(() -> mailService.sendAsync(mail));
    }

    // ---------------------------------------------------------------- helpers

    /** "auto" (default) requires verification whenever the application is able to send email. */
    public boolean emailVerificationRequired() {
        String mode = appProperties.auth().requireEmailVerification();
        if ("true".equalsIgnoreCase(mode)) {
            return true;
        }
        if ("false".equalsIgnoreCase(mode)) {
            return false;
        }
        return mailService.isConfigured();
    }

    private void sendVerificationMail(User user, String intro) {
        String token = tokenService.issue(user, Enums.AuthTokenPurpose.EMAIL_VERIFICATION,
                appProperties.auth().verificationTtl());
        Mail mail = Mail.of(user.getName(), user.getEmail(), "Confirm your email address", intro,
                        "Please confirm your email address to activate sign-in. The link is valid for "
                                + appProperties.auth().verificationTtl().toHours() + " hours.")
                .withAction("Verify email", link("/verify-email?token=" + token));
        AfterCommit.run(() -> mailService.sendAsync(mail));
    }

    private boolean passwordMatches(String rawPassword, String hash) {
        // BCrypt rejects longer input; no stored password can be longer, so it cannot match anyway
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, hash);
    }

    private static void rejectPasswordWithIdentity(String password, String name, String email) {
        String lower = password.toLowerCase(Locale.ROOT);
        String localPart = email.trim().toLowerCase(Locale.ROOT).split("@")[0];
        boolean containsIdentity = containsFragment(lower, localPart)
                || Arrays.stream(name.trim().toLowerCase(Locale.ROOT).split("\\s+"))
                .anyMatch(part -> containsFragment(lower, part));
        if (containsIdentity) {
            throw ApiException.badRequest("password: must not contain your name or email address");
        }
    }

    private static boolean containsFragment(String password, String fragment) {
        return fragment.length() >= MIN_IDENTITY_FRAGMENT && password.contains(fragment);
    }

    private String link(String path) {
        return appProperties.siteBaseUrl() + path;
    }
}

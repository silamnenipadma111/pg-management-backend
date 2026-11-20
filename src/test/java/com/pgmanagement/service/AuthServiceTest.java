package com.pgmanagement.service;

import com.pgmanagement.dto.AuthDtos.MessageResponse;
import com.pgmanagement.dto.AuthDtos.ResetPasswordRequest;
import com.pgmanagement.dto.AuthDtos.SessionResponse;
import com.pgmanagement.dto.AuthDtos.SigninRequest;
import com.pgmanagement.dto.AuthDtos.SignupRequest;
import com.pgmanagement.dto.AuthDtos.SignupResponse;
import com.pgmanagement.entity.Enums.AuthTokenPurpose;
import com.pgmanagement.entity.Enums.Gender;
import com.pgmanagement.entity.Enums.UserRole;
import com.pgmanagement.entity.User;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.repository.UserRepository;
import com.pgmanagement.security.AuthUser;
import com.pgmanagement.security.LoginAttemptService;
import com.pgmanagement.security.SessionService;
import com.pgmanagement.security.SessionService.ActiveSession;
import com.pgmanagement.security.SessionService.IssuedSession;
import com.pgmanagement.service.AuthService.SignedIn;
import com.pgmanagement.service.MailService.Mail;
import com.pgmanagement.support.Fixtures;
import com.pgmanagement.support.TestProps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String EMAIL = "anil@example.com";
    private static final String PASSWORD = "Tr4vel!Light";
    private static final String NEW_PASSWORD = "Mango$Lassi9";
    private static final String PHONE = "9123456780";
    private static final String IP = "203.0.113.9";
    private static final String USER_AGENT = "Mozilla/5.0";
    private static final String INVALID_CREDENTIALS = "Invalid email or password";
    private static final String VERIFY = "true";
    private static final String NO_VERIFY = "false";

    @Mock
    private UserRepository userRepository;
    @Mock
    private SessionService sessionService;
    @Mock
    private AuthTokenService tokenService;
    @Mock
    private LoginAttemptService loginAttempts;
    @Mock
    private MailService mailService;
    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    // ---------------------------------------------------------------- sign up

    @Test
    void signupWithoutVerificationCreatesAUsableAccountAndSendsAWelcomeMail() {
        acceptNewUsers();
        SignupRequest request = new SignupRequest("  Anil Varma ", " Anil@Example.com ", " " + PHONE, PASSWORD,
                UserRole.CUSTOMER, Gender.MALE);

        SignupResponse response = service(NO_VERIFY).signup(request);

        User saved = savedUser();
        assertThat(saved.getName()).isEqualTo("Anil Varma");
        assertThat(saved.getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getPhone()).isEqualTo(PHONE);
        assertThat(saved.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(saved.getGender()).isEqualTo(Gender.MALE);
        assertThat(saved.isEmailVerified()).isTrue();
        assertThat(saved.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, saved.getPasswordHash())).isTrue();
        assertThat(response).isEqualTo(new SignupResponse(EMAIL, false, "Account created. Please sign in."));
        Mail mail = sentMail();
        assertThat(mail.toEmail()).isEqualTo(EMAIL);
        assertThat(mail.toName()).isEqualTo("Anil Varma");
        assertThat(mail.subject()).isEqualTo("Your account has been created");
        assertThat(mail.paragraphs()).hasSize(2).first().asString().contains("customer account has been created");
        assertThat(mail.actionLabel()).isEqualTo("Sign in");
        assertThat(mail.actionUrl()).isEqualTo(TestProps.SITE + "/signin");
        verifyNoInteractions(tokenService);
    }

    @Test
    void ownersGetAWelcomeMailAboutRegisteringTheirPg() {
        acceptNewUsers();
        SignupRequest request = new SignupRequest("Lakshmi Reddy", "lakshmi@example.com", PHONE, PASSWORD,
                UserRole.OWNER, null);

        service(NO_VERIFY).signup(request);

        assertThat(String.join(" ", sentMail().paragraphs())).contains("PG owner account", "register your PG");
    }

    @Test
    void signupWithVerificationMailsALinkAndLeavesTheAccountUnverified() {
        acceptNewUsers();
        when(tokenService.issue(any(User.class), eq(AuthTokenPurpose.EMAIL_VERIFICATION), eq(TestProps.VERIFICATION_TTL)))
                .thenReturn("verify-token");
        SignupRequest request = new SignupRequest("Anil Varma", EMAIL, PHONE, PASSWORD, UserRole.CUSTOMER, null);

        SignupResponse response = service(VERIFY).signup(request);

        assertThat(savedUser().isEmailVerified()).isFalse();
        assertThat(response.verificationRequired()).isTrue();
        assertThat(response.email()).isEqualTo(EMAIL);
        assertThat(response.message()).contains(EMAIL, "verification link");
        Mail mail = sentMail();
        assertThat(mail.subject()).isEqualTo("Confirm your email address");
        assertThat(mail.actionLabel()).isEqualTo("Verify email");
        assertThat(mail.actionUrl()).isEqualTo(TestProps.SITE + "/verify-email?token=verify-token");
        assertThat(String.join(" ", mail.paragraphs())).contains("customer account has been created", "24 hours");
    }

    @ParameterizedTest(name = "mode={0}, mail configured={1} -> required={2}")
    @CsvSource({
            "true, false, true",
            "TRUE, false, true",
            "false, true, false",
            "False, true, false",
            "auto, true, true",
            "auto, false, false",
            "anything-else, true, true"})
    void emailVerificationFollowsTheConfiguredMode(String mode, boolean mailConfigured, boolean required) {
        lenient().when(mailService.isConfigured()).thenReturn(mailConfigured);

        assertThat(service(mode).emailVerificationRequired()).isEqualTo(required);
    }

    @Test
    void customerCareAccountsCannotBeCreatedThroughSignup() {
        AuthService service = service(NO_VERIFY);
        SignupRequest request = new SignupRequest("Care Agent", "agent@example.com", PHONE, PASSWORD,
                UserRole.SUPPORT, null);

        assertThatThrownBy(() -> service.signup(request)).isInstanceOf(ApiException.class)
                .hasMessageContaining("Customer care accounts")
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(userRepository, mailService);
    }

    @Test
    void anEmailAddressCanOnlyBeRegisteredOnce() {
        when(userRepository.existsByEmailIgnoreCaseAndRole(EMAIL, UserRole.CUSTOMER)).thenReturn(true);
        AuthService service = service(NO_VERIFY);
        SignupRequest request = new SignupRequest("Anil Varma", EMAIL, PHONE, PASSWORD, UserRole.CUSTOMER, null);

        assertThatThrownBy(() -> service.signup(request)).isInstanceOf(ApiException.class)
                .hasMessage("An account with this email already exists")
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(mailService);
    }

    @ParameterizedTest(name = "{2} must not be accepted for {0} <{1}>")
    @CsvSource({
            "Anil Varma, anil@example.com, Varma#2026x",
            "Anil Varma, anil@example.com, xANIL#2026x",
            "Priya S, priyasharma@example.com, Xpriyasharma9!",
            "Mohammed Irfan Khan, mik@example.com, 9Irfan!secret"})
    void passwordsContainingTheNameOrEmailAreRejected(String name, String email, String password) {
        AuthService service = service(NO_VERIFY);
        SignupRequest request = new SignupRequest(name, email, PHONE, password, UserRole.CUSTOMER, null);

        assertThatThrownBy(() -> service.signup(request)).isInstanceOf(ApiException.class)
                .hasMessage("password: must not contain your name or email address")
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(userRepository);
    }

    @Test
    void veryShortNamePartsDoNotBlockAPassword() {
        acceptNewUsers();
        SignupRequest request = new SignupRequest("Raj Rao", "rr@example.com", PHONE, "Raj!Rao#2026", UserRole.CUSTOMER,
                null);

        assertThat(service(NO_VERIFY).signup(request).email()).isEqualTo("rr@example.com");
    }

    @Test
    void createUserNormalisesTheAddressAndRefusesDuplicates() {
        when(userRepository.existsByEmailIgnoreCaseAndRole("Owner@Example.com", UserRole.OWNER)).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCaseAndRole("taken@example.com", UserRole.OWNER)).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuthService service = service(VERIFY);

        User created = service.createUser(" Phone Owner ", " Owner@Example.com ", " 9000000001 ", PASSWORD,
                UserRole.OWNER, null);

        assertThat(created.getName()).isEqualTo("Phone Owner");
        assertThat(created.getEmail()).isEqualTo("owner@example.com");
        assertThat(created.getPhone()).isEqualTo("9000000001");
        assertThat(created.isEmailVerified()).as("accounts created by staff are usable immediately").isTrue();
        assertThatThrownBy(() -> service.createUser("Other", "taken@example.com", PHONE, PASSWORD, UserRole.OWNER, null))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    // ---------------------------------------------------------------- email verification

    @Test
    void verifyingTheEmailActivatesTheAccount() {
        User user = Fixtures.customer(3L);
        user.setEmailVerified(false);
        when(tokenService.consume("verify-token", AuthTokenPurpose.EMAIL_VERIFICATION)).thenReturn(Optional.of(user));

        MessageResponse response = service(VERIFY).verifyEmail("verify-token");

        assertThat(user.isEmailVerified()).isTrue();
        assertThat(response.message()).contains("verified");
    }

    @Test
    void anInvalidVerificationLinkIsRejectedWithACode() {
        when(tokenService.consume("stale", AuthTokenPurpose.EMAIL_VERIFICATION)).thenReturn(Optional.empty());
        AuthService service = service(VERIFY);

        ApiException error = catchThrowableOfType(ApiException.class, () -> service.verifyEmail("stale"));

        assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error.getCode()).isEqualTo(ApiException.CODE_INVALID_TOKEN);
        assertThat(error).hasMessageContaining("invalid or has expired");
    }

    @Test
    void resendVerificationMailsANewLinkToAnUnverifiedAccount() {
        User user = existingUser(UserRole.CUSTOMER, false);
        when(tokenService.issue(user, AuthTokenPurpose.EMAIL_VERIFICATION, TestProps.VERIFICATION_TTL))
                .thenReturn("fresh-token");

        MessageResponse response = service(VERIFY).resendVerification("  " + EMAIL + " ");

        assertThat(response.message()).isEqualTo(AuthService.VERIFICATION_REQUESTED);
        Mail mail = sentMail();
        assertThat(mail.toEmail()).isEqualTo(EMAIL);
        assertThat(mail.actionUrl()).isEqualTo(TestProps.SITE + "/verify-email?token=fresh-token");
    }

    @Test
    void resendVerificationAnswersTheSameWayWhenNothingIsSent() {
        AuthService service = service(VERIFY);
        when(userRepository.findAllByEmailIgnoreCase("unknown@example.com")).thenReturn(List.of());
        User verified = Fixtures.user(8L, UserRole.OWNER, "Ravi Kumar", "ravi@example.com");
        when(userRepository.findAllByEmailIgnoreCase(verified.getEmail())).thenReturn(List.of(verified));
        User justMailed = existingUser(UserRole.CUSTOMER, false);
        when(tokenService.issuedWithin(eq(justMailed), eq(AuthTokenPurpose.EMAIL_VERIFICATION), any())).thenReturn(true);

        assertThat(service.resendVerification("unknown@example.com").message())
                .isEqualTo(service.resendVerification(verified.getEmail()).message())
                .isEqualTo(service.resendVerification(EMAIL).message())
                .isEqualTo(AuthService.VERIFICATION_REQUESTED);

        verify(tokenService, never()).issue(any(), any(), any());
        verifyNoInteractions(mailService);
    }

    // ---------------------------------------------------------------- sign in / out

    @Test
    void signinStartsANewSessionAndEndsThePreviousOne() {
        User user = existingUser(UserRole.OWNER, true);
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(12);
        when(sessionService.create(user, IP, USER_AGENT)).thenReturn(new IssuedSession("session-token", "csrf", expiresAt));
        SigninRequest request = new SigninRequest(" " + EMAIL + " ", PASSWORD, UserRole.OWNER);

        SignedIn signedIn = service(VERIFY).signin(request, IP, USER_AGENT, "previous-token");

        assertThat(signedIn.sessionToken()).isEqualTo("session-token");
        assertThat(signedIn.response().csrfToken()).isEqualTo("csrf");
        assertThat(signedIn.response().expiresAt()).isEqualTo(expiresAt);
        assertThat(signedIn.response().user().email()).isEqualTo(EMAIL);
        assertThat(signedIn.response().user().role()).isEqualTo(UserRole.OWNER);
        assertThat(signedIn.response().user().emailVerified()).isTrue();
        verify(loginAttempts).recordSuccess(EMAIL);
        verify(sessionService).revoke("previous-token");
        verify(loginAttempts, never()).recordFailure(anyString());
    }

    @Test
    void theRoleIsOptionalWhenSigningIn() {
        User user = existingUser(UserRole.CUSTOMER, true);
        when(sessionService.create(user, IP, USER_AGENT))
                .thenReturn(new IssuedSession("session-token", "csrf", LocalDateTime.now().plusHours(1)));
        SigninRequest request = new SigninRequest(EMAIL, PASSWORD, null);

        assertThat(service(VERIFY).signin(request, IP, USER_AGENT, null).sessionToken()).isEqualTo("session-token");
    }

    @Test
    void anUnknownEmailFailsLikeAWrongPasswordAndStillChecksAHash() {
        when(userRepository.findAllByEmailIgnoreCase("nobody@example.com")).thenReturn(List.of());
        AuthService service = service(VERIFY);
        SigninRequest request = new SigninRequest("nobody@example.com", PASSWORD, UserRole.CUSTOMER);

        assertThatThrownBy(() -> service.signin(request, IP, USER_AGENT, null))
                .isInstanceOf(BadCredentialsException.class).hasMessage(INVALID_CREDENTIALS);

        verify(passwordEncoder).matches(eq(PASSWORD), anyString());
        verify(loginAttempts).recordFailure("nobody@example.com");
        verifyNoInteractions(sessionService);
    }

    @Test
    void aWrongPasswordIsCountedAsAFailedAttempt() {
        existingUser(UserRole.CUSTOMER, true);
        AuthService service = service(VERIFY);
        SigninRequest request = new SigninRequest(EMAIL, "Wrong!Pass1", UserRole.CUSTOMER);

        assertThatThrownBy(() -> service.signin(request, IP, USER_AGENT, null))
                .isInstanceOf(BadCredentialsException.class).hasMessage(INVALID_CREDENTIALS);

        verify(loginAttempts).recordFailure(EMAIL);
        verify(loginAttempts, never()).recordSuccess(anyString());
        verifyNoInteractions(sessionService);
    }

    @Test
    void signingInWithTheWrongRoleFailsLikeAnUnknownAccount() {
        existingUser(UserRole.OWNER, true);
        AuthService service = service(VERIFY);
        SigninRequest request = new SigninRequest(EMAIL, PASSWORD, UserRole.CUSTOMER);

        assertThatThrownBy(() -> service.signin(request, IP, USER_AGENT, null))
                .isInstanceOf(BadCredentialsException.class).hasMessage(INVALID_CREDENTIALS);

        verify(loginAttempts).recordFailure(EMAIL);
        verifyNoInteractions(sessionService);
    }

    @Test
    void anUnverifiedAccountCannotSignInWhileVerificationIsRequired() {
        existingUser(UserRole.CUSTOMER, false);
        AuthService service = service(VERIFY);
        SigninRequest request = new SigninRequest(EMAIL, PASSWORD, UserRole.CUSTOMER);

        ApiException error = catchThrowableOfType(ApiException.class,
                () -> service.signin(request, IP, USER_AGENT, null));

        assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(error.getCode()).isEqualTo(ApiException.CODE_EMAIL_NOT_VERIFIED);
        assertThat(error).hasMessageContaining(EMAIL);
        verifyNoInteractions(sessionService);
    }

    @Test
    void anUnverifiedAccountMaySignInWhenVerificationIsSwitchedOff() {
        User user = existingUser(UserRole.CUSTOMER, false);
        when(sessionService.create(user, IP, USER_AGENT))
                .thenReturn(new IssuedSession("session-token", "csrf", LocalDateTime.now().plusHours(1)));
        SigninRequest request = new SigninRequest(EMAIL, PASSWORD, UserRole.CUSTOMER);

        SignedIn signedIn = service(NO_VERIFY).signin(request, IP, USER_AGENT, null);

        assertThat(signedIn.response().user().emailVerified()).isFalse();
    }

    @Test
    void aLockedAddressIsRefusedBeforeAnyLookup() {
        when(loginAttempts.secondsUntilUnlocked(EMAIL)).thenReturn(61L);
        AuthService service = service(VERIFY);
        SigninRequest request = new SigninRequest(EMAIL, PASSWORD, UserRole.CUSTOMER);

        ApiException error = catchThrowableOfType(ApiException.class,
                () -> service.signin(request, IP, USER_AGENT, null));

        assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(error.getCode()).isEqualTo(ApiException.CODE_TOO_MANY_ATTEMPTS);
        assertThat(error.getRetryAfterSeconds()).isEqualTo(61L);
        assertThat(error).hasMessageContaining("2 minute(s)");
        verifyNoInteractions(userRepository, sessionService);
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void aPasswordLongerThanBcryptAcceptsFailsCleanly() {
        existingUser(UserRole.CUSTOMER, true);
        AuthService service = service(VERIFY);
        SigninRequest request = new SigninRequest(EMAIL, "A1!" + "x".repeat(90), UserRole.CUSTOMER);

        assertThatThrownBy(() -> service.signin(request, IP, USER_AGENT, null))
                .isInstanceOf(BadCredentialsException.class).hasMessage(INVALID_CREDENTIALS);

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(loginAttempts).recordFailure(EMAIL);
    }

    @Test
    void theCurrentSessionIsDescribedFromFreshUserData() {
        User user = Fixtures.user(7L, UserRole.OWNER, "Ravi K. Kumar", "ravi@example.com");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(3);
        ActiveSession session = new ActiveSession(11L,
                new AuthUser(7L, "ravi@example.com", "Ravi Kumar", UserRole.OWNER), "csrf", expiresAt);

        SessionResponse response = service(VERIFY).currentSession(session);

        assertThat(response.user().name()).as("the name stored now, not the one from sign-in").isEqualTo("Ravi K. Kumar");
        assertThat(response.csrfToken()).isEqualTo("csrf");
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void theCurrentSessionOfADeletedUserIsNotFound() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());
        AuthService service = service(VERIFY);
        ActiveSession session = new ActiveSession(11L,
                new AuthUser(7L, "ravi@example.com", "Ravi Kumar", UserRole.OWNER), "csrf", LocalDateTime.now());

        assertThatThrownBy(() -> service.currentSession(session)).isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void signoutRevokesTheSession() {
        service(VERIFY).signout("session-token");

        verify(sessionService).revoke("session-token");
    }

    // ---------------------------------------------------------------- password reset

    @Test
    void forgotPasswordMailsASingleUseLink() {
        User user = existingUser(UserRole.CUSTOMER, true);
        when(tokenService.issue(user, AuthTokenPurpose.PASSWORD_RESET, TestProps.RESET_TTL)).thenReturn("reset-token");

        MessageResponse response = service(VERIFY).forgotPassword(EMAIL);

        assertThat(response.message()).isEqualTo(AuthService.RESET_REQUESTED);
        Mail mail = sentMail();
        assertThat(mail.toEmail()).isEqualTo(EMAIL);
        assertThat(mail.subject()).isEqualTo("Reset your password");
        assertThat(mail.actionUrl()).isEqualTo(TestProps.SITE + "/reset-password?token=reset-token");
        assertThat(String.join(" ", mail.paragraphs())).contains("30 minutes", "used once");
    }

    @Test
    void forgotPasswordDoesNotRevealWhetherAnAccountExists() {
        when(userRepository.findAllByEmailIgnoreCase("nobody@example.com")).thenReturn(List.of());
        User recentlyMailed = existingUser(UserRole.CUSTOMER, true);
        when(tokenService.issuedWithin(eq(recentlyMailed), eq(AuthTokenPurpose.PASSWORD_RESET), any())).thenReturn(true);
        AuthService service = service(VERIFY);

        assertThat(service.forgotPassword("nobody@example.com").message())
                .isEqualTo(service.forgotPassword(EMAIL).message())
                .isEqualTo(AuthService.RESET_REQUESTED);

        verify(tokenService, never()).issue(any(), any(), any());
        verifyNoInteractions(mailService);
    }

    @Test
    void resetPasswordChangesThePasswordAndSignsTheUserOutEverywhere() {
        User user = Fixtures.customer(3L);
        user.setEmail(EMAIL);
        user.setEmailVerified(false);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        when(tokenService.consume("reset-token", AuthTokenPurpose.PASSWORD_RESET)).thenReturn(Optional.of(user));

        MessageResponse response = service(VERIFY).resetPassword(new ResetPasswordRequest("reset-token", NEW_PASSWORD));

        assertThat(passwordEncoder.matches(NEW_PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, user.getPasswordHash())).isFalse();
        assertThat(user.isEmailVerified()).as("the link proves the mailbox").isTrue();
        assertThat(response.message()).contains("password has been changed");
        verify(sessionService).revokeAll(3L);
        verify(loginAttempts).recordSuccess(EMAIL);
        Mail mail = sentMail();
        assertThat(mail.subject()).isEqualTo("Your password was changed");
        assertThat(String.join(" ", mail.paragraphs())).contains("signed out on all devices", TestProps.SUPPORT_EMAIL);
        assertThat(mail.actionUrl()).isNull();
    }

    @Test
    void anInvalidResetLinkChangesNothing() {
        when(tokenService.consume("stale", AuthTokenPurpose.PASSWORD_RESET)).thenReturn(Optional.empty());
        AuthService service = service(VERIFY);
        ResetPasswordRequest request = new ResetPasswordRequest("stale", NEW_PASSWORD);

        ApiException error = catchThrowableOfType(ApiException.class, () -> service.resetPassword(request));

        assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error.getCode()).isEqualTo(ApiException.CODE_INVALID_TOKEN);
        verifyNoInteractions(sessionService, mailService);
    }

    @Test
    void aNewPasswordContainingTheNameIsRejectedAndTheOldOneKept() {
        User user = Fixtures.customer(3L);
        String oldHash = user.getPasswordHash();
        when(tokenService.consume("reset-token", AuthTokenPurpose.PASSWORD_RESET)).thenReturn(Optional.of(user));
        AuthService service = service(VERIFY);
        ResetPasswordRequest request = new ResetPasswordRequest("reset-token", "Varma!2026xy");

        assertThatThrownBy(() -> service.resetPassword(request)).isInstanceOf(ApiException.class)
                .hasMessageContaining("must not contain your name");

        assertThat(user.getPasswordHash()).isEqualTo(oldHash);
        verify(sessionService, never()).revokeAll(any());
    }

    @Test
    void ownersRegisteredByCustomerCareGetALinkToChooseTheirPassword() {
        User owner = Fixtures.owner(5L);
        when(tokenService.issue(owner, AuthTokenPurpose.PASSWORD_RESET, TestProps.VERIFICATION_TTL))
                .thenReturn("setup-token");

        service(VERIFY).sendAccountSetupMail(owner, "Green Nest Co-Living");

        Mail mail = sentMail();
        assertThat(mail.toEmail()).isEqualTo(owner.getEmail());
        assertThat(mail.subject()).isEqualTo("Your PG is listed - set your password");
        assertThat(mail.actionLabel()).isEqualTo("Choose your password");
        assertThat(mail.actionUrl()).isEqualTo(TestProps.SITE + "/reset-password?token=setup-token");
        assertThat(String.join(" ", mail.paragraphs())).contains("Green Nest Co-Living", "24 hours");
    }

    // ---------------------------------------------------------------- helpers

    private AuthService service(String emailVerification) {
        return new AuthService(userRepository, passwordEncoder, sessionService, tokenService, loginAttempts, mailService,
                TestProps.app(TestProps.auth(emailVerification, 5, true)));
    }

    private void acceptNewUsers() {
        when(userRepository.existsByEmailIgnoreCaseAndRole(anyString(), any())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** An account with {@link #EMAIL} and {@link #PASSWORD} that the repository finds. */
    private User existingUser(UserRole role, boolean verified) {
        User user = Fixtures.user(3L, role, "Anil Varma", EMAIL);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setEmailVerified(verified);
        when(userRepository.findAllByEmailIgnoreCase(EMAIL)).thenReturn(List.of(user));
        return user;
    }

    private User savedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue();
    }

    private Mail sentMail() {
        ArgumentCaptor<Mail> captor = ArgumentCaptor.forClass(Mail.class);
        verify(mailService).sendAsync(captor.capture());
        return captor.getValue();
    }
}

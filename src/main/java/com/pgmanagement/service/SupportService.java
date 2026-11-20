package com.pgmanagement.service;

import com.pgmanagement.config.AppProperties;
import com.pgmanagement.dto.CommonDtos.*;
import com.pgmanagement.dto.PgDtos.PgDetailDto;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.SupportRequest;
import com.pgmanagement.entity.User;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.repository.SupportRequestRepository;
import com.pgmanagement.repository.UserRepository;
import com.pgmanagement.service.MailService.Mail;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SupportService {

    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final String PASSWORD_DIGITS = "23456789";
    private static final int PASSWORD_RANDOM_LENGTH = 9;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String OWNER_DASHBOARD = "/owner/dashboard";

    private final SupportRequestRepository supportRequestRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final PgService pgService;
    private final NotificationService notificationService;
    private final MailService mailService;
    private final AppProperties appProperties;

    /**
     * Stores an enquiry from the website, forwards it to the business support mailbox (reply-to = the person
     * who wrote) and acknowledges it to the sender.
     */
    @Transactional
    public SupportRequestDto create(SupportRequestCreate req) {
        SupportRequest s = new SupportRequest();
        s.setName(req.name().trim());
        s.setPhone(req.phone().trim());
        s.setEmail(isBlank(req.email()) ? null : req.email().trim());
        s.setRequestType(req.requestType());
        s.setMessage(req.message());
        SupportRequestDto saved = DtoMapper.toSupportDto(supportRequestRepository.save(s));
        AfterCommit.run(() -> {
            mailService.sendAsync(forwardToSupport(saved));
            if (saved.email() != null) {
                mailService.sendAsync(acknowledgement(saved));
            }
        });
        return saved;
    }

    @Transactional(readOnly = true)
    public List<SupportRequestDto> list() {
        return supportRequestRepository.findAllByOrderByCreatedAtDesc().stream().map(DtoMapper::toSupportDto).toList();
    }

    @Transactional
    public SupportRequestDto resolve(Long id) {
        SupportRequest s = supportRequestRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Support request"));
        s.setStatus(Enums.SupportRequestStatus.RESOLVED);
        return DtoMapper.toSupportDto(s);
    }

    /** Customer care registers a PG on behalf of an owner who called in or wrote to the support mailbox. */
    @Transactional
    public AssistedPgResponse registerForOwner(AssistedPgRequest req) {
        String tempPassword = null;
        User owner = userRepository.findByEmailIgnoreCaseAndRole(req.ownerEmail().trim(), Enums.UserRole.OWNER)
                .orElse(null);
        if (owner == null) {
            tempPassword = randomPassword();
            owner = authService.createUser(req.ownerName(), req.ownerEmail(), req.ownerPhone(), tempPassword,
                    Enums.UserRole.OWNER, null);
        } else if (owner.getRole() != Enums.UserRole.OWNER) {
            throw ApiException.conflict("This email belongs to a " + owner.getRole().name().toLowerCase(Locale.ROOT)
                    + " account. Use a different email for the owner.");
        }
        PgDetailDto pg = pgService.create(owner, req.pg(), Enums.RegisteredVia.CUSTOMER_CARE);
        if (req.supportRequestId() != null) {
            supportRequestRepository.findById(req.supportRequestId())
                    .ifPresent(s -> s.setStatus(Enums.SupportRequestStatus.RESOLVED));
        }
        String message = pg.name() + " was registered on your behalf by our customer care team. "
                + "Review the details under Dashboards.";
        if (tempPassword == null) {
            notificationService.notifyAllChannels(owner, "PG registered by customer care", message, OWNER_DASHBOARD);
        } else {
            notificationService.notify(owner, "PG registered by customer care", message, OWNER_DASHBOARD);
            authService.sendAccountSetupMail(owner, pg.name());
        }
        return new AssistedPgResponse(pg, owner.getEmail(), tempPassword != null, tempPassword);
    }

    private Mail forwardToSupport(SupportRequestDto request) {
        Mail mail = Mail.of("team", appProperties.supportEmail(),
                        "New enquiry #" + request.id() + " - " + typeLabel(request.requestType()),
                        "A new request was submitted on the website.",
                        "Name: " + request.name() + "\nPhone: " + request.phone() + "\nEmail: "
                                + (request.email() == null ? "-" : request.email()),
                        "Message: " + (isBlank(request.message()) ? "-" : request.message()))
                .withAction("Open support console", appProperties.siteBaseUrl() + "/support");
        return request.email() == null ? mail : mail.withReplyTo(request.email());
    }

    private Mail acknowledgement(SupportRequestDto request) {
        return Mail.of(request.name(), request.email(), "We received your request (#" + request.id() + ")",
                "Thank you for contacting PG-STAY.IN. Your request \"" + typeLabel(request.requestType())
                        + "\" has reached our team and we will call you on " + request.phone() + " shortly.",
                "You can also reply to this email or write to " + appProperties.supportEmail() + " at any time.");
    }

    private static String typeLabel(Enums.SupportRequestType type) {
        return switch (type) {
            case CALLBACK -> "Call back";
            case REGISTER_PG -> "Register my PG";
            case GENERAL -> "General question";
        };
    }

    /** Temporary password for an owner account created by customer care; always satisfies the password policy. */
    static String randomPassword() {
        StringBuilder sb = new StringBuilder("Pg@");
        for (int i = 0; i < PASSWORD_RANDOM_LENGTH; i++) {
            sb.append(PASSWORD_CHARS.charAt(RANDOM.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.append(PASSWORD_DIGITS.charAt(RANDOM.nextInt(PASSWORD_DIGITS.length()))).toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

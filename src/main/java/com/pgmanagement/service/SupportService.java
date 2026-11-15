package com.pgmanagement.service;

import com.pgmanagement.dto.CommonDtos.*;
import com.pgmanagement.dto.PgDtos.PgDetailDto;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.SupportRequest;
import com.pgmanagement.entity.User;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.repository.SupportRequestRepository;
import com.pgmanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SupportService {

    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SupportRequestRepository supportRequestRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final PgService pgService;
    private final NotificationService notificationService;

    @Transactional
    public SupportRequestDto create(SupportRequestCreate req) {
        SupportRequest s = new SupportRequest();
        s.setName(req.name().trim());
        s.setPhone(req.phone().trim());
        s.setEmail(req.email());
        s.setRequestType(req.requestType());
        s.setMessage(req.message());
        return DtoMapper.toSupportDto(supportRequestRepository.save(s));
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

    /** Customer care registers a PG on behalf of an owner who called in. */
    @Transactional
    public AssistedPgResponse registerForOwner(AssistedPgRequest req) {
        String tempPassword = null;
        User owner = userRepository.findByEmailIgnoreCase(req.ownerEmail().trim()).orElse(null);
        if (owner == null) {
            tempPassword = randomPassword();
            owner = authService.createUser(req.ownerName(), req.ownerEmail(), req.ownerPhone(), tempPassword,
                    Enums.UserRole.OWNER, null);
        } else if (owner.getRole() != Enums.UserRole.OWNER) {
            throw ApiException.conflict("This email belongs to a " + owner.getRole().name().toLowerCase()
                    + " account. Use a different email for the owner.");
        }
        PgDetailDto pg = pgService.create(owner, req.pg(), Enums.RegisteredVia.CUSTOMER_CARE);
        if (req.supportRequestId() != null) {
            supportRequestRepository.findById(req.supportRequestId())
                    .ifPresent(s -> s.setStatus(Enums.SupportRequestStatus.RESOLVED));
        }
        notificationService.notify(owner, "PG registered by customer care",
                pg.name() + " was registered on your behalf by our customer care team. Review details under Dashboards.",
                "/owner/dashboards");
        return new AssistedPgResponse(pg, owner.getEmail(), tempPassword != null, tempPassword);
    }

    private static String randomPassword() {
        StringBuilder sb = new StringBuilder("Pg@");
        for (int i = 0; i < 8; i++) {
            sb.append(PASSWORD_CHARS.charAt(RANDOM.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}

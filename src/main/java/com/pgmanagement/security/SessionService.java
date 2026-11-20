package com.pgmanagement.security;

import com.pgmanagement.config.AppProperties;
import com.pgmanagement.entity.User;
import com.pgmanagement.entity.UserSession;
import com.pgmanagement.repository.AuthTokenRepository;
import com.pgmanagement.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Server-side sessions. Signing in creates a row and hands the browser a random token; every request looks the
 * token up again, so signing out (or resetting the password) invalidates it immediately.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    /** The raw token is returned exactly once, when the session is created. */
    public record IssuedSession(String token, String csrfToken, LocalDateTime expiresAt) {
    }

    public record ActiveSession(Long sessionId, AuthUser user, String csrfToken, LocalDateTime expiresAt) {
    }

    /** Avoids a database write on every request: "last used" is refreshed at most once a minute. */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
    private static final Duration KEEP_FINISHED = Duration.ofDays(1);
    private static final int MAX_TOKEN_LENGTH = 128;
    private static final int MAX_IP_LENGTH = 45;
    private static final int MAX_USER_AGENT_LENGTH = 255;

    private final UserSessionRepository sessionRepository;
    private final AuthTokenRepository authTokenRepository;
    private final AppProperties appProperties;
    private final Clock clock;

    @Transactional
    public IssuedSession create(User user, String ipAddress, String userAgent) {
        LocalDateTime now = LocalDateTime.now(clock);
        String token = SecureTokens.random();
        UserSession session = new UserSession();
        session.setUser(user);
        session.setTokenHash(SecureTokens.sha256Hex(token));
        session.setCsrfToken(SecureTokens.random());
        session.setCreatedAt(now);
        session.setLastUsedAt(now);
        session.setExpiresAt(now.plus(appProperties.auth().sessionTtl()));
        session.setIpAddress(truncate(ipAddress, MAX_IP_LENGTH));
        session.setUserAgent(truncate(userAgent, MAX_USER_AGENT_LENGTH));
        sessionRepository.save(session);
        return new IssuedSession(token, session.getCsrfToken(), session.getExpiresAt());
    }

    /** Resolves the cookie value to a live session: not revoked, not expired and not idle for too long. */
    @Transactional
    public Optional<ActiveSession> authenticate(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return sessionRepository.findByTokenHash(SecureTokens.sha256Hex(token))
                .filter(session -> isActive(session, now))
                .map(session -> {
                    if (session.getLastUsedAt().plus(TOUCH_INTERVAL).isBefore(now)) {
                        session.setLastUsedAt(now);
                    }
                    User user = session.getUser();
                    return new ActiveSession(session.getId(),
                            new AuthUser(user.getId(), user.getEmail(), user.getName(), user.getRole()),
                            session.getCsrfToken(), session.getExpiresAt());
                });
    }

    @Transactional
    public void revoke(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return;
        }
        sessionRepository.findByTokenHash(SecureTokens.sha256Hex(token))
                .filter(session -> session.getRevokedAt() == null)
                .ifPresent(session -> session.setRevokedAt(LocalDateTime.now(clock)));
    }

    /** Signs the user out everywhere, e.g. after a password reset. */
    @Transactional
    public int revokeAll(Long userId) {
        return sessionRepository.revokeAllForUser(userId, LocalDateTime.now(clock));
    }

    /** Housekeeping: finished sessions and expired email tokens are deleted a day after they ended. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeFinished() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(KEEP_FINISHED);
        int sessions = sessionRepository.deleteFinishedBefore(cutoff);
        int tokens = authTokenRepository.deleteExpiredBefore(cutoff);
        log.info("Purged {} finished sessions and {} expired email tokens", sessions, tokens);
    }

    private boolean isActive(UserSession session, LocalDateTime now) {
        return session.getRevokedAt() == null
                && session.getExpiresAt().isAfter(now)
                && session.getLastUsedAt().plus(appProperties.auth().idleTimeout()).isAfter(now);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

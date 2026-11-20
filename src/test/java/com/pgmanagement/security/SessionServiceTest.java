package com.pgmanagement.security;

import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.User;
import com.pgmanagement.entity.UserSession;
import com.pgmanagement.repository.AuthTokenRepository;
import com.pgmanagement.repository.UserSessionRepository;
import com.pgmanagement.security.SessionService.ActiveSession;
import com.pgmanagement.security.SessionService.IssuedSession;
import com.pgmanagement.support.Fixtures;
import com.pgmanagement.support.MutableClock;
import com.pgmanagement.support.TestProps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    private static final String TOKEN = "raw-session-token";
    private static final String CSRF = "csrf-token-value";

    @Mock
    private UserSessionRepository sessionRepository;
    @Mock
    private AuthTokenRepository authTokenRepository;

    private final MutableClock clock = MutableClock.fixed();
    private final User user = Fixtures.user(7L, Enums.UserRole.OWNER, "Ravi Kumar", "ravi@example.com");
    private SessionService service;

    @BeforeEach
    void setUp() {
        service = new SessionService(sessionRepository, authTokenRepository, TestProps.app(), clock);
    }

    @Test
    void createStoresOnlyTheHashOfTheTokenAndReturnsTheRawTokenOnce() {
        IssuedSession issued = service.create(user, "203.0.113.9", "Mozilla/5.0");

        UserSession saved = savedSession();
        assertThat(issued.token()).hasSize(43).isNotEqualTo(saved.getTokenHash());
        assertThat(saved.getTokenHash()).isEqualTo(SecureTokens.sha256Hex(issued.token()));
        assertThat(saved.getCsrfToken()).hasSize(43).isEqualTo(issued.csrfToken()).isNotEqualTo(issued.token());
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getCreatedAt()).isEqualTo(now()).isEqualTo(saved.getLastUsedAt());
        assertThat(saved.getExpiresAt()).isEqualTo(now().plus(TestProps.SESSION_TTL)).isEqualTo(issued.expiresAt());
        assertThat(saved.getRevokedAt()).isNull();
        assertThat(saved.getIpAddress()).isEqualTo("203.0.113.9");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
    }

    @Test
    void createTruncatesOversizedClientDetails() {
        service.create(user, "1".repeat(60), "U".repeat(400));

        UserSession saved = savedSession();
        assertThat(saved.getIpAddress()).hasSize(45);
        assertThat(saved.getUserAgent()).hasSize(255);
    }

    @Test
    void createAcceptsMissingClientDetails() {
        service.create(user, null, null);

        UserSession saved = savedSession();
        assertThat(saved.getIpAddress()).isNull();
        assertThat(saved.getUserAgent()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void authenticateIgnoresMissingTokensWithoutTouchingTheDatabase(String token) {
        assertThat(service.authenticate(token)).isEmpty();

        verifyNoInteractions(sessionRepository);
    }

    @Test
    void authenticateIgnoresAbsurdlyLongTokens() {
        assertThat(service.authenticate("x".repeat(129))).isEmpty();

        verify(sessionRepository, never()).findByTokenHash(anyString());
    }

    @Test
    void authenticateReturnsTheUserAndCsrfTokenOfALiveSession() {
        UserSession session = storedSession();

        Optional<ActiveSession> active = service.authenticate(TOKEN);

        assertThat(active).isPresent();
        assertThat(active.get().sessionId()).isEqualTo(session.getId());
        assertThat(active.get().user()).isEqualTo(new AuthUser(7L, "ravi@example.com", "Ravi Kumar",
                Enums.UserRole.OWNER));
        assertThat(active.get().csrfToken()).isEqualTo(CSRF);
        assertThat(active.get().expiresAt()).isEqualTo(session.getExpiresAt());
    }

    @Test
    void lastUsedIsRefreshedAtMostOnceAMinute() {
        UserSession session = storedSession();
        LocalDateTime created = session.getLastUsedAt();

        clock.advance(Duration.ofSeconds(59));
        service.authenticate(TOKEN);
        assertThat(session.getLastUsedAt()).isEqualTo(created);

        clock.advance(Duration.ofSeconds(2));
        service.authenticate(TOKEN);
        assertThat(session.getLastUsedAt()).isEqualTo(now()).isAfter(created);
    }

    @Test
    void anIdleSessionExpiresEvenThoughItsLifetimeIsNotOver() {
        storedSession();

        clock.advance(TestProps.IDLE_TIMEOUT.minusSeconds(1));
        assertThat(service.authenticate(TOKEN)).isPresent();

        clock.advance(TestProps.IDLE_TIMEOUT);
        assertThat(service.authenticate(TOKEN)).isEmpty();
    }

    @Test
    void aSessionEndsAtItsAbsoluteLifetimeEvenWhenItIsUsedAllTheTime() {
        storedSession();
        Duration step = Duration.ofHours(1);
        for (int hour = 1; hour < TestProps.SESSION_TTL.toHours(); hour++) {
            clock.advance(step);
            assertThat(service.authenticate(TOKEN)).as("after %d hours", hour).isPresent();
        }

        clock.advance(step);

        assertThat(service.authenticate(TOKEN)).isEmpty();
    }

    @Test
    void aRevokedSessionIsRejected() {
        UserSession session = storedSession();
        session.setRevokedAt(now());

        assertThat(service.authenticate(TOKEN)).isEmpty();
    }

    @Test
    void anUnknownTokenIsRejected() {
        when(sessionRepository.findByTokenHash(SecureTokens.sha256Hex("unknown"))).thenReturn(Optional.empty());

        assertThat(service.authenticate("unknown")).isEmpty();
    }

    @Test
    void revokeMarksTheSessionAsEnded() {
        UserSession session = storedSession();

        service.revoke(TOKEN);

        assertThat(session.getRevokedAt()).isEqualTo(now());
    }

    @Test
    void revokeKeepsTheOriginalTimeOfAnAlreadyRevokedSession() {
        UserSession session = storedSession();
        LocalDateTime earlier = now().minusHours(1);
        session.setRevokedAt(earlier);

        service.revoke(TOKEN);

        assertThat(session.getRevokedAt()).isEqualTo(earlier);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void revokeIgnoresMissingTokens(String token) {
        service.revoke(token);

        verifyNoInteractions(sessionRepository);
    }

    @Test
    void revokeAllEndsEverySessionOfTheUser() {
        when(sessionRepository.revokeAllForUser(7L, now())).thenReturn(3);

        assertThat(service.revokeAll(7L)).isEqualTo(3);
    }

    @Test
    void purgeDeletesSessionsAndTokensThatEndedMoreThanADayAgo() {
        LocalDateTime cutoff = now().minusDays(1);
        when(sessionRepository.deleteFinishedBefore(cutoff)).thenReturn(4);
        when(authTokenRepository.deleteExpiredBefore(cutoff)).thenReturn(2);

        service.purgeFinished();

        verify(sessionRepository).deleteFinishedBefore(cutoff);
        verify(authTokenRepository).deleteExpiredBefore(cutoff);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private UserSession savedSession() {
        ArgumentCaptor<UserSession> captor = ArgumentCaptor.forClass(UserSession.class);
        verify(sessionRepository).save(captor.capture());
        return captor.getValue();
    }

    /** A session created "now" that the repository finds by the hash of {@link #TOKEN}. */
    private UserSession storedSession() {
        UserSession session = new UserSession();
        session.setId(11L);
        session.setUser(user);
        session.setTokenHash(SecureTokens.sha256Hex(TOKEN));
        session.setCsrfToken(CSRF);
        session.setCreatedAt(now());
        session.setLastUsedAt(now());
        session.setExpiresAt(now().plus(TestProps.SESSION_TTL));
        when(sessionRepository.findByTokenHash(session.getTokenHash())).thenReturn(Optional.of(session));
        return session;
    }
}

package com.pgmanagement.service;

import com.pgmanagement.entity.AuthToken;
import com.pgmanagement.entity.Enums.AuthTokenPurpose;
import com.pgmanagement.entity.User;
import com.pgmanagement.repository.AuthTokenRepository;
import com.pgmanagement.security.SecureTokens;
import com.pgmanagement.support.Fixtures;
import com.pgmanagement.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthTokenServiceTest {

    private static final String RAW_TOKEN = "raw-emailed-token";
    private static final Duration ONE_DAY = Duration.ofHours(24);

    @Mock
    private AuthTokenRepository tokenRepository;

    private final MutableClock clock = MutableClock.fixed();
    private final User user = Fixtures.customer(3L);
    private AuthTokenService service;

    @BeforeEach
    void setUp() {
        service = new AuthTokenService(tokenRepository, clock);
    }

    @Test
    void issuingStoresOnlyTheHashAndRetiresEarlierUnusedTokens() {
        String rawToken = service.issue(user, AuthTokenPurpose.EMAIL_VERIFICATION, ONE_DAY);

        ArgumentCaptor<AuthToken> captor = ArgumentCaptor.forClass(AuthToken.class);
        InOrder order = inOrder(tokenRepository);
        order.verify(tokenRepository).invalidateUnused(3L, AuthTokenPurpose.EMAIL_VERIFICATION, now());
        order.verify(tokenRepository).save(captor.capture());
        AuthToken saved = captor.getValue();
        assertThat(rawToken).hasSize(43);
        assertThat(saved.getTokenHash()).isEqualTo(SecureTokens.sha256Hex(rawToken)).isNotEqualTo(rawToken);
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getPurpose()).isEqualTo(AuthTokenPurpose.EMAIL_VERIFICATION);
        assertThat(saved.getCreatedAt()).isEqualTo(now());
        assertThat(saved.getExpiresAt()).isEqualTo(now().plus(ONE_DAY));
        assertThat(saved.getUsedAt()).isNull();
    }

    @Test
    void aValidTokenIsRedeemedExactlyOnce() {
        AuthToken token = storedToken(AuthTokenPurpose.PASSWORD_RESET);

        assertThat(service.consume(RAW_TOKEN, AuthTokenPurpose.PASSWORD_RESET)).containsSame(user);
        assertThat(token.getUsedAt()).isEqualTo(now());

        assertThat(service.consume(RAW_TOKEN, AuthTokenPurpose.PASSWORD_RESET)).isEmpty();
    }

    @Test
    void surroundingWhitespaceFromCopyingTheLinkIsIgnored() {
        storedToken(AuthTokenPurpose.EMAIL_VERIFICATION);

        assertThat(service.consume("  " + RAW_TOKEN + "\n", AuthTokenPurpose.EMAIL_VERIFICATION)).isPresent();
    }

    @Test
    void aTokenCannotBeUsedForAnotherPurpose() {
        AuthToken token = storedToken(AuthTokenPurpose.EMAIL_VERIFICATION);

        assertThat(service.consume(RAW_TOKEN, AuthTokenPurpose.PASSWORD_RESET)).isEmpty();
        assertThat(token.getUsedAt()).isNull();
    }

    @Test
    void anExpiredTokenIsRejected() {
        AuthToken token = storedToken(AuthTokenPurpose.PASSWORD_RESET);

        clock.advance(ONE_DAY);

        assertThat(service.consume(RAW_TOKEN, AuthTokenPurpose.PASSWORD_RESET)).isEmpty();
        assertThat(token.getUsedAt()).isNull();
    }

    @Test
    void anUnknownTokenIsRejected() {
        when(tokenRepository.findByTokenHash(SecureTokens.sha256Hex("unknown"))).thenReturn(Optional.empty());

        assertThat(service.consume("unknown", AuthTokenPurpose.PASSWORD_RESET)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void missingTokensAreRejectedWithoutALookup(String token) {
        assertThat(service.consume(token, AuthTokenPurpose.PASSWORD_RESET)).isEmpty();

        verifyNoInteractions(tokenRepository);
    }

    @Test
    void knowsWhetherATokenWasIssuedRecently() {
        AuthToken latest = new AuthToken();
        latest.setCreatedAt(now());
        when(tokenRepository.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(3L, AuthTokenPurpose.PASSWORD_RESET))
                .thenReturn(Optional.of(latest));
        Duration cooldown = Duration.ofMinutes(2);

        clock.advance(Duration.ofSeconds(119));
        assertThat(service.issuedWithin(user, AuthTokenPurpose.PASSWORD_RESET, cooldown)).isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.issuedWithin(user, AuthTokenPurpose.PASSWORD_RESET, cooldown)).isFalse();
    }

    @Test
    void nothingWasIssuedRecentlyWhenThereIsNoToken() {
        when(tokenRepository.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(3L, AuthTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.empty());

        assertThat(service.issuedWithin(user, AuthTokenPurpose.EMAIL_VERIFICATION, Duration.ofMinutes(2))).isFalse();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** A token issued "now", valid for a day, that the repository finds by the hash of {@link #RAW_TOKEN}. */
    private AuthToken storedToken(AuthTokenPurpose purpose) {
        AuthToken token = new AuthToken();
        token.setId(1L);
        token.setUser(user);
        token.setPurpose(purpose);
        token.setTokenHash(SecureTokens.sha256Hex(RAW_TOKEN));
        token.setCreatedAt(now());
        token.setExpiresAt(now().plus(ONE_DAY));
        when(tokenRepository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));
        return token;
    }
}

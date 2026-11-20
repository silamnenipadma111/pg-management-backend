package com.pgmanagement.service;

import com.pgmanagement.entity.AuthToken;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.User;
import com.pgmanagement.repository.AuthTokenRepository;
import com.pgmanagement.security.SecureTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/** Issues and redeems the single-use tokens behind the "verify email" and "reset password" links. */
@Service
@RequiredArgsConstructor
public class AuthTokenService {

    private final AuthTokenRepository tokenRepository;
    private final Clock clock;

    /** Creates a new token (earlier unused tokens for the same purpose stop working) and returns its raw value. */
    @Transactional
    public String issue(User user, Enums.AuthTokenPurpose purpose, Duration timeToLive) {
        LocalDateTime now = LocalDateTime.now(clock);
        tokenRepository.invalidateUnused(user.getId(), purpose, now);
        String rawToken = SecureTokens.random();
        AuthToken token = new AuthToken();
        token.setUser(user);
        token.setPurpose(purpose);
        token.setTokenHash(SecureTokens.sha256Hex(rawToken));
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(timeToLive));
        tokenRepository.save(token);
        return rawToken;
    }

    /** Redeems a token exactly once; empty when it is unknown, expired, already used or for another purpose. */
    @Transactional
    public Optional<User> consume(String rawToken, Enums.AuthTokenPurpose purpose) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return tokenRepository.findByTokenHash(SecureTokens.sha256Hex(rawToken.trim()))
                .filter(token -> token.getPurpose() == purpose && token.getUsedAt() == null
                        && token.getExpiresAt().isAfter(now))
                .map(token -> {
                    token.setUsedAt(now);
                    return token.getUser();
                });
    }

    /** True when a token for this purpose was issued very recently (stops mail bombing through "resend"). */
    @Transactional(readOnly = true)
    public boolean issuedWithin(User user, Enums.AuthTokenPurpose purpose, Duration window) {
        LocalDateTime threshold = LocalDateTime.now(clock).minus(window);
        return tokenRepository.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(user.getId(), purpose)
                .filter(token -> token.getCreatedAt().isAfter(threshold))
                .isPresent();
    }
}

package com.pgmanagement.repository;

import com.pgmanagement.entity.AuthToken;
import com.pgmanagement.entity.Enums;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

    @EntityGraph(attributePaths = "user")
    Optional<AuthToken> findByTokenHash(String tokenHash);

    Optional<AuthToken> findFirstByUserIdAndPurposeOrderByCreatedAtDesc(Long userId, Enums.AuthTokenPurpose purpose);

    /** A new token replaces every earlier unused token issued for the same purpose. */
    @Modifying
    @Query("update AuthToken t set t.usedAt = :now where t.user.id = :userId and t.purpose = :purpose and t.usedAt is null")
    int invalidateUnused(@Param("userId") Long userId, @Param("purpose") Enums.AuthTokenPurpose purpose,
                         @Param("now") LocalDateTime now);

    @Modifying
    @Query("delete from AuthToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}

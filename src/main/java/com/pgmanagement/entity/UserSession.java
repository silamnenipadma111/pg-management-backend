package com.pgmanagement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A signed-in browser session. The browser only holds a random token in an HttpOnly cookie; the database keeps
 * its SHA-256 hash, so a session can be revoked (sign out) and a leaked database never reveals usable tokens.
 */
@Entity
@Table(name = "user_sessions", indexes = @Index(name = "ix_sessions_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** Sent back by the website in the X-CSRF-Token header of every state changing request. */
    @Column(nullable = false, length = 64)
    private String csrfToken;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime lastUsedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime revokedAt;

    @Column(length = 45)
    private String ipAddress;

    @Column(length = 255)
    private String userAgent;
}

package com.pgmanagement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "app_users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Enums.Gender gender;

    /** False until the owner of the mailbox opened the verification link (when verification is required). */
    @Column(nullable = false)
    private boolean emailVerified = true;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}

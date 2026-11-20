package com.pgmanagement.repository;

import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** One mailbox may hold a customer and an owner account, so lookups are always by email + role. */
    Optional<User> findByEmailIgnoreCaseAndRole(String email, Enums.UserRole role);

    List<User> findAllByEmailIgnoreCase(String email);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndRole(String email, Enums.UserRole role);

    boolean existsByEmailIgnoreCase(String email);
}

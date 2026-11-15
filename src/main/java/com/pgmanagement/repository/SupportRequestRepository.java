package com.pgmanagement.repository;

import com.pgmanagement.entity.SupportRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportRequestRepository extends JpaRepository<SupportRequest, Long> {

    List<SupportRequest> findAllByOrderByCreatedAtDesc();
}

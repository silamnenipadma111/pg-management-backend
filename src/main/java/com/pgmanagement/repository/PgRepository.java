package com.pgmanagement.repository;

import com.pgmanagement.entity.Pg;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PgRepository extends JpaRepository<Pg, Long> {

    @Query("select distinct p from Pg p left join fetch p.rooms r where p.active = true")
    List<Pg> findAllActiveWithRooms();

    List<Pg> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    Optional<Pg> findByIdAndOwnerId(Long id, Long ownerId);

    Optional<Pg> findByNameIgnoreCase(String name);
}

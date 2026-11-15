package com.pgmanagement.repository;

import com.pgmanagement.entity.Bed;
import com.pgmanagement.entity.Enums;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BedRepository extends JpaRepository<Bed, Long> {

    @Query("select b.room.pg.id, b.status, count(b) from Bed b where b.room.pg.id in :pgIds group by b.room.pg.id, b.status")
    List<Object[]> countByPgAndStatus(@Param("pgIds") List<Long> pgIds);

    @Query("select count(b) from Bed b where b.room.pg.id = :pgId and b.status = :status")
    long countByPgIdAndStatus(@Param("pgId") Long pgId, @Param("status") Enums.BedStatus status);
}

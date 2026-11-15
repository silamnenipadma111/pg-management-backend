package com.pgmanagement.repository;

import com.pgmanagement.entity.Booking;
import com.pgmanagement.entity.Enums;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    boolean existsByBedId(Long bedId);

    @Query("select b from Booking b where b.pg.owner.id = :ownerId and (:pgId is null or b.pg.id = :pgId) "
            + "and b.status in :statuses order by b.createdAt desc")
    List<Booking> findForOwner(@Param("ownerId") Long ownerId, @Param("pgId") Long pgId,
                               @Param("statuses") Collection<Enums.BookingStatus> statuses);

    @Query("select count(b) from Booking b where b.pg.id = :pgId and b.status = :status")
    long countByPgIdAndStatus(@Param("pgId") Long pgId, @Param("status") Enums.BookingStatus status);
}

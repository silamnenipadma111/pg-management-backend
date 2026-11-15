package com.pgmanagement.repository;

import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Query("select p from Payment p where p.booking.customer.id = :customerId and p.status in :statuses "
            + "order by p.dueDate asc, p.installmentNo asc")
    List<Payment> findForCustomer(@Param("customerId") Long customerId,
                                  @Param("statuses") Collection<Enums.PaymentStatus> statuses);

    @Query("select p from Payment p where p.booking.pg.owner.id = :ownerId and (:pgId is null or p.booking.pg.id = :pgId) "
            + "and p.status in :statuses and p.booking.status in :bookingStatuses order by p.dueDate asc")
    List<Payment> findForOwner(@Param("ownerId") Long ownerId, @Param("pgId") Long pgId,
                               @Param("statuses") Collection<Enums.PaymentStatus> statuses,
                               @Param("bookingStatuses") Collection<Enums.BookingStatus> bookingStatuses);

    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.booking.pg.id = :pgId and p.status = :status "
            + "and p.booking.status in :bookingStatuses")
    BigDecimal sumByPgAndStatus(@Param("pgId") Long pgId, @Param("status") Enums.PaymentStatus status,
                                @Param("bookingStatuses") Collection<Enums.BookingStatus> bookingStatuses);

    @Query("select count(p) from Payment p where p.booking.pg.id = :pgId and p.status = :status")
    long countByPgAndStatus(@Param("pgId") Long pgId, @Param("status") Enums.PaymentStatus status);

    long countByInvoiceNumberIsNotNull();
}

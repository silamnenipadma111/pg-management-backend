package com.pgmanagement.service;

import com.pgmanagement.dto.DashboardDtos.OwnerDashboardDto;
import com.pgmanagement.dto.DashboardDtos.PgStatsDto;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.Payment;
import com.pgmanagement.entity.Pg;
import com.pgmanagement.repository.BedRepository;
import com.pgmanagement.repository.BookingRepository;
import com.pgmanagement.repository.PaymentRepository;
import com.pgmanagement.repository.PgRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final Set<Enums.BookingStatus> ACTIVE = EnumSet.of(Enums.BookingStatus.APPROVED,
            Enums.BookingStatus.BOOKED);

    private final PgRepository pgRepository;
    private final BedRepository bedRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;

    @Transactional(readOnly = true)
    public OwnerDashboardDto ownerDashboard(Long ownerId) {
        List<Pg> pgs = pgRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
        Map<Long, EnumMap<Enums.BedStatus, Long>> beds = new HashMap<>();
        if (!pgs.isEmpty()) {
            for (Object[] row : bedRepository.countByPgAndStatus(pgs.stream().map(Pg::getId).toList())) {
                beds.computeIfAbsent((Long) row[0], k -> new EnumMap<>(Enums.BedStatus.class))
                        .put((Enums.BedStatus) row[1], (Long) row[2]);
            }
        }
        Map<Long, Long> overdue = new HashMap<>();
        for (Payment p : paymentRepository.findForOwner(ownerId, null, EnumSet.of(Enums.PaymentStatus.DUE), ACTIVE)) {
            if (p.getDueDate().isBefore(LocalDate.now())) {
                overdue.merge(p.getBooking().getPg().getId(), 1L, Long::sum);
            }
        }

        List<PgStatsDto> stats = new ArrayList<>();
        for (Pg pg : pgs) {
            EnumMap<Enums.BedStatus, Long> c = beds.getOrDefault(pg.getId(), new EnumMap<>(Enums.BedStatus.class));
            long available = c.getOrDefault(Enums.BedStatus.AVAILABLE, 0L);
            long blocked = c.getOrDefault(Enums.BedStatus.BLOCKED, 0L);
            long booked = c.getOrDefault(Enums.BedStatus.BOOKED, 0L);
            long unavailable = c.getOrDefault(Enums.BedStatus.UNAVAILABLE, 0L);
            long total = available + blocked + booked + unavailable;
            stats.add(new PgStatsDto(pg.getId(), pg.getName(), pg.getArea(), pg.getCity(), total, available, blocked,
                    booked, unavailable,
                    bookingRepository.countByPgIdAndStatus(pg.getId(), Enums.BookingStatus.REQUESTED),
                    paymentRepository.countByPgAndStatus(pg.getId(), Enums.PaymentStatus.SUBMITTED),
                    paymentRepository.sumByPgAndStatus(pg.getId(), Enums.PaymentStatus.DUE, ACTIVE),
                    overdue.getOrDefault(pg.getId(), 0L), percent(booked + blocked, total)));
        }
        return new OwnerDashboardDto(totals(stats), stats);
    }

    private static PgStatsDto totals(List<PgStatsDto> s) {
        long total = s.stream().mapToLong(PgStatsDto::totalBeds).sum();
        long booked = s.stream().mapToLong(PgStatsDto::bookedBeds).sum();
        long blocked = s.stream().mapToLong(PgStatsDto::blockedBeds).sum();
        return new PgStatsDto(null, "All PGs", null, null, total,
                s.stream().mapToLong(PgStatsDto::availableBeds).sum(), blocked, booked,
                s.stream().mapToLong(PgStatsDto::unavailableBeds).sum(),
                s.stream().mapToLong(PgStatsDto::pendingApprovals).sum(),
                s.stream().mapToLong(PgStatsDto::pendingPaymentConfirmations).sum(),
                s.stream().map(PgStatsDto::pendingRentAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                s.stream().mapToLong(PgStatsDto::overdueCount).sum(), percent(booked + blocked, total));
    }

    private static int percent(long part, long total) {
        return total == 0 ? 0 : (int) Math.round(part * 100.0 / total);
    }
}

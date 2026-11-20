package com.pgmanagement.service;

import com.pgmanagement.dto.DashboardDtos.OwnerDashboardDto;
import com.pgmanagement.dto.DashboardDtos.PgStatsDto;
import com.pgmanagement.entity.Bed;
import com.pgmanagement.entity.Booking;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.Payment;
import com.pgmanagement.entity.Pg;
import com.pgmanagement.entity.User;
import com.pgmanagement.repository.BedRepository;
import com.pgmanagement.repository.BookingRepository;
import com.pgmanagement.repository.PaymentRepository;
import com.pgmanagement.repository.PgRepository;
import com.pgmanagement.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private PgRepository pgRepository;
    @Mock
    private BedRepository bedRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @InjectMocks
    private DashboardService service;

    private final User owner = Fixtures.owner(1L);

    @Test
    void anOwnerWithoutPgsSeesEmptyTotals() {
        when(pgRepository.findByOwnerIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(paymentRepository.findForOwner(eq(1L), any(), any(), any())).thenReturn(List.of());

        OwnerDashboardDto dashboard = service.ownerDashboard(1L);

        assertThat(dashboard.pgs()).isEmpty();
        assertThat(dashboard.totals().totalBeds()).isZero();
        assertThat(dashboard.totals().occupancyPercent()).isZero();
        verifyNoInteractions(bedRepository);
    }

    @Test
    void countsBedsOccupancyAndOverdueRentPerPgAndInTotal() {
        Pg first = Fixtures.pg(10L, owner, "Green Nest", "Madhapur", "Hyderabad");
        Pg second = Fixtures.pg(20L, owner, "Elite", "KPHB", "Hyderabad");
        Bed bed = Fixtures.bed(1L, Fixtures.room(1L, first, "101", 2, false, 8000), "A", Enums.BedStatus.BOOKED);
        Booking booking = Fixtures.booking(1L, Fixtures.customer(2L), bed, Enums.BookingType.MONTHLY, 3,
                LocalDate.now().minusMonths(1), Enums.BookingStatus.BOOKED);
        Payment overdue = Fixtures.payment(1L, booking, 1, 8000, LocalDate.now().minusDays(3), Enums.PaymentStatus.DUE);
        Payment upcoming = Fixtures.payment(2L, booking, 2, 8000, LocalDate.now().plusDays(5), Enums.PaymentStatus.DUE);
        when(pgRepository.findByOwnerIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(first, second));
        when(bedRepository.countByPgAndStatus(List.of(10L, 20L))).thenReturn(List.of(
                new Object[]{10L, Enums.BedStatus.AVAILABLE, 5L}, new Object[]{10L, Enums.BedStatus.BOOKED, 2L},
                new Object[]{10L, Enums.BedStatus.BLOCKED, 1L}, new Object[]{20L, Enums.BedStatus.UNAVAILABLE, 4L}));
        when(paymentRepository.findForOwner(eq(1L), any(), any(), any())).thenReturn(List.of(overdue, upcoming));
        when(bookingRepository.countByPgIdAndStatus(10L, Enums.BookingStatus.REQUESTED)).thenReturn(2L);
        when(paymentRepository.countByPgAndStatus(10L, Enums.PaymentStatus.SUBMITTED)).thenReturn(1L);
        when(paymentRepository.sumByPgAndStatus(eq(10L), eq(Enums.PaymentStatus.DUE), any()))
                .thenReturn(BigDecimal.valueOf(16000));
        when(paymentRepository.sumByPgAndStatus(eq(20L), eq(Enums.PaymentStatus.DUE), any())).thenReturn(BigDecimal.ZERO);

        OwnerDashboardDto dashboard = service.ownerDashboard(1L);

        PgStatsDto stats = dashboard.pgs().get(0);
        assertThat(stats.totalBeds()).isEqualTo(8);
        assertThat(stats.occupancyPercent()).isEqualTo(38);
        assertThat(stats.pendingApprovals()).isEqualTo(2);
        assertThat(stats.pendingPaymentConfirmations()).isEqualTo(1);
        assertThat(stats.overdueCount()).isEqualTo(1);
        assertThat(dashboard.pgs().get(1).occupancyPercent()).isZero();
        assertThat(dashboard.totals().totalBeds()).isEqualTo(12);
        assertThat(dashboard.totals().unavailableBeds()).isEqualTo(4);
        assertThat(dashboard.totals().pendingRentAmount()).isEqualByComparingTo("16000");
        assertThat(dashboard.totals().occupancyPercent()).isEqualTo(25);
    }
}

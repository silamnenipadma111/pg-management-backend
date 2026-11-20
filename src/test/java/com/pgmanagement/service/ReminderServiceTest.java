package com.pgmanagement.service;

import com.pgmanagement.config.MessagingProperties;
import com.pgmanagement.dto.BookingDtos.ReminderResult;
import com.pgmanagement.entity.Bed;
import com.pgmanagement.entity.Booking;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.Payment;
import com.pgmanagement.entity.Pg;
import com.pgmanagement.entity.User;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.repository.PaymentRepository;
import com.pgmanagement.support.Fixtures;
import com.pgmanagement.support.TestProps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReminderServiceTest {

    private static final LocalDate TODAY = LocalDate.now();
    private static final String LINK = "/my-rents?tab=upcoming";

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private MessagingService messagingService;

    private final User owner = Fixtures.owner(1L);
    private final User customer = Fixtures.customer(2L);

    @ParameterizedTest(name = "due in {0} days, every {1} overdue days -> {2}")
    @CsvSource({"3, 3, true", "1, 3, true", "0, 3, true", "2, 3, false", "10, 3, false",
            "-3, 3, true", "-6, 3, true", "-4, 3, false", "-3, 0, false"})
    void remindsOnTheConfiguredDays(int daysLeft, int overdueEvery, boolean expected) {
        Payment payment = duePayment(1L, 1L, TODAY.plusDays(daysLeft));
        MessagingProperties.Reminders cfg = new MessagingProperties.Reminders(true, false, List.of(3, 1, 0), overdueEvery);

        assertThat(ReminderService.shouldRemind(payment, TODAY, cfg)).isEqualTo(expected);
    }

    @Test
    void neverRemindsTwiceOnTheSameDayOrWithoutConfiguredDays() {
        Payment payment = duePayment(1L, 1L, TODAY);
        MessagingProperties.Reminders noDays = new MessagingProperties.Reminders(true, false, null, 3);
        assertThat(ReminderService.shouldRemind(payment, TODAY, noDays)).isFalse();

        payment.setLastReminderOn(TODAY);
        assertThat(ReminderService.shouldRemind(payment, TODAY, TestProps.messaging().remindersOrDefault())).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"5, 'is due on', '(in 5 days)'", "1, 'is due tomorrow', ''", "0, 'is due today', ''",
            "-2, 'was due on', 'overdue by 2 day(s)'"})
    void describesTheDueDate(int daysLeft, String start, String end) {
        assertThat(ReminderService.dueText(TODAY.plusDays(daysLeft), daysLeft)).startsWith(start).endsWith(end);
    }

    @Test
    void dailyRunRemindsOnlyTheNextUnpaidInstallmentOfEachBooking() {
        Payment dueToday = duePayment(1L, 10L, TODAY);
        Payment laterInstallment = Fixtures.payment(2L, dueToday.getBooking(), 2, 8000, TODAY.plusDays(1),
                Enums.PaymentStatus.DUE);
        Payment waitingForCash = duePayment(3L, 20L, TODAY);
        Payment submitted = Fixtures.payment(4L, waitingForCash.getBooking(), 0, 8000, TODAY.minusMonths(1),
                Enums.PaymentStatus.SUBMITTED);
        when(paymentRepository.findByStatusForBookings(eq(Enums.PaymentStatus.DUE), any()))
                .thenReturn(List.of(dueToday, laterInstallment, waitingForCash));
        when(paymentRepository.findByStatusForBookings(eq(Enums.PaymentStatus.SUBMITTED), any()))
                .thenReturn(List.of(submitted));
        when(messagingService.send(any(), anyString(), anyString(), anyString())).thenReturn(List.of("EMAIL"));

        int sent = service(true, false).sendDueReminders();

        assertThat(sent).isEqualTo(1);
        assertThat(dueToday.getLastReminderOn()).isEqualTo(TODAY);
        assertThat(Arrays.asList(laterInstallment.getLastReminderOn(), waitingForCash.getLastReminderOn()))
                .containsOnlyNulls();
        verify(notificationService).notify(eq(customer), eq("Rent due reminder"), anyString(), eq(LINK));
        verify(messagingService).send(any(), eq("Rent due reminder"), anyString(), eq(TestProps.SITE + LINK));
    }

    @Test
    void disabledRemindersDoNothing() {
        assertThat(service(false, true).sendDueReminders()).isZero();

        verifyNoInteractions(paymentRepository, notificationService, messagingService);
    }

    @Test
    void startupRunHonoursItsSwitch() {
        service(true, false).onStartup();
        verifyNoInteractions(paymentRepository);

        when(paymentRepository.findByStatusForBookings(any(), any())).thenReturn(List.of());
        service(true, true).onStartup();
        verify(paymentRepository).findByStatusForBookings(eq(Enums.PaymentStatus.DUE), any());
    }

    @Test
    void ownerCanRemindNowAndSeesTheChannels() {
        Payment overdue = duePayment(1L, 10L, TODAY.minusDays(2));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(overdue));
        when(messagingService.send(any(), eq("Rent overdue"), anyString(), anyString())).thenReturn(List.of("EMAIL", "SMS"));

        ReminderResult result = service(true, false).remindNow(1L, 1L);

        assertThat(result.channels()).containsExactly("WEBSITE", "EMAIL", "SMS");
        assertThat(result.sentOn()).isEqualTo(TODAY);
    }

    @Test
    void remindNowRejectsForeignPaidOrInactivePayments() {
        Payment paid = duePayment(1L, 10L, TODAY);
        paid.setStatus(Enums.PaymentStatus.PAID);
        Payment cancelled = duePayment(2L, 20L, TODAY);
        cancelled.getBooking().setStatus(Enums.BookingStatus.CANCELLED);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(paid));
        when(paymentRepository.findById(2L)).thenReturn(Optional.of(cancelled));
        ReminderService service = service(true, false);

        assertThatThrownBy(() -> service.remindNow(99L, 1L)).isInstanceOf(ApiException.class)
                .hasMessage("Payment not found");
        assertThatThrownBy(() -> service.remindNow(1L, 1L)).isInstanceOf(ApiException.class)
                .hasMessageContaining("only for unpaid rent");
        assertThatThrownBy(() -> service.remindNow(1L, 2L)).isInstanceOf(ApiException.class)
                .hasMessageContaining("active booking");
    }

    private ReminderService service(boolean enabled, boolean runOnStartup) {
        MessagingProperties props = new MessagingProperties(null, null, null,
                new MessagingProperties.Reminders(enabled, runOnStartup, List.of(3, 1, 0), 3));
        return new ReminderService(paymentRepository, notificationService, messagingService, props, TestProps.app());
    }

    private Payment duePayment(long paymentId, long bookingId, LocalDate dueDate) {
        Pg pg = Fixtures.pg(bookingId, owner, "Green Nest", "Madhapur", "Hyderabad");
        Bed bed = Fixtures.bed(bookingId, Fixtures.room(bookingId, pg, "101", 2, false, 8000), "A", Enums.BedStatus.BOOKED);
        Booking booking = Fixtures.booking(bookingId, customer, bed, Enums.BookingType.MONTHLY, 3, TODAY.minusMonths(1),
                Enums.BookingStatus.BOOKED);
        return Fixtures.payment(paymentId, booking, 1, 8000, dueDate, Enums.PaymentStatus.DUE);
    }
}

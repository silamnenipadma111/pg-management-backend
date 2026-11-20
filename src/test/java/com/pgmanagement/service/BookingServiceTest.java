package com.pgmanagement.service;

import com.pgmanagement.dto.BookingDtos.BookingDto;
import com.pgmanagement.dto.BookingDtos.BookingRequest;
import com.pgmanagement.dto.BookingDtos.CashPaymentRequest;
import com.pgmanagement.dto.BookingDtos.CheckoutDto;
import com.pgmanagement.dto.BookingDtos.CheckoutRequest;
import com.pgmanagement.dto.BookingDtos.CompletePaymentRequest;
import com.pgmanagement.dto.BookingDtos.PaymentDto;
import com.pgmanagement.entity.Bed;
import com.pgmanagement.entity.Booking;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.Enums.BookingStatus;
import com.pgmanagement.entity.Enums.BookingType;
import com.pgmanagement.entity.Enums.PaymentStatus;
import com.pgmanagement.entity.Payment;
import com.pgmanagement.entity.Pg;
import com.pgmanagement.entity.Room;
import com.pgmanagement.entity.User;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.repository.BedRepository;
import com.pgmanagement.repository.BookingRepository;
import com.pgmanagement.repository.PaymentRepository;
import com.pgmanagement.repository.UserRepository;
import com.pgmanagement.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 12, 1);
    private static final String STATUS = "status";
    private static final String CARD = "CARD";
    private static final String UPI = "UPI";
    private static final String ORDER = "ORD-1";

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BedRepository bedRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationService notificationService;
    @InjectMocks
    private BookingService service;

    private final User owner = Fixtures.owner(1L);
    private final User customer = Fixtures.customer(2L);
    private Pg pg;
    private Room room;
    private Bed bed;

    @BeforeEach
    void setUp() {
        pg = Fixtures.pg(1L, owner, "Green Nest", "Madhapur", "Hyderabad");
        pg.setUpiId("owner@okaxis");
        room = Fixtures.room(1L, pg, "101", 2, false, 8000);
        bed = Fixtures.bed(1L, room, "A", Enums.BedStatus.AVAILABLE);
        lenient().when(userRepository.findById(2L)).thenReturn(Optional.of(customer));
        lenient().when(bedRepository.findById(1L)).thenReturn(Optional.of(bed));
    }

    @ParameterizedTest
    @CsvSource({"DAILY, 1", "DAILY, 90", "WEEKLY, 52", "MONTHLY, 36", "YEARLY, 5"})
    void acceptsDurationsWithinTheLimit(BookingType type, int duration) {
        assertThatCode(() -> BookingService.validateDuration(type, duration)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"DAILY, 0", "DAILY, 91", "WEEKLY, 53", "MONTHLY, 37", "YEARLY, 6"})
    void rejectsDurationsOutsideTheLimit(BookingType type, int duration) {
        assertThatThrownBy(() -> BookingService.validateDuration(type, duration)).isInstanceOf(ApiException.class)
                .hasMessageContaining("must be between 1 and");
    }

    @ParameterizedTest
    @CsvSource({"DAILY, 3, 2026-12-04, 1200", "WEEKLY, 2, 2026-12-15, 5600", "MONTHLY, 6, 2027-06-01, 48000",
            "YEARLY, 1, 2027-12-01, 96000"})
    void computesEndDateAndTotal(BookingType type, int duration, LocalDate end, BigDecimal total) {
        assertThat(BookingService.endDate(type, START, duration)).isEqualTo(end);
        assertThat(BookingService.totalAmount(room, type, duration)).isEqualByComparingTo(total);
    }

    @ParameterizedTest
    @CsvSource(value = {"DAILY, NULL, FULL", "MONTHLY, FULL, MONTHLY", "YEARLY, NULL, MONTHLY", "YEARLY, FULL, FULL"},
            nullValues = "NULL")
    void requestBlocksTheBedAndNotifiesBothParties(BookingType type, Enums.PaymentPlan requested, Enums.PaymentPlan plan) {
        BookingDto dto = service.requestBooking(2L, new BookingRequest(1L, type, 1, START, requested, "hello"));

        assertThat(dto.status()).isEqualTo(BookingStatus.REQUESTED);
        assertThat(dto.paymentPlan()).isEqualTo(plan);
        assertThat(bed.getStatus()).isEqualTo(Enums.BedStatus.BLOCKED);
        verify(bookingRepository).save(any(Booking.class));
        verify(notificationService).notifyAllChannels(eq(owner), eq("New booking request"),
                contains(customer.getEmail()), anyString());
        verify(notificationService).emailConfirmation(eq(customer), eq("Booking request sent"), anyString(), anyString());
    }

    @Test
    void requestIsRefusedForInactivePgsTakenBedsAndUnknownIds() {
        BookingRequest request = new BookingRequest(1L, BookingType.MONTHLY, 1, START, null, null);
        BookingRequest unknownBed = new BookingRequest(9L, BookingType.MONTHLY, 1, START, null, null);
        when(bedRepository.findById(9L)).thenReturn(Optional.empty());
        when(userRepository.findById(8L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestBooking(8L, request)).hasMessage("User not found");
        assertThatThrownBy(() -> service.requestBooking(2L, unknownBed)).hasMessage("Bed not found");
        bed.setStatus(Enums.BedStatus.BOOKED);
        assertThatThrownBy(() -> service.requestBooking(2L, request)).isInstanceOf(ApiException.class)
                .extracting(STATUS).isEqualTo(HttpStatus.CONFLICT);
        pg.setActive(false);
        assertThatThrownBy(() -> service.requestBooking(2L, request)).hasMessageContaining("not accepting bookings");
        verifyNoInteractions(notificationService);
    }

    @ParameterizedTest
    @CsvSource({"MONTHLY, MONTHLY, 3, 3, 8000", "YEARLY, MONTHLY, 1, 12, 8000", "YEARLY, FULL, 2, 2, 96000",
            "WEEKLY, FULL, 2, 1, 5600"})
    void approvalGeneratesTheInstallments(BookingType type, Enums.PaymentPlan plan, int duration, int count,
                                          BigDecimal amount) {
        Booking booking = booking(BookingStatus.REQUESTED);
        booking.setBookingType(type);
        booking.setPaymentPlan(plan);
        booking.setDuration(duration);
        booking.setEndDate(BookingService.endDate(type, START, duration));
        booking.setTotalAmount(BookingService.totalAmount(room, type, duration));

        BookingDto dto = service.approve(1L, 10L, "Welcome");

        assertThat(dto.status()).isEqualTo(BookingStatus.APPROVED);
        assertThat(booking.getPayments()).hasSize(count).allSatisfy(p ->
                assertThat(p.getAmount()).isEqualByComparingTo(amount));
        assertThat(booking.getPayments().get(0).getDueDate()).isEqualTo(START);
        verify(notificationService).notifyAllChannels(eq(customer), eq("Booking approved"), anyString(), anyString());
        verify(notificationService).emailConfirmation(eq(owner), eq("Booking approved"), anyString(), anyString());
    }

    @Test
    void onlyTheOwnerCanApproveAndOnlyRequestedBookings() {
        booking(BookingStatus.BOOKED);

        assertThatThrownBy(() -> service.approve(9L, 10L, null)).hasMessage("Booking not found");
        assertThatThrownBy(() -> service.approve(1L, 10L, null)).hasMessageContaining("Only requested bookings");
        assertThatThrownBy(() -> service.reject(1L, 10L, null)).hasMessageContaining("can no longer be rejected");
    }

    @Test
    void rejectAndCancelFreeTheBedUnlessMoneyIsInvolved() {
        Booking booking = booking(BookingStatus.APPROVED);
        bed.setStatus(Enums.BedStatus.BLOCKED);
        Payment payment = Fixtures.payment(1L, booking, 1, 8000, START, PaymentStatus.PAID);

        assertThatThrownBy(() -> service.reject(1L, 10L, null)).hasMessageContaining("confirmed payments");
        assertThatThrownBy(() -> service.cancel(2L, 10L)).hasMessageContaining("already submitted");
        assertThatThrownBy(() -> service.cancel(7L, 10L)).hasMessage("Booking not found");

        payment.setStatus(PaymentStatus.DUE);
        assertThat(service.reject(1L, 10L, " ").status()).isEqualTo(BookingStatus.REJECTED);
        assertThat(bed.getStatus()).isEqualTo(Enums.BedStatus.AVAILABLE);
        assertThat(booking.getPayments()).isEmpty();
        assertThatThrownBy(() -> service.cancel(2L, 10L)).hasMessageContaining("Only pending or approved");

        booking.setStatus(BookingStatus.REQUESTED);
        assertThat(service.cancel(2L, 10L).status()).isEqualTo(BookingStatus.CANCELLED);
        verify(notificationService).notifyAllChannels(eq(owner), eq("Booking cancelled"), anyString(), anyString());
    }

    @Test
    void checkoutIssuesAnOrderAndAUpiLinkOnlyForUpi() {
        Payment payment = duePayment(booking(BookingStatus.APPROVED));

        CheckoutDto card = service.checkout(2L, 1L, new CheckoutRequest(CARD));
        CheckoutDto upi = service.checkout(2L, 1L, new CheckoutRequest(UPI));

        assertThat(card.upiUri()).isNull();
        assertThat(upi.upiUri()).startsWith("upi://pay?pa=owner%40okaxis&pn=Green%20Nest&am=8000.00&cu=INR");
        assertThat(payment.getGatewayOrderId()).isEqualTo(upi.orderId()).startsWith("ORD1X");
        pg.setUpiId(" ");
        CheckoutRequest upiRequest = new CheckoutRequest(UPI);
        assertThatThrownBy(() -> service.checkout(2L, 1L, upiRequest)).hasMessageContaining("has not added a UPI ID");
    }

    @ParameterizedTest
    @CsvSource(value = {
            "UPI, NULL, NULL, NULL, NULL, choose the UPI app",
            "CARD, NULL, NULL, Anil, NULL, Card holder name and card number",
            "CARD, NULL, 4242, NULL, NULL, Card holder name and card number",
            "NET_BANKING, NULL, NULL, NULL, ' ', choose your bank"}, nullValues = "NULL")
    void onlinePaymentNeedsTheDetailsOfItsMethod(String method, String upiApp, String last4, String holder, String bank,
                                                 String message) {
        duePayment(booking(BookingStatus.APPROVED)).setGatewayOrderId(ORDER);
        CompletePaymentRequest request = new CompletePaymentRequest(ORDER, method, upiApp, null, null, last4, null,
                holder, bank);

        assertThatThrownBy(() -> service.completeOnline(2L, 1L, request)).isInstanceOf(ApiException.class)
                .hasMessageContaining(message);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "UPI, PHONEPE, me@ybl, 123456789012, NULL, NULL, NULL, PhonePe - me@ybl, 123456789012",
            "UPI, OTHER, NULL, NULL, NULL, NULL, NULL, UPI, ORD-1",
            "CARD, NULL, NULL, NULL, 4242, Anil Varma, NULL, Card **** 4242 (Anil Varma), ORD-1",
            "NET_BANKING, NULL, NULL, NULL, NULL, NULL, HDFC Bank, Net banking - HDFC Bank, ORD-1"}, nullValues = "NULL")
    void onlinePaymentBooksTheBedAndIssuesAnInvoice(String method, String upiApp, String payer, String utr, String last4,
                                                    String holder, String bank, String details, String reference) {
        Booking booking = booking(BookingStatus.APPROVED);
        Payment payment = duePayment(booking);
        payment.setGatewayOrderId(ORDER);

        PaymentDto dto = service.completeOnline(2L, 1L,
                new CompletePaymentRequest(ORDER, method, upiApp, payer, utr, last4, null, holder, bank));

        assertThat(dto.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(dto.paymentDetails()).isEqualTo(details);
        assertThat(dto.transactionRef()).isEqualTo(reference);
        assertThat(dto.invoiceNumber()).matches("INV-\\d{4}-000001");
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.BOOKED);
        assertThat(bed.getStatus()).isEqualTo(Enums.BedStatus.BOOKED);
        verify(notificationService).notifyAllChannels(eq(owner), eq("Payment received"), anyString(), anyString());
        verify(notificationService).notifyAllChannels(eq(customer), eq("Payment successful"), anyString(), anyString());
    }

    @Test
    void anInstallmentIsPayableOnlyInOrderAndOnlyOnce() {
        Booking booking = booking(BookingStatus.APPROVED);
        Payment first = duePayment(booking);
        Payment second = Fixtures.payment(2L, booking, 2, 8000, START.plusMonths(1), PaymentStatus.DUE);
        when(paymentRepository.findById(2L)).thenReturn(Optional.of(second));
        CheckoutRequest card = new CheckoutRequest(CARD);
        CompletePaymentRequest wrongOrder = new CompletePaymentRequest("OTHER", CARD, null, null, null, "4242", "VISA",
                "Anil", null);

        assertThatThrownBy(() -> service.checkout(2L, 2L, card)).hasMessageContaining("earlier rent installments");
        assertThatThrownBy(() -> service.checkout(7L, 1L, card)).hasMessage("Payment not found");
        assertThatThrownBy(() -> service.completeOnline(2L, 1L, wrongOrder)).hasMessageContaining("session has expired");
        first.setStatus(PaymentStatus.SUBMITTED);
        assertThatThrownBy(() -> service.checkout(2L, 1L, card)).hasMessageContaining("waiting for the owner");
        first.setStatus(PaymentStatus.PAID);
        assertThatThrownBy(() -> service.checkout(2L, 1L, card)).hasMessageContaining("already paid");
        booking.setStatus(BookingStatus.REQUESTED);
        assertThatThrownBy(() -> service.checkout(2L, 1L, card)).hasMessageContaining("after the owner approves");
    }

    @Test
    void cashIsSubmittedThenAcceptedOrRejectedByTheOwner() {
        Booking booking = booking(BookingStatus.APPROVED);
        Payment payment = duePayment(booking);

        assertThatThrownBy(() -> service.confirmPayment(1L, 1L)).hasMessageContaining("can be accepted");
        assertThatThrownBy(() -> service.rejectPayment(1L, 1L, null)).hasMessageContaining("can be rejected");

        PaymentDto submitted = service.requestCashConfirmation(2L, 1L,
                new CashPaymentRequest(LocalDate.of(2026, 11, 30), " Ravi ", " at the office "));
        assertThat(submitted.status()).isEqualTo(PaymentStatus.SUBMITTED);
        assertThat(submitted.paymentDetails()).isEqualTo("Cash handed to Ravi on 30 Nov 2026 - at the office");
        assertThat(submitted.transactionRef()).isEqualTo("CASH-1");

        assertThat(service.rejectPayment(1L, 1L, " ").ownerNote()).isEqualTo("Cash not received");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DUE);
        assertThat(payment.getPaymentMethod()).isNull();

        payment.setStatus(PaymentStatus.SUBMITTED);
        assertThat(service.confirmPayment(1L, 1L).status()).isEqualTo(PaymentStatus.PAID);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.BOOKED);
        assertThatThrownBy(() -> service.confirmPayment(9L, 1L)).hasMessage("Payment not found");
        verify(notificationService).emailConfirmation(eq(owner), eq("Cash payment accepted"), anyString(), anyString());
        verify(notificationService).emailConfirmation(eq(owner), eq("Cash payment not accepted"), anyString(), anyString());
    }

    @Test
    void invoicesAreForPaidRentAndOnlyForTheTwoParties() {
        Payment payment = duePayment(booking(BookingStatus.BOOKED));
        when(paymentRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.invoice(2L, 1L)).hasMessageContaining("Invoice is generated after");
        assertThatThrownBy(() -> service.invoice(7L, 1L)).hasMessage("Payment not found");
        assertThatThrownBy(() -> service.invoice(2L, 404L)).hasMessage("Payment not found");

        payment.setStatus(PaymentStatus.PAID);
        payment.setInvoiceNumber("INV-2026-000001");
        assertThat(service.invoice(1L, 1L).invoiceNumber()).isEqualTo("INV-2026-000001");
        assertThat(service.invoice(2L, 1L).pgAddress()).isEqualTo("12 Main Road, Madhapur, Hyderabad, Telangana - 500081");
    }

    @Test
    void customerSeesOnlyTheNextRentPerBookingAndHistoryNewestFirst() {
        Booking booking = booking(BookingStatus.BOOKED);
        Payment older = Fixtures.payment(1L, booking, 1, 8000, START, PaymentStatus.PAID);
        older.setPaidAt(LocalDateTime.of(2026, 12, 1, 10, 0));
        Payment newer = Fixtures.payment(2L, booking, 2, 8000, START.plusMonths(1), PaymentStatus.PAID);
        newer.setPaidAt(LocalDateTime.of(2027, 1, 1, 10, 0));
        Payment next = Fixtures.payment(3L, booking, 3, 8000, START.plusMonths(2), PaymentStatus.DUE);
        Payment later = Fixtures.payment(4L, booking, 4, 8000, START.plusMonths(3), PaymentStatus.DUE);
        Booking cancelled = Fixtures.booking(11L, customer, bed, BookingType.MONTHLY, 1, START, BookingStatus.CANCELLED);
        Payment orphan = Fixtures.payment(5L, cancelled, 1, 8000, START, PaymentStatus.DUE);
        when(paymentRepository.findForCustomer(eq(2L), any())).thenReturn(List.of(older, newer))
                .thenReturn(List.of(orphan, later, next));

        assertThat(service.myPayments(2L, "HISTORY")).extracting(PaymentDto::id).containsExactly(2L, 1L);
        assertThat(service.myPayments(2L, "upcoming")).extracting(PaymentDto::id).containsExactly(3L);
    }

    @Test
    void listsDefaultToEveryStatus() {
        when(bookingRepository.findByCustomerIdOrderByCreatedAtDesc(2L)).thenReturn(List.of());
        when(bookingRepository.findForOwner(eq(1L), eq(null), any())).thenReturn(List.of());
        when(paymentRepository.findForOwner(eq(1L), eq(5L), any(), any())).thenReturn(List.of());

        assertThat(service.myBookings(2L)).isEmpty();
        assertThat(service.ownerBookings(1L, null, null)).isEmpty();
        assertThat(service.ownerPayments(1L, 5L, java.util.Set.of())).isEmpty();
        verify(bookingRepository).findForOwner(1L, null, java.util.EnumSet.allOf(BookingStatus.class));
    }

    /** Booking 10 of the customer for bed 1, found by the repository. */
    private Booking booking(BookingStatus status) {
        Booking booking = Fixtures.booking(10L, customer, bed, BookingType.MONTHLY, 3, START, status);
        lenient().when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        return booking;
    }

    /** Payment 1: the first installment, found by the repository. */
    private Payment duePayment(Booking booking) {
        Payment payment = Fixtures.payment(1L, booking, 1, 8000, START, PaymentStatus.DUE);
        lenient().when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        return payment;
    }
}

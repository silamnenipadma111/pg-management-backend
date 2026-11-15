package com.pgmanagement.service;

import com.pgmanagement.dto.BookingDtos.*;
import com.pgmanagement.entity.*;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.repository.BedRepository;
import com.pgmanagement.repository.BookingRepository;
import com.pgmanagement.repository.PaymentRepository;
import com.pgmanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BookingService {

    private static final Set<Enums.BookingStatus> ACTIVE = EnumSet.of(Enums.BookingStatus.APPROVED,
            Enums.BookingStatus.BOOKED);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final BookingRepository bookingRepository;
    private final BedRepository bedRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    // ---------------------------------------------------------------- customer

    @Transactional
    public BookingDto requestBooking(Long customerId, BookingRequest req) {
        User customer = userRepository.findById(customerId).orElseThrow(() -> ApiException.notFound("User"));
        Bed bed = bedRepository.findById(req.bedId()).orElseThrow(() -> ApiException.notFound("Bed"));
        Room room = bed.getRoom();
        Pg pg = room.getPg();
        if (!pg.isActive()) {
            throw ApiException.badRequest("This PG is not accepting bookings");
        }
        if (bed.getStatus() != Enums.BedStatus.AVAILABLE) {
            throw ApiException.conflict("Bed " + room.getRoomNumber() + "-" + bed.getBedLabel() + " is not available");
        }
        validateDuration(req.bookingType(), req.duration());

        Enums.PaymentPlan plan = switch (req.bookingType()) {
            case DAILY, WEEKLY -> Enums.PaymentPlan.FULL;
            case MONTHLY -> Enums.PaymentPlan.MONTHLY;
            case YEARLY -> req.paymentPlan() == null ? Enums.PaymentPlan.MONTHLY : req.paymentPlan();
        };

        Booking booking = new Booking();
        booking.setCustomer(customer);
        booking.setPg(pg);
        booking.setBed(bed);
        booking.setBookingType(req.bookingType());
        booking.setPaymentPlan(plan);
        booking.setDuration(req.duration());
        booking.setStartDate(req.startDate());
        booking.setEndDate(endDate(req.bookingType(), req.startDate(), req.duration()));
        booking.setTotalAmount(totalAmount(room, req.bookingType(), req.duration()));
        booking.setCustomerNote(req.note());
        bed.setStatus(Enums.BedStatus.BLOCKED);
        bookingRepository.save(booking);
        bedRepository.flush();

        notificationService.notify(pg.getOwner(), "New booking request",
                String.format("%s (Phone: %s, Email: %s) requested Room %s Bed %s at %s - %s x %d from %s. Amount: Rs. %s",
                        customer.getName(), customer.getPhone(), customer.getEmail(), room.getRoomNumber(),
                        bed.getBedLabel(), pg.getName(), label(req.bookingType()), req.duration(),
                        req.startDate().format(DATE), booking.getTotalAmount().toPlainString()),
                "/owner/dashboards?tab=approvals");
        return DtoMapper.toBookingDto(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingDto> myBookings(Long customerId) {
        return bookingRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(DtoMapper::toBookingDto).toList();
    }

    @Transactional
    public BookingDto cancel(Long customerId, Long bookingId) {
        Booking b = bookingRepository.findById(bookingId)
                .filter(x -> x.getCustomer().getId().equals(customerId))
                .orElseThrow(() -> ApiException.notFound("Booking"));
        if (b.getStatus() != Enums.BookingStatus.REQUESTED && b.getStatus() != Enums.BookingStatus.APPROVED) {
            throw ApiException.badRequest("Only pending or approved bookings can be cancelled");
        }
        if (b.getPayments().stream().anyMatch(p -> p.getStatus() != Enums.PaymentStatus.DUE)) {
            throw ApiException.badRequest("A payment is already submitted for this booking. Please contact the owner.");
        }
        b.setStatus(Enums.BookingStatus.CANCELLED);
        b.getPayments().clear();
        b.getBed().setStatus(Enums.BedStatus.AVAILABLE);
        notificationService.notify(b.getPg().getOwner(), "Booking cancelled",
                b.getCustomer().getName() + " cancelled the booking for Room " + b.getBed().getRoom().getRoomNumber()
                        + " Bed " + b.getBed().getBedLabel() + " at " + b.getPg().getName() + ". The bed is available again.",
                "/owner/dashboards");
        return DtoMapper.toBookingDto(b);
    }

    @Transactional(readOnly = true)
    public List<PaymentDto> myPayments(Long customerId, String scope) {
        Set<Enums.PaymentStatus> statuses = "history".equalsIgnoreCase(scope)
                ? EnumSet.of(Enums.PaymentStatus.PAID)
                : EnumSet.of(Enums.PaymentStatus.DUE, Enums.PaymentStatus.SUBMITTED);
        List<PaymentDto> list = paymentRepository.findForCustomer(customerId, statuses).stream()
                .filter(p -> ACTIVE.contains(p.getBooking().getStatus()) || p.getStatus() == Enums.PaymentStatus.PAID)
                .map(DtoMapper::toPaymentDto).toList();
        if ("history".equalsIgnoreCase(scope)) {
            return list.stream().sorted((a, b) -> b.paidAt().compareTo(a.paidAt())).toList();
        }
        return list;
    }

    @Transactional
    public PaymentDto pay(Long customerId, Long paymentId, PayRequest req) {
        Payment p = paymentRepository.findById(paymentId)
                .filter(x -> x.getBooking().getCustomer().getId().equals(customerId))
                .orElseThrow(() -> ApiException.notFound("Payment"));
        Booking b = p.getBooking();
        if (!ACTIVE.contains(b.getStatus())) {
            throw ApiException.badRequest("Payment is allowed only after the owner approves the booking");
        }
        if (p.getStatus() != Enums.PaymentStatus.DUE) {
            throw ApiException.badRequest("This rent is already " + p.getStatus().name().toLowerCase(Locale.ROOT));
        }
        boolean earlierDue = b.getPayments().stream()
                .anyMatch(o -> o.getInstallmentNo() < p.getInstallmentNo() && o.getStatus() == Enums.PaymentStatus.DUE);
        if (earlierDue) {
            throw ApiException.badRequest("Please pay earlier rent installments first");
        }
        p.setStatus(Enums.PaymentStatus.SUBMITTED);
        p.setPaymentMethod(req.paymentMethod());
        p.setTransactionRef(req.transactionRef() == null || req.transactionRef().isBlank()
                ? "TXN" + System.currentTimeMillis() : req.transactionRef().trim());
        p.setSubmittedAt(LocalDateTime.now());
        p.setOwnerNote(null);
        notificationService.notify(b.getPg().getOwner(), "Payment received - please confirm",
                String.format("%s (Phone: %s) paid Rs. %s via %s (Ref: %s) for Room %s Bed %s at %s, installment %d/%d.",
                        b.getCustomer().getName(), b.getCustomer().getPhone(), p.getAmount().toPlainString(),
                        p.getPaymentMethod(), p.getTransactionRef(), b.getBed().getRoom().getRoomNumber(),
                        b.getBed().getBedLabel(), b.getPg().getName(), p.getInstallmentNo(), b.getPayments().size()),
                "/owner/dashboards?tab=approvals");
        return DtoMapper.toPaymentDto(p);
    }

    // ---------------------------------------------------------------- owner

    @Transactional(readOnly = true)
    public List<BookingDto> ownerBookings(Long ownerId, Long pgId, Set<Enums.BookingStatus> statuses) {
        Set<Enums.BookingStatus> s = statuses == null || statuses.isEmpty()
                ? EnumSet.allOf(Enums.BookingStatus.class) : statuses;
        return bookingRepository.findForOwner(ownerId, pgId, s).stream().map(DtoMapper::toBookingDto).toList();
    }

    @Transactional
    public BookingDto approve(Long ownerId, Long bookingId, String note) {
        Booking b = ownedBooking(ownerId, bookingId);
        if (b.getStatus() != Enums.BookingStatus.REQUESTED) {
            throw ApiException.badRequest("Only requested bookings can be approved");
        }
        b.setStatus(Enums.BookingStatus.APPROVED);
        b.setOwnerNote(note);
        generateInstallments(b);
        notificationService.notify(b.getCustomer(), "Booking approved",
                String.format("Your request for Room %s Bed %s at %s is approved. Pay Rs. %s to confirm your bed.",
                        b.getBed().getRoom().getRoomNumber(), b.getBed().getBedLabel(), b.getPg().getName(),
                        b.getPayments().get(0).getAmount().toPlainString()),
                "/customer/rents");
        return DtoMapper.toBookingDto(b);
    }

    @Transactional
    public BookingDto reject(Long ownerId, Long bookingId, String note) {
        Booking b = ownedBooking(ownerId, bookingId);
        if (b.getStatus() != Enums.BookingStatus.REQUESTED && b.getStatus() != Enums.BookingStatus.APPROVED) {
            throw ApiException.badRequest("This booking can no longer be rejected");
        }
        if (b.getPayments().stream().anyMatch(p -> p.getStatus() == Enums.PaymentStatus.PAID)) {
            throw ApiException.badRequest("Booking already has confirmed payments");
        }
        b.setStatus(Enums.BookingStatus.REJECTED);
        b.setOwnerNote(note);
        b.getPayments().clear();
        b.getBed().setStatus(Enums.BedStatus.AVAILABLE);
        notificationService.notify(b.getCustomer(), "Booking rejected",
                "Your request for Room " + b.getBed().getRoom().getRoomNumber() + " Bed " + b.getBed().getBedLabel()
                        + " at " + b.getPg().getName() + " was rejected" + (note == null || note.isBlank() ? "." : ": " + note),
                "/customer/rents");
        return DtoMapper.toBookingDto(b);
    }

    @Transactional(readOnly = true)
    public List<PaymentDto> ownerPayments(Long ownerId, Long pgId, Set<Enums.PaymentStatus> statuses) {
        Set<Enums.PaymentStatus> s = statuses == null || statuses.isEmpty()
                ? EnumSet.allOf(Enums.PaymentStatus.class) : statuses;
        return paymentRepository.findForOwner(ownerId, pgId, s, ACTIVE).stream().map(DtoMapper::toPaymentDto).toList();
    }

    @Transactional
    public PaymentDto confirmPayment(Long ownerId, Long paymentId) {
        Payment p = ownedPayment(ownerId, paymentId);
        if (p.getStatus() != Enums.PaymentStatus.SUBMITTED) {
            throw ApiException.badRequest("Only submitted payments can be confirmed");
        }
        p.setStatus(Enums.PaymentStatus.PAID);
        p.setPaidAt(LocalDateTime.now());
        p.setInvoiceNumber(String.format("INV-%d-%06d", LocalDate.now().getYear(), p.getId()));
        Booking b = p.getBooking();
        if (b.getStatus() == Enums.BookingStatus.APPROVED) {
            b.setStatus(Enums.BookingStatus.BOOKED);
            b.getBed().setStatus(Enums.BedStatus.BOOKED);
        }
        notificationService.notify(b.getCustomer(), "Payment confirmed",
                String.format("Owner confirmed your payment of Rs. %s for %s. Invoice %s is ready. Your bed %s-%s is booked.",
                        p.getAmount().toPlainString(), b.getPg().getName(), p.getInvoiceNumber(),
                        b.getBed().getRoom().getRoomNumber(), b.getBed().getBedLabel()),
                "/customer/rents?tab=history");
        return DtoMapper.toPaymentDto(p);
    }

    @Transactional
    public PaymentDto rejectPayment(Long ownerId, Long paymentId, String note) {
        Payment p = ownedPayment(ownerId, paymentId);
        if (p.getStatus() != Enums.PaymentStatus.SUBMITTED) {
            throw ApiException.badRequest("Only submitted payments can be rejected");
        }
        p.setStatus(Enums.PaymentStatus.DUE);
        p.setOwnerNote(note == null || note.isBlank() ? "Payment not received" : note);
        p.setSubmittedAt(null);
        notificationService.notify(p.getBooking().getCustomer(), "Payment not confirmed",
                "The owner could not verify your payment of Rs. " + p.getAmount().toPlainString() + " for "
                        + p.getBooking().getPg().getName() + ": " + p.getOwnerNote() + ". Please pay again.",
                "/customer/rents");
        return DtoMapper.toPaymentDto(p);
    }

    // ---------------------------------------------------------------- shared

    @Transactional(readOnly = true)
    public InvoiceDto invoice(Long userId, Long paymentId) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        Booking b = p.getBooking();
        boolean allowed = b.getCustomer().getId().equals(userId) || b.getPg().getOwner().getId().equals(userId);
        if (!allowed) {
            throw ApiException.notFound("Payment");
        }
        if (p.getStatus() != Enums.PaymentStatus.PAID) {
            throw ApiException.badRequest("Invoice is generated after the owner confirms the payment");
        }
        Pg pg = b.getPg();
        Room room = b.getBed().getRoom();
        return new InvoiceDto(p.getInvoiceNumber(), p.getPaidAt(), pg.getName(),
                pg.getAddressLine() + ", " + pg.getArea() + ", " + pg.getCity() + ", " + pg.getState() + " - " + pg.getPincode(),
                pg.getContactPhone(), pg.getOwner().getName(), b.getCustomer().getName(), b.getCustomer().getEmail(),
                b.getCustomer().getPhone(), room.getRoomNumber(), room.getFloorNumber(), b.getBed().getBedLabel(),
                room.getSharingType(), b.getBookingType(), b.getPaymentPlan(), p.getInstallmentNo(),
                b.getPayments().size(), p.getPeriodStart(), p.getPeriodEnd(), p.getAmount(), p.getPaymentMethod(),
                p.getTransactionRef());
    }

    // ---------------------------------------------------------------- helpers

    private Booking ownedBooking(Long ownerId, Long bookingId) {
        return bookingRepository.findById(bookingId)
                .filter(b -> b.getPg().getOwner().getId().equals(ownerId))
                .orElseThrow(() -> ApiException.notFound("Booking"));
    }

    private Payment ownedPayment(Long ownerId, Long paymentId) {
        return paymentRepository.findById(paymentId)
                .filter(p -> p.getBooking().getPg().getOwner().getId().equals(ownerId))
                .orElseThrow(() -> ApiException.notFound("Payment"));
    }

    static void validateDuration(Enums.BookingType type, int duration) {
        int max = switch (type) {
            case DAILY -> 90;
            case WEEKLY -> 52;
            case MONTHLY -> 36;
            case YEARLY -> 5;
        };
        if (duration < 1 || duration > max) {
            throw ApiException.badRequest("Duration for " + label(type).toLowerCase(Locale.ROOT)
                    + " booking must be between 1 and " + max);
        }
    }

    static LocalDate endDate(Enums.BookingType type, LocalDate start, int duration) {
        return switch (type) {
            case DAILY -> start.plusDays(duration);
            case WEEKLY -> start.plusWeeks(duration);
            case MONTHLY -> start.plusMonths(duration);
            case YEARLY -> start.plusYears(duration);
        };
    }

    static BigDecimal totalAmount(Room room, Enums.BookingType type, int duration) {
        BigDecimal unit = switch (type) {
            case DAILY -> room.getDailyRent();
            case WEEKLY -> room.getWeeklyRent();
            case MONTHLY -> room.getMonthlyRent();
            case YEARLY -> room.getMonthlyRent().multiply(BigDecimal.valueOf(12));
        };
        return unit.multiply(BigDecimal.valueOf(duration));
    }

    /**
     * Daily / weekly bookings are paid in one go, monthly bookings month by month,
     * yearly bookings either per year (FULL) or month by month (MONTHLY).
     */
    private void generateInstallments(Booking b) {
        b.getPayments().clear();
        Room room = b.getBed().getRoom();
        LocalDate start = b.getStartDate();
        boolean monthly = b.getPaymentPlan() == Enums.PaymentPlan.MONTHLY
                && (b.getBookingType() == Enums.BookingType.MONTHLY || b.getBookingType() == Enums.BookingType.YEARLY);
        if (monthly) {
            int months = b.getBookingType() == Enums.BookingType.YEARLY ? b.getDuration() * 12 : b.getDuration();
            for (int i = 0; i < months; i++) {
                addPayment(b, i + 1, room.getMonthlyRent(), start.plusMonths(i), start.plusMonths(i + 1).minusDays(1));
            }
        } else if (b.getBookingType() == Enums.BookingType.YEARLY) {
            BigDecimal yearly = room.getMonthlyRent().multiply(BigDecimal.valueOf(12));
            for (int i = 0; i < b.getDuration(); i++) {
                addPayment(b, i + 1, yearly, start.plusYears(i), start.plusYears(i + 1).minusDays(1));
            }
        } else {
            addPayment(b, 1, b.getTotalAmount(), start, b.getEndDate().minusDays(1));
        }
    }

    private static void addPayment(Booking b, int no, BigDecimal amount, LocalDate from, LocalDate to) {
        Payment p = new Payment();
        p.setBooking(b);
        p.setInstallmentNo(no);
        p.setAmount(amount);
        p.setPeriodStart(from);
        p.setPeriodEnd(to);
        p.setDueDate(from);
        b.getPayments().add(p);
    }

    private static String label(Enums.BookingType type) {
        return switch (type) {
            case DAILY -> "Daily";
            case WEEKLY -> "Weekly";
            case MONTHLY -> "Monthly";
            case YEARLY -> "Yearly";
        };
    }
}

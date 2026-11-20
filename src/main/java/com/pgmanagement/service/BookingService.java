package com.pgmanagement.service;

import com.pgmanagement.dto.BookingDtos.*;
import com.pgmanagement.entity.Bed;
import com.pgmanagement.entity.Booking;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.Payment;
import com.pgmanagement.entity.Pg;
import com.pgmanagement.entity.Room;
import com.pgmanagement.entity.User;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.repository.BedRepository;
import com.pgmanagement.repository.BookingRepository;
import com.pgmanagement.repository.PaymentRepository;
import com.pgmanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Booking and rent payment workflow. Every action notifies the other party on the website and by
 * Email / SMS / WhatsApp, and emails a confirmation to the person who performed it.
 */
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final Set<Enums.BookingStatus> ACTIVE = EnumSet.of(Enums.BookingStatus.APPROVED,
            Enums.BookingStatus.BOOKED);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private static final String OWNER_APPROVALS = "/owner/dashboard?tab=approvals";
    private static final String OWNER_RENTS = "/owner/dashboard?tab=rents";
    private static final String OWNER_BOOKINGS = "/owner/dashboard?tab=bookings";
    private static final String CUSTOMER_BOOKINGS = "/my-rents";
    private static final String CUSTOMER_UPCOMING = "/my-rents?tab=upcoming";
    private static final String CUSTOMER_HISTORY = "/my-rents?tab=history";

    private static final String METHOD_UPI = "UPI";
    private static final String METHOD_CARD = "CARD";
    private static final String METHOD_CASH = "CASH";
    private static final String PAYMENT = "Payment";
    private static final String BOOKING = "Booking";

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

        String stay = String.format("%s x %d from %s", label(req.bookingType()), req.duration(),
                req.startDate().format(DATE));
        notificationService.notifyAllChannels(pg.getOwner(), "New booking request",
                String.format("%s (Phone: %s, Email: %s) requested %s - %s. Amount: Rs. %s",
                        customer.getName(), customer.getPhone(), customer.getEmail(), bedAt(booking), stay,
                        booking.getTotalAmount().toPlainString()),
                OWNER_APPROVALS);
        notificationService.emailConfirmation(customer, "Booking request sent",
                String.format("Your request for %s (%s, Rs. %s) was sent to the owner. The bed is held for you "
                                + "until the owner responds - we will email you as soon as it is approved.",
                        bedAt(booking), stay, booking.getTotalAmount().toPlainString()),
                CUSTOMER_BOOKINGS);
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
                .orElseThrow(() -> ApiException.notFound(BOOKING));
        if (b.getStatus() != Enums.BookingStatus.REQUESTED && b.getStatus() != Enums.BookingStatus.APPROVED) {
            throw ApiException.badRequest("Only pending or approved bookings can be cancelled");
        }
        if (b.getPayments().stream().anyMatch(p -> p.getStatus() != Enums.PaymentStatus.DUE)) {
            throw ApiException.badRequest("A payment is already submitted for this booking. Please contact the owner.");
        }
        b.setStatus(Enums.BookingStatus.CANCELLED);
        b.getPayments().clear();
        b.getBed().setStatus(Enums.BedStatus.AVAILABLE);
        notificationService.notifyAllChannels(b.getPg().getOwner(), "Booking cancelled",
                b.getCustomer().getName() + " cancelled the booking for " + bedAt(b) + ". The bed is available again.",
                OWNER_BOOKINGS);
        notificationService.emailConfirmation(b.getCustomer(), "Booking cancelled",
                "You cancelled your booking for " + bedAt(b) + ". The bed has been released and nothing is payable.",
                CUSTOMER_BOOKINGS);
        return DtoMapper.toBookingDto(b);
    }

    /**
     * history = paid rents (newest first); upcoming = only the <b>next</b> unpaid installment of each
     * active booking (later installments are shown once the current one is paid).
     */
    @Transactional(readOnly = true)
    public List<PaymentDto> myPayments(Long customerId, String scope) {
        if ("history".equalsIgnoreCase(scope)) {
            return paymentRepository.findForCustomer(customerId, EnumSet.of(Enums.PaymentStatus.PAID)).stream()
                    .map(DtoMapper::toPaymentDto)
                    .sorted(Comparator.comparing(PaymentDto::paidAt).reversed()).toList();
        }
        Map<Long, Payment> nextPerBooking = new LinkedHashMap<>();
        paymentRepository.findForCustomer(customerId, EnumSet.of(Enums.PaymentStatus.DUE, Enums.PaymentStatus.SUBMITTED))
                .stream()
                .filter(p -> ACTIVE.contains(p.getBooking().getStatus()))
                .sorted(Comparator.comparing((Payment p) -> p.getBooking().getId()).thenComparingInt(Payment::getInstallmentNo))
                .forEach(p -> nextPerBooking.putIfAbsent(p.getBooking().getId(), p));
        return nextPerBooking.values().stream().map(DtoMapper::toPaymentDto)
                .sorted(Comparator.comparing(PaymentDto::dueDate)).toList();
    }

    /** Online payment step 1: lock in the payment method and issue an order (plus a UPI deep link for UPI). */
    @Transactional
    public CheckoutDto checkout(Long customerId, Long paymentId, CheckoutRequest req) {
        Payment p = payableInstallment(customerId, paymentId);
        Booking b = p.getBooking();
        Pg pg = b.getPg();
        String orderId = "ORD" + p.getId() + "X" + Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT);
        p.setGatewayOrderId(orderId);
        String note = "Rent " + b.getBed().getRoom().getRoomNumber() + "-" + b.getBed().getBedLabel() + " inst "
                + p.getInstallmentNo();
        String upiUri = null;
        if (METHOD_UPI.equals(req.paymentMethod())) {
            if (isBlank(pg.getUpiId())) {
                throw ApiException.badRequest("This PG has not added a UPI ID yet. Please pay by card, net banking or cash.");
            }
            upiUri = "upi://pay?pa=" + enc(pg.getUpiId()) + "&pn=" + enc(pg.getName())
                    + "&am=" + p.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString()
                    + "&cu=INR&tn=" + enc(note) + "&tr=" + enc(orderId);
        }
        return new CheckoutDto(p.getId(), orderId, p.getAmount(), req.paymentMethod(), pg.getName(), pg.getUpiId(),
                note, upiUri);
    }

    /**
     * Online payment step 2: the customer is back from the UPI app / card / bank page. The payment is
     * marked PAID straight away (no owner confirmation needed), the bed becomes BOOKED and the owner is notified.
     */
    @Transactional
    public PaymentDto completeOnline(Long customerId, Long paymentId, CompletePaymentRequest req) {
        Payment p = payableInstallment(customerId, paymentId);
        if (p.getGatewayOrderId() == null || !p.getGatewayOrderId().equals(req.orderId())) {
            throw ApiException.badRequest("This payment session has expired. Please start the payment again.");
        }
        String details = onlinePaymentDetails(req);
        verifyWithGateway(p, req);
        p.setPaymentMethod(req.paymentMethod());
        p.setTransactionRef(METHOD_UPI.equals(req.paymentMethod()) && !isBlank(req.upiRef()) ? req.upiRef() : req.orderId());
        p.setPaymentDetails(details);
        p.setSubmittedAt(LocalDateTime.now());
        p.setOwnerNote(null);
        markPaid(p);

        Booking b = p.getBooking();
        notificationService.notifyAllChannels(b.getPg().getOwner(), "Payment received",
                String.format("%s (Phone: %s) paid Rs. %s online via %s (Ref: %s) for %s, installment %d/%d. "
                                + "The bed is booked.",
                        b.getCustomer().getName(), b.getCustomer().getPhone(), p.getAmount().toPlainString(), details,
                        p.getTransactionRef(), bedAt(b), p.getInstallmentNo(), b.getPayments().size()),
                OWNER_RENTS);
        notificationService.notifyAllChannels(b.getCustomer(), "Payment successful",
                String.format("We received Rs. %s for %s. Invoice %s is ready. Your bed is booked.%s",
                        p.getAmount().toPlainString(), bedAt(b), p.getInvoiceNumber(), nextDueText(b)),
                CUSTOMER_HISTORY);
        return DtoMapper.toPaymentDto(p);
    }

    /** Cash: the customer handed the rent to the owner and asks the owner to accept it. */
    @Transactional
    public PaymentDto requestCashConfirmation(Long customerId, Long paymentId, CashPaymentRequest req) {
        Payment p = payableInstallment(customerId, paymentId);
        Booking b = p.getBooking();
        String receivedBy = req.receivedBy().trim();
        String paidOn = req.paidOn().format(DATE);
        p.setStatus(Enums.PaymentStatus.SUBMITTED);
        p.setPaymentMethod(METHOD_CASH);
        p.setTransactionRef("CASH-" + p.getId());
        p.setGatewayOrderId(null);
        p.setPaymentDetails("Cash handed to " + receivedBy + " on " + paidOn
                + (isBlank(req.note()) ? "" : " - " + req.note().trim()));
        p.setSubmittedAt(LocalDateTime.now());
        p.setOwnerNote(null);
        notificationService.notifyAllChannels(b.getPg().getOwner(), "Cash payment - please confirm",
                String.format("%s (Phone: %s) says they paid Rs. %s in cash to %s on %s for %s, "
                                + "installment %d/%d. Accept it in Approvals to book the bed.",
                        b.getCustomer().getName(), b.getCustomer().getPhone(), p.getAmount().toPlainString(),
                        receivedBy, paidOn, bedAt(b), p.getInstallmentNo(), b.getPayments().size()),
                OWNER_APPROVALS);
        notificationService.emailConfirmation(b.getCustomer(), "Cash payment submitted",
                String.format("You reported a cash payment of Rs. %s to %s on %s for %s. The owner has been asked "
                                + "to confirm it - your invoice is issued as soon as they accept.",
                        p.getAmount().toPlainString(), receivedBy, paidOn, bedAt(b)),
                CUSTOMER_UPCOMING);
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
        Payment first = b.getPayments().get(0);
        String firstRent = "Rs. " + first.getAmount().toPlainString() + " (due " + first.getDueDate().format(DATE) + ")";
        notificationService.notifyAllChannels(b.getCustomer(), "Booking approved",
                String.format("Your request for %s is approved. Pay %s by UPI, card, net banking or cash to "
                        + "confirm your bed.", bedAt(b), firstRent),
                CUSTOMER_UPCOMING);
        notificationService.emailConfirmation(b.getPg().getOwner(), "Booking approved",
                String.format("You approved %s's request for %s. They have been asked to pay %s to confirm the bed.",
                        b.getCustomer().getName(), bedAt(b), firstRent),
                OWNER_BOOKINGS);
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
        notificationService.notifyAllChannels(b.getCustomer(), "Booking rejected",
                "Your request for " + bedAt(b) + " was rejected" + (isBlank(note) ? "." : ": " + note),
                CUSTOMER_BOOKINGS);
        notificationService.emailConfirmation(b.getPg().getOwner(), "Booking rejected",
                "You rejected " + b.getCustomer().getName() + "'s request for " + bedAt(b)
                        + ". The bed is available again.",
                OWNER_BOOKINGS);
        return DtoMapper.toBookingDto(b);
    }

    @Transactional(readOnly = true)
    public List<PaymentDto> ownerPayments(Long ownerId, Long pgId, Set<Enums.PaymentStatus> statuses) {
        Set<Enums.PaymentStatus> s = statuses == null || statuses.isEmpty()
                ? EnumSet.allOf(Enums.PaymentStatus.class) : statuses;
        return paymentRepository.findForOwner(ownerId, pgId, s, ACTIVE).stream().map(DtoMapper::toPaymentDto).toList();
    }

    /** Owner accepts a cash payment the customer reported: payment PAID and the bed is booked. */
    @Transactional
    public PaymentDto confirmPayment(Long ownerId, Long paymentId) {
        Payment p = ownedPayment(ownerId, paymentId);
        if (p.getStatus() != Enums.PaymentStatus.SUBMITTED) {
            throw ApiException.badRequest("Only cash payments waiting for your confirmation can be accepted");
        }
        markPaid(p);
        Booking b = p.getBooking();
        notificationService.notifyAllChannels(b.getCustomer(), "Cash payment accepted",
                String.format("%s accepted your cash payment of Rs. %s for %s. Invoice %s is ready. Your bed is booked.%s",
                        b.getPg().getOwner().getName(), p.getAmount().toPlainString(), bedAt(b),
                        p.getInvoiceNumber(), nextDueText(b)),
                CUSTOMER_HISTORY);
        notificationService.emailConfirmation(b.getPg().getOwner(), "Cash payment accepted",
                String.format("You accepted %s's cash payment of Rs. %s for %s. Invoice %s was issued and the bed "
                                + "is booked.",
                        b.getCustomer().getName(), p.getAmount().toPlainString(), bedAt(b), p.getInvoiceNumber()),
                OWNER_RENTS);
        return DtoMapper.toPaymentDto(p);
    }

    @Transactional
    public PaymentDto rejectPayment(Long ownerId, Long paymentId, String note) {
        Payment p = ownedPayment(ownerId, paymentId);
        if (p.getStatus() != Enums.PaymentStatus.SUBMITTED) {
            throw ApiException.badRequest("Only cash payments waiting for your confirmation can be rejected");
        }
        Booking b = p.getBooking();
        p.setStatus(Enums.PaymentStatus.DUE);
        p.setOwnerNote(isBlank(note) ? "Cash not received" : note);
        p.setSubmittedAt(null);
        p.setPaymentMethod(null);
        p.setPaymentDetails(null);
        p.setTransactionRef(null);
        notificationService.notifyAllChannels(b.getCustomer(), "Cash payment not accepted",
                "The owner did not accept your cash payment of Rs. " + p.getAmount().toPlainString() + " for "
                        + bedAt(b) + ": " + p.getOwnerNote() + ". Please pay again.",
                CUSTOMER_UPCOMING);
        notificationService.emailConfirmation(b.getPg().getOwner(), "Cash payment not accepted",
                "You marked " + b.getCustomer().getName() + "'s cash payment of Rs. " + p.getAmount().toPlainString()
                        + " for " + bedAt(b) + " as not received. The rent is due again.",
                OWNER_RENTS);
        return DtoMapper.toPaymentDto(p);
    }

    // ---------------------------------------------------------------- shared

    @Transactional(readOnly = true)
    public InvoiceDto invoice(Long userId, Long paymentId) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> ApiException.notFound(PAYMENT));
        Booking b = p.getBooking();
        boolean allowed = b.getCustomer().getId().equals(userId) || b.getPg().getOwner().getId().equals(userId);
        if (!allowed) {
            throw ApiException.notFound(PAYMENT);
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
                p.getTransactionRef(), p.getPaymentDetails());
    }

    // ---------------------------------------------------------------- helpers

    /** The installment must belong to the customer, be DUE, belong to an approved booking and be the next one. */
    private Payment payableInstallment(Long customerId, Long paymentId) {
        Payment p = paymentRepository.findById(paymentId)
                .filter(x -> x.getBooking().getCustomer().getId().equals(customerId))
                .orElseThrow(() -> ApiException.notFound(PAYMENT));
        Booking b = p.getBooking();
        if (!ACTIVE.contains(b.getStatus())) {
            throw ApiException.badRequest("Payment is allowed only after the owner approves the booking");
        }
        if (p.getStatus() == Enums.PaymentStatus.SUBMITTED) {
            throw ApiException.badRequest("Your cash payment is waiting for the owner to accept it");
        }
        if (p.getStatus() != Enums.PaymentStatus.DUE) {
            throw ApiException.badRequest("This rent is already " + p.getStatus().name().toLowerCase(Locale.ROOT));
        }
        boolean earlierUnpaid = b.getPayments().stream()
                .anyMatch(o -> o.getInstallmentNo() < p.getInstallmentNo() && o.getStatus() != Enums.PaymentStatus.PAID);
        if (earlierUnpaid) {
            throw ApiException.badRequest("Please pay earlier rent installments first");
        }
        return p;
    }

    /** Human readable description of an online payment; also validates the method specific fields. */
    private static String onlinePaymentDetails(CompletePaymentRequest req) {
        if (METHOD_UPI.equals(req.paymentMethod())) {
            if (isBlank(req.upiApp())) {
                throw ApiException.badRequest("Please choose the UPI app you paid with");
            }
            return upiAppName(req.upiApp()) + (isBlank(req.payerUpiId()) ? "" : " - " + req.payerUpiId().trim());
        }
        if (METHOD_CARD.equals(req.paymentMethod())) {
            if (isBlank(req.cardLast4()) || isBlank(req.cardHolder())) {
                throw ApiException.badRequest("Card holder name and card number are required");
            }
            return (isBlank(req.cardNetwork()) ? "Card" : req.cardNetwork().trim()) + " **** " + req.cardLast4()
                    + " (" + req.cardHolder().trim() + ")";
        }
        if (isBlank(req.bank())) {
            throw ApiException.badRequest("Please choose your bank");
        }
        return "Net banking - " + req.bank().trim();
    }

    /** PAID + invoice; the first payment turns the booking and the bed into BOOKED. */
    private static void markPaid(Payment p) {
        p.setStatus(Enums.PaymentStatus.PAID);
        p.setPaidAt(LocalDateTime.now());
        p.setInvoiceNumber(String.format("INV-%d-%06d", LocalDate.now().getYear(), p.getId()));
        p.setGatewayOrderId(null);
        Booking b = p.getBooking();
        if (b.getStatus() == Enums.BookingStatus.APPROVED) {
            b.setStatus(Enums.BookingStatus.BOOKED);
        }
        b.getBed().setStatus(Enums.BedStatus.BOOKED);
    }

    /**
     * Demo payment gateway. UPI intent payments made in a UPI app cannot be verified by the website itself;
     * in production replace this with the status / signature check of a payment gateway
     * (Razorpay, Cashfree, PhonePe PG ...) using {@code orderId}.
     */
    private static void verifyWithGateway(Payment p, CompletePaymentRequest req) {
        if (!req.orderId().equals(p.getGatewayOrderId())) {
            throw ApiException.badRequest("Payment could not be verified");
        }
    }

    /** "Room 101 Bed A at Sri Sai Comforts" */
    private static String bedAt(Booking b) {
        return "Room " + b.getBed().getRoom().getRoomNumber() + " Bed " + b.getBed().getBedLabel() + " at "
                + b.getPg().getName();
    }

    private static String nextDueText(Booking b) {
        return b.getPayments().stream().filter(x -> x.getStatus() == Enums.PaymentStatus.DUE)
                .min(Comparator.comparingInt(Payment::getInstallmentNo))
                .map(n -> " Next rent of Rs. " + n.getAmount().toPlainString() + " is due on " + n.getDueDate().format(DATE) + ".")
                .orElse("");
    }

    private static String upiAppName(String code) {
        return switch (code) {
            case "GPAY" -> "Google Pay";
            case "PHONEPE" -> "PhonePe";
            case "PAYTM" -> "Paytm";
            case "BHIM" -> "BHIM UPI";
            case "AMAZONPAY" -> "Amazon Pay";
            default -> METHOD_UPI;
        };
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private Booking ownedBooking(Long ownerId, Long bookingId) {
        return bookingRepository.findById(bookingId)
                .filter(b -> b.getPg().getOwner().getId().equals(ownerId))
                .orElseThrow(() -> ApiException.notFound(BOOKING));
    }

    private Payment ownedPayment(Long ownerId, Long paymentId) {
        return paymentRepository.findById(paymentId)
                .filter(p -> p.getBooking().getPg().getOwner().getId().equals(ownerId))
                .orElseThrow(() -> ApiException.notFound(PAYMENT));
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
                addPayment(b, i + 1, room.getMonthlyRent(), start.plusMonths(i), start.plusMonths(i + 1L).minusDays(1));
            }
        } else if (b.getBookingType() == Enums.BookingType.YEARLY) {
            BigDecimal yearly = room.getMonthlyRent().multiply(BigDecimal.valueOf(12));
            for (int i = 0; i < b.getDuration(); i++) {
                addPayment(b, i + 1, yearly, start.plusYears(i), start.plusYears(i + 1L).minusDays(1));
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

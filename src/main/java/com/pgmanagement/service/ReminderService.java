package com.pgmanagement.service;

import com.pgmanagement.config.AppProperties;
import com.pgmanagement.config.MessagingProperties;
import com.pgmanagement.dto.BookingDtos.ReminderResult;
import com.pgmanagement.entity.Booking;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.Payment;
import com.pgmanagement.entity.User;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rent due reminders. Once a day the next unpaid installment of every approved / booked stay is checked and
 * the customer is reminded on the website, by Email, SMS and WhatsApp a few days before the due date,
 * on the due date and periodically while it is overdue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    private static final Set<Enums.BookingStatus> ACTIVE = EnumSet.of(Enums.BookingStatus.APPROVED,
            Enums.BookingStatus.BOOKED);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final String UPCOMING_RENTS = "/my-rents?tab=upcoming";

    private final PaymentRepository paymentRepository;
    private final NotificationService notificationService;
    private final MessagingService messagingService;
    private final MessagingProperties props;
    private final AppProperties appProperties;

    @Scheduled(cron = "${app.messaging.reminders.cron:0 0 9 * * *}", zone = "${app.messaging.reminders.zone:Asia/Kolkata}")
    @Transactional
    public int sendDueReminders() {
        MessagingProperties.Reminders cfg = props.remindersOrDefault();
        if (!cfg.enabled()) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        int sent = 0;
        for (Payment p : nextUnpaidPerBooking()) {
            if (shouldRemind(p, today, cfg)) {
                remind(p, today);
                sent++;
            }
        }
        log.info("Rent reminders sent: {}", sent);
        return sent;
    }

    /** Event listeners are invoked through the proxy, so this runs in its own transaction. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onStartup() {
        if (props.remindersOrDefault().runOnStartup()) {
            sendDueReminders();
        }
    }

    /** Owner triggered reminder ("Send reminder" on the dashboard). */
    @Transactional
    public ReminderResult remindNow(Long ownerId, Long paymentId) {
        Payment p = paymentRepository.findById(paymentId)
                .filter(x -> x.getBooking().getPg().getOwner().getId().equals(ownerId))
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if (p.getStatus() != Enums.PaymentStatus.DUE || !ACTIVE.contains(p.getBooking().getStatus())) {
            throw ApiException.badRequest("Reminders can be sent only for unpaid rent of an active booking");
        }
        return new ReminderResult(remind(p, LocalDate.now()), LocalDate.now());
    }

    static boolean shouldRemind(Payment p, LocalDate today, MessagingProperties.Reminders cfg) {
        if (today.equals(p.getLastReminderOn())) {
            return false;
        }
        long daysLeft = ChronoUnit.DAYS.between(today, p.getDueDate());
        if (daysLeft >= 0) {
            return cfg.daysBefore() != null && cfg.daysBefore().contains((int) daysLeft);
        }
        return cfg.overdueEveryDays() > 0 && (-daysLeft) % cfg.overdueEveryDays() == 0;
    }

    /** "is due tomorrow, 20 Nov 2026" / "was due on 12 Nov 2026 and is overdue by 7 day(s)" */
    static String dueText(LocalDate dueDate, long daysLeft) {
        String date = dueDate.format(DATE);
        if (daysLeft > 1) {
            return "is due on " + date + " (in " + daysLeft + " days)";
        }
        if (daysLeft == 1) {
            return "is due tomorrow, " + date;
        }
        if (daysLeft == 0) {
            return "is due today, " + date;
        }
        return "was due on " + date + " and is overdue by " + (-daysLeft) + " day(s)";
    }

    private List<Payment> nextUnpaidPerBooking() {
        Map<Long, Payment> next = new LinkedHashMap<>();
        // includes SUBMITTED so that a pending cash confirmation stops reminders for later installments
        for (Payment p : paymentRepository.findByStatusForBookings(Enums.PaymentStatus.DUE, ACTIVE)) {
            next.putIfAbsent(p.getBooking().getId(), p);
        }
        for (Payment p : paymentRepository.findByStatusForBookings(Enums.PaymentStatus.SUBMITTED, ACTIVE)) {
            Payment due = next.get(p.getBooking().getId());
            if (due != null && p.getInstallmentNo() < due.getInstallmentNo()) {
                next.remove(p.getBooking().getId());
            }
        }
        return new ArrayList<>(next.values());
    }

    private List<String> remind(Payment p, LocalDate today) {
        Booking b = p.getBooking();
        User c = b.getCustomer();
        long daysLeft = ChronoUnit.DAYS.between(today, p.getDueDate());
        String title = daysLeft < 0 ? "Rent overdue" : "Rent due reminder";
        String message = String.format("Your next rent of Rs. %s for %s (Room %s, Bed %s, installment %d/%d) %s. "
                        + "Pay by UPI, card, net banking or cash from My Bookings & Rents.",
                p.getAmount().toPlainString(), b.getPg().getName(), b.getBed().getRoom().getRoomNumber(),
                b.getBed().getBedLabel(), p.getInstallmentNo(), b.getPayments().size(),
                dueText(p.getDueDate(), daysLeft));
        notificationService.notify(c, title, message, UPCOMING_RENTS);
        List<String> channels = new ArrayList<>(List.of("WEBSITE"));
        channels.addAll(messagingService.send(new MessagingService.Recipient(c.getName(), c.getEmail(), c.getPhone()),
                title, message, appProperties.siteBaseUrl() + UPCOMING_RENTS));
        p.setLastReminderOn(today);
        return channels;
    }
}

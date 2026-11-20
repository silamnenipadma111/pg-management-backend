package com.pgmanagement.dto;

import com.pgmanagement.entity.Enums;
import com.pgmanagement.validation.ValidationRules;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class BookingDtos {

    private BookingDtos() {
    }

    public record BookingRequest(
            @NotNull Long bedId,
            @NotNull Enums.BookingType bookingType,
            @Min(1) @Max(365) int duration,
            @NotNull @FutureOrPresent LocalDate startDate,
            Enums.PaymentPlan paymentPlan,
            @Size(max = 1000) String note) {
    }

    public record CustomerInfo(Long id, String name, String phone, String email, Enums.Gender gender) {
    }

    public record BookingDto(Long id, Long pgId, String pgName, String pgArea, String pgCity, Long bedId,
                             String roomNumber, int floorNumber, String bedLabel, int sharingType, boolean ac,
                             Enums.BookingType bookingType, Enums.PaymentPlan paymentPlan, int duration,
                             LocalDate startDate, LocalDate endDate, BigDecimal totalAmount,
                             Enums.BookingStatus status, Enums.BedStatus bedStatus, String customerNote,
                             String ownerNote, LocalDateTime createdAt, CustomerInfo customer,
                             int totalInstallments, int paidInstallments) {
    }

    public record PaymentDto(Long id, Long bookingId, int installmentNo, int totalInstallments, BigDecimal amount,
                             LocalDate dueDate, LocalDate periodStart, LocalDate periodEnd,
                             Enums.PaymentStatus status, boolean overdue, String paymentMethod,
                             String transactionRef, String paymentDetails, String ownerNote,
                             LocalDateTime submittedAt, LocalDateTime paidAt, String invoiceNumber, Long pgId,
                             String pgName, String roomNumber, String bedLabel, Enums.BookingType bookingType,
                             Enums.BookingStatus bookingStatus, CustomerInfo customer, LocalDate lastReminderOn) {
    }

    /** Step 1 of an online payment: choose the method, the server issues an order. */
    public record CheckoutRequest(
            @NotBlank @Pattern(regexp = "UPI|CARD|NET_BANKING", message = "must be UPI, CARD or NET_BANKING") String paymentMethod) {
    }

    /** Order issued for an online payment. upiUri is the standard UPI deep link (upi://pay?...) for UPI apps. */
    public record CheckoutDto(Long paymentId, String orderId, BigDecimal amount, String paymentMethod,
                              String payeeName, String payeeUpiId, String note, String upiUri) {
    }

    /**
     * Step 2 of an online payment: the customer returns from the UPI app / card / bank page.
     * Only non-sensitive details are accepted - never the full card number or CVV.
     */
    public record CompletePaymentRequest(
            @NotBlank @Size(max = 40) String orderId,
            @NotBlank @Pattern(regexp = "UPI|CARD|NET_BANKING", message = "must be UPI, CARD or NET_BANKING") String paymentMethod,
            @Pattern(regexp = "GPAY|PHONEPE|PAYTM|BHIM|AMAZONPAY|OTHER", message = "unknown UPI app") String upiApp,
            @Pattern(regexp = ValidationRules.UPI_ID, message = "must be a valid UPI ID") String payerUpiId,
            @Pattern(regexp = "^$|^[0-9]{12}$", message = "UPI reference (UTR) must be 12 digits") String upiRef,
            @Pattern(regexp = "^$|^[0-9]{4}$", message = "must be the last 4 digits") String cardLast4,
            @Size(max = 20) String cardNetwork,
            @Size(max = 100) String cardHolder,
            @Size(max = 60) String bank) {
    }

    /** Customer says the rent was handed over in cash; the owner must accept it. */
    public record CashPaymentRequest(
            @NotNull @PastOrPresent LocalDate paidOn,
            @NotBlank @Size(max = 100) String receivedBy,
            @Size(max = 300) String note) {
    }

    /** Channels the reminder went out on, e.g. [WEBSITE, EMAIL, SMS, WHATSAPP]. */
    public record ReminderResult(List<String> channels, LocalDate sentOn) {
    }

    public record NoteRequest(@Size(max = 1000) String note) {
    }

    public record InvoiceDto(String invoiceNumber, LocalDateTime issuedAt, String pgName, String pgAddress,
                             String pgContactPhone, String ownerName, String customerName, String customerEmail,
                             String customerPhone, String roomNumber, int floorNumber, String bedLabel,
                             int sharingType, Enums.BookingType bookingType, Enums.PaymentPlan paymentPlan,
                             int installmentNo, int totalInstallments, LocalDate periodStart,
                             LocalDate periodEnd, BigDecimal amount, String paymentMethod,
                             String transactionRef, String paymentDetails) {
    }
}

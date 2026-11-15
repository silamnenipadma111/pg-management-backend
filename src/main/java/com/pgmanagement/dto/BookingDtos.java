package com.pgmanagement.dto;

import com.pgmanagement.entity.Enums;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

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
                             String transactionRef, String ownerNote, LocalDateTime submittedAt,
                             LocalDateTime paidAt, String invoiceNumber, Long pgId, String pgName,
                             String roomNumber, String bedLabel, Enums.BookingType bookingType,
                             Enums.BookingStatus bookingStatus, CustomerInfo customer) {
    }

    public record PayRequest(
            @NotBlank @Pattern(regexp = "UPI|CARD|NET_BANKING|CASH", message = "must be UPI, CARD, NET_BANKING or CASH") String paymentMethod,
            @Size(max = 100) String transactionRef) {
    }

    public record NoteRequest(@Size(max = 1000) String note) {
    }

    public record InvoiceDto(String invoiceNumber, LocalDateTime issuedAt, String pgName, String pgAddress,
                             String pgContactPhone, String ownerName, String customerName, String customerEmail,
                             String customerPhone, String roomNumber, int floorNumber, String bedLabel,
                             int sharingType, Enums.BookingType bookingType, Enums.PaymentPlan paymentPlan,
                             int installmentNo, int totalInstallments, LocalDate periodStart,
                             LocalDate periodEnd, BigDecimal amount, String paymentMethod,
                             String transactionRef) {
    }
}

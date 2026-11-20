package com.pgmanagement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(nullable = false)
    private int installmentNo;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false)
    private LocalDate periodStart;

    @Column(nullable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.PaymentStatus status = Enums.PaymentStatus.DUE;

    @Column(length = 30)
    private String paymentMethod;

    @Column(length = 100)
    private String transactionRef;

    @Column(length = 500)
    private String ownerNote;

    private LocalDateTime submittedAt;

    private LocalDateTime paidAt;

    @Column(unique = true, length = 40)
    private String invoiceNumber;

    /** Order id issued at checkout; completing an online payment must reference it. */
    @Column(length = 40)
    private String gatewayOrderId;

    /** Human readable payment details, e.g. "Google Pay - name@okaxis" or "VISA **** 4242". */
    @Column(length = 200)
    private String paymentDetails;

    /** Date the last due-date reminder was sent for this installment (prevents duplicate reminders). */
    private LocalDate lastReminderOn;
}

package com.pgmanagement.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pg_id", nullable = false)
    private Pg pg;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bed_id", nullable = false)
    private Bed bed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Enums.BookingType bookingType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Enums.PaymentPlan paymentPlan = Enums.PaymentPlan.FULL;

    /** Number of days / weeks / months / years depending on bookingType */
    @Column(nullable = false)
    private int duration;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.BookingStatus status = Enums.BookingStatus.REQUESTED;

    @Column(length = 1000)
    private String customerNote;

    @Column(length = 1000)
    private String ownerNote;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("installmentNo ASC")
    private List<Payment> payments = new ArrayList<>();

    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}

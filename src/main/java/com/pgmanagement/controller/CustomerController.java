package com.pgmanagement.controller;

import com.pgmanagement.dto.BookingDtos.*;
import com.pgmanagement.dto.PgDtos.PreviousStayDto;
import com.pgmanagement.security.AuthUser;
import com.pgmanagement.service.BookingService;
import com.pgmanagement.service.PgService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class CustomerController {

    private final BookingService bookingService;
    private final PgService pgService;

    @PostMapping("/api/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingDto request(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody BookingRequest req) {
        return bookingService.requestBooking(user.id(), req);
    }

    @GetMapping("/api/bookings/my")
    public List<BookingDto> myBookings(@AuthenticationPrincipal AuthUser user) {
        return bookingService.myBookings(user.id());
    }

    /** PGs this customer stayed at before, for the "book again" suggestions on the home page. */
    @GetMapping("/api/bookings/suggestions")
    public List<PreviousStayDto> suggestions(@AuthenticationPrincipal AuthUser user) {
        return pgService.previousStays(user.id());
    }

    @PostMapping("/api/bookings/{id}/cancel")
    public BookingDto cancel(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        return bookingService.cancel(user.id(), id);
    }

    @GetMapping("/api/payments/my")
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<PaymentDto> myPayments(@AuthenticationPrincipal AuthUser user,
                                       @RequestParam(defaultValue = "upcoming") String scope) {
        return bookingService.myPayments(user.id(), scope);
    }

    /** Online payment step 1 (UPI / card / net banking): returns an order and, for UPI, the upi:// deep link. */
    @PostMapping("/api/payments/{id}/checkout")
    @PreAuthorize("hasRole('CUSTOMER')")
    public CheckoutDto checkout(@AuthenticationPrincipal AuthUser user, @PathVariable Long id,
                                @Valid @RequestBody CheckoutRequest req) {
        return bookingService.checkout(user.id(), id, req);
    }

    /** Online payment step 2: payment is PAID immediately, bed BOOKED, owner notified. */
    @PostMapping("/api/payments/{id}/complete")
    @PreAuthorize("hasRole('CUSTOMER')")
    public PaymentDto complete(@AuthenticationPrincipal AuthUser user, @PathVariable Long id,
                               @Valid @RequestBody CompletePaymentRequest req) {
        return bookingService.completeOnline(user.id(), id, req);
    }

    /** Cash handed to the owner: waits for the owner to accept it. */
    @PostMapping("/api/payments/{id}/cash")
    @PreAuthorize("hasRole('CUSTOMER')")
    public PaymentDto cash(@AuthenticationPrincipal AuthUser user, @PathVariable Long id,
                           @Valid @RequestBody CashPaymentRequest req) {
        return bookingService.requestCashConfirmation(user.id(), id, req);
    }

    @GetMapping("/api/payments/{id}/invoice")
    public InvoiceDto invoice(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        return bookingService.invoice(user.id(), id);
    }
}

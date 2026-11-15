package com.pgmanagement.controller;

import com.pgmanagement.dto.BookingDtos.*;
import com.pgmanagement.security.AuthUser;
import com.pgmanagement.service.BookingService;
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

    @PostMapping("/api/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingDto request(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody BookingRequest req) {
        return bookingService.requestBooking(user.id(), req);
    }

    @GetMapping("/api/bookings/my")
    public List<BookingDto> myBookings(@AuthenticationPrincipal AuthUser user) {
        return bookingService.myBookings(user.id());
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

    @PostMapping("/api/payments/{id}/pay")
    @PreAuthorize("hasRole('CUSTOMER')")
    public PaymentDto pay(@AuthenticationPrincipal AuthUser user, @PathVariable Long id,
                          @Valid @RequestBody PayRequest req) {
        return bookingService.pay(user.id(), id, req);
    }

    @GetMapping("/api/payments/{id}/invoice")
    public InvoiceDto invoice(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        return bookingService.invoice(user.id(), id);
    }
}

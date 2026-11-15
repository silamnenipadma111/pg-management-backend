package com.pgmanagement.controller;

import com.pgmanagement.dto.BookingDtos.BookingDto;
import com.pgmanagement.dto.BookingDtos.NoteRequest;
import com.pgmanagement.dto.BookingDtos.PaymentDto;
import com.pgmanagement.dto.DashboardDtos.OwnerDashboardDto;
import com.pgmanagement.dto.PgDtos.BedStatusRequest;
import com.pgmanagement.dto.PgDtos.PgDetailDto;
import com.pgmanagement.dto.PgDtos.PgRequest;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.repository.UserRepository;
import com.pgmanagement.security.AuthUser;
import com.pgmanagement.service.BookingService;
import com.pgmanagement.service.DashboardService;
import com.pgmanagement.service.PgService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/owner")
@RequiredArgsConstructor
public class OwnerController {

    private final PgService pgService;
    private final BookingService bookingService;
    private final DashboardService dashboardService;
    private final UserRepository userRepository;

    @GetMapping("/pgs")
    public List<PgDetailDto> myPgs(@AuthenticationPrincipal AuthUser user) {
        return pgService.ownerPgs(user.id());
    }

    @GetMapping("/pgs/{id}")
    public PgDetailDto myPg(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        return pgService.ownerPg(user.id(), id);
    }

    @PostMapping("/pgs")
    @ResponseStatus(HttpStatus.CREATED)
    public PgDetailDto register(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody PgRequest req) {
        var owner = userRepository.findById(user.id()).orElseThrow(() -> ApiException.notFound("User"));
        return pgService.create(owner, req, Enums.RegisteredVia.OWNER);
    }

    @PutMapping("/pgs/{id}")
    public PgDetailDto update(@AuthenticationPrincipal AuthUser user, @PathVariable Long id,
                              @Valid @RequestBody PgRequest req) {
        return pgService.update(user.id(), id, req);
    }

    @PatchMapping("/beds/{bedId}/status")
    public PgDetailDto bedStatus(@AuthenticationPrincipal AuthUser user, @PathVariable Long bedId,
                                 @Valid @RequestBody BedStatusRequest req) {
        return pgService.updateBedStatus(user.id(), bedId, req.status());
    }

    @GetMapping("/dashboard")
    public OwnerDashboardDto dashboard(@AuthenticationPrincipal AuthUser user) {
        return dashboardService.ownerDashboard(user.id());
    }

    @GetMapping("/bookings")
    public List<BookingDto> bookings(@AuthenticationPrincipal AuthUser user,
                                     @RequestParam(required = false) Long pgId,
                                     @RequestParam(required = false) Set<Enums.BookingStatus> status) {
        return bookingService.ownerBookings(user.id(), pgId, status);
    }

    @PostMapping("/bookings/{id}/approve")
    public BookingDto approve(@AuthenticationPrincipal AuthUser user, @PathVariable Long id,
                              @Valid @RequestBody(required = false) NoteRequest req) {
        return bookingService.approve(user.id(), id, req == null ? null : req.note());
    }

    @PostMapping("/bookings/{id}/reject")
    public BookingDto reject(@AuthenticationPrincipal AuthUser user, @PathVariable Long id,
                             @Valid @RequestBody(required = false) NoteRequest req) {
        return bookingService.reject(user.id(), id, req == null ? null : req.note());
    }

    @GetMapping("/payments")
    public List<PaymentDto> payments(@AuthenticationPrincipal AuthUser user,
                                     @RequestParam(required = false) Long pgId,
                                     @RequestParam(required = false) Set<Enums.PaymentStatus> status) {
        return bookingService.ownerPayments(user.id(), pgId, status);
    }

    @PostMapping("/payments/{id}/confirm")
    public PaymentDto confirmPayment(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        return bookingService.confirmPayment(user.id(), id);
    }

    @PostMapping("/payments/{id}/reject")
    public PaymentDto rejectPayment(@AuthenticationPrincipal AuthUser user, @PathVariable Long id,
                                    @Valid @RequestBody(required = false) NoteRequest req) {
        return bookingService.rejectPayment(user.id(), id, req == null ? null : req.note());
    }
}

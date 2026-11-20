package com.pgmanagement.config;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.pgmanagement.dto.BookingDtos.BookingRequest;
import com.pgmanagement.dto.BookingDtos.CashPaymentRequest;
import com.pgmanagement.dto.BookingDtos.CheckoutRequest;
import com.pgmanagement.dto.BookingDtos.CompletePaymentRequest;
import com.pgmanagement.dto.PgDtos.*;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.Pg;
import com.pgmanagement.entity.Room;
import com.pgmanagement.entity.User;
import com.pgmanagement.repository.BookingRepository;
import com.pgmanagement.repository.PgRepository;
import com.pgmanagement.repository.UserRepository;
import com.pgmanagement.service.AuthService;
import com.pgmanagement.service.BookingService;
import com.pgmanagement.service.DemoAccounts;
import com.pgmanagement.service.PgService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads demo data from classpath:mock-data/*.json when the database is empty (app.seed-mock-data=true).
 * Data is inserted through the regular services so every business rule applies.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockDataSeeder implements ApplicationRunner {

    private final AppProperties appProperties;
    private final UserRepository userRepository;
    private final PgRepository pgRepository;
    private final BookingRepository bookingRepository;
    private final AuthService authService;
    private final PgService pgService;
    private final BookingService bookingService;
    private final DemoAccounts demoAccounts;

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

    record SeedUser(String name, String email, String phone, String password, Enums.UserRole role,
                    Enums.Gender gender) {
    }

    record SeedRoom(String roomNumber, int sharingType, boolean ac, boolean attachedBathroom, BigDecimal dailyRent,
                    BigDecimal weeklyRent, BigDecimal monthlyRent, List<String> unavailableBeds) {
    }

    record SeedFloor(int floorNumber, List<SeedRoom> rooms) {
    }

    record SeedPg(String ownerEmail, String name, String description, String addressLine, String area, String city,
                  String state, String pincode, Double latitude, Double longitude,
                  Enums.GenderPreference genderPreference, List<String> facilities, String contactPhone, String upiId,
                  String houseRules, List<MediaDto> media, List<SeedFloor> floors) {
    }

    record SeedBooking(String customerEmail, String pgName, String roomNumber, String bedLabel,
                       Enums.BookingType bookingType, Enums.PaymentPlan paymentPlan, int duration,
                       int startOffsetDays, String stage, int paidInstallments) {
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        if (!appProperties.seedMockData()) {
            return;
        }
        List<SeedUser> users = read("users.json", new TypeReference<>() { });
        // demo accounts use made-up addresses and phone numbers: never send them real emails or SMS
        demoAccounts.register(users.stream().map(SeedUser::email).toList());
        if (userRepository.count() > 0) {
            return;
        }
        for (SeedUser u : users) {
            authService.createUser(u.name(), u.email(), u.phone(), u.password(), u.role(), u.gender());
        }

        List<SeedPg> pgs = read("pgs.json", new TypeReference<>() { });
        for (SeedPg p : pgs) {
            User owner = userRepository.findByEmailIgnoreCaseAndRole(p.ownerEmail(), Enums.UserRole.OWNER).orElseThrow();
            pgService.create(owner, toRequest(p), Enums.RegisteredVia.OWNER);
        }

        List<SeedBooking> bookings = read("bookings.json", new TypeReference<>() { });
        for (SeedBooking b : bookings) {
            seedBooking(b);
        }
        log.info("Seeded mock data: {} users, {} PGs, {} bookings", users.size(), pgs.size(), bookings.size());
    }

    private void seedBooking(SeedBooking b) {
        User customer = userRepository.findByEmailIgnoreCaseAndRole(b.customerEmail(), Enums.UserRole.CUSTOMER)
                .orElseThrow();
        Pg pg = pgRepository.findByNameIgnoreCase(b.pgName()).orElseThrow();
        Room room = pg.getRooms().stream().filter(r -> r.getRoomNumber().equals(b.roomNumber())).findFirst()
                .orElseThrow();
        Long bedId = room.getBeds().stream().filter(x -> x.getBedLabel().equals(b.bedLabel())).findFirst()
                .orElseThrow().getId();
        var booking = bookingService.requestBooking(customer.getId(), new BookingRequest(bedId, b.bookingType(),
                b.duration(), LocalDate.now().plusDays(b.startOffsetDays()), b.paymentPlan(), "Seeded booking"));
        if ("REQUESTED".equals(b.stage())) {
            return;
        }
        Long ownerId = pg.getOwner().getId();
        bookingService.approve(ownerId, booking.id(), "Welcome!");
        bookingRepository.flush(); // assigns ids to the generated installments
        List<Long> payments = bookingRepository.findById(booking.id()).orElseThrow().getPayments().stream()
                .map(com.pgmanagement.entity.Payment::getId).toList();
        if ("PAYMENT_SUBMITTED".equals(b.stage()) || "CASH_SUBMITTED".equals(b.stage())) {
            bookingService.requestCashConfirmation(customer.getId(), payments.get(0),
                    new CashPaymentRequest(LocalDate.now(), pg.getOwner().getName(), "Paid at the PG office"));
        } else if ("BOOKED".equals(b.stage())) {
            for (int i = 0; i < Math.max(1, b.paidInstallments()); i++) {
                Long paymentId = payments.get(i);
                if (i % 2 == 0) {
                    var order = bookingService.checkout(customer.getId(), paymentId, new CheckoutRequest("CARD"));
                    bookingService.completeOnline(customer.getId(), paymentId, new CompletePaymentRequest(
                            order.orderId(), "CARD", null, null, null, "4242", "VISA", customer.getName(), null));
                } else {
                    bookingService.requestCashConfirmation(customer.getId(), paymentId,
                            new CashPaymentRequest(LocalDate.now(), pg.getOwner().getName(), null));
                    bookingService.confirmPayment(ownerId, paymentId);
                }
            }
        }
    }

    private static PgRequest toRequest(SeedPg p) {
        List<RoomRequest> rooms = new ArrayList<>();
        for (SeedFloor f : p.floors()) {
            for (SeedRoom r : f.rooms()) {
                List<BedRequest> beds = r.unavailableBeds() == null ? null
                        : r.unavailableBeds().stream().map(l -> new BedRequest(l, false)).toList();
                rooms.add(new RoomRequest(f.floorNumber(), r.roomNumber(), r.sharingType(), r.ac(),
                        r.attachedBathroom(), r.dailyRent(), r.weeklyRent(), r.monthlyRent(), beds));
            }
        }
        return new PgRequest(p.name(), p.description(), p.addressLine(), p.area(), p.city(), p.state(), p.pincode(),
                p.latitude(), p.longitude(), p.genderPreference(), p.facilities(), p.contactPhone(), p.upiId(), p.houseRules(),
                p.media(), rooms);
    }

    private <T> T read(String file, TypeReference<T> type) throws IOException {
        try (InputStream in = new ClassPathResource("mock-data/" + file).getInputStream()) {
            return mapper.readValue(in, type);
        }
    }
}

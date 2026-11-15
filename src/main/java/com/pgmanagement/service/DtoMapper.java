package com.pgmanagement.service;

import com.pgmanagement.dto.AuthDtos.UserDto;
import com.pgmanagement.dto.BookingDtos.BookingDto;
import com.pgmanagement.dto.BookingDtos.CustomerInfo;
import com.pgmanagement.dto.BookingDtos.PaymentDto;
import com.pgmanagement.dto.CommonDtos.NotificationDto;
import com.pgmanagement.dto.CommonDtos.SupportRequestDto;
import com.pgmanagement.dto.PgDtos.*;
import com.pgmanagement.entity.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public final class DtoMapper {

    private DtoMapper() {
    }

    public static UserDto toUserDto(User u) {
        return new UserDto(u.getId(), u.getName(), u.getEmail(), u.getPhone(), u.getRole(), u.getGender());
    }

    public static CustomerInfo toCustomerInfo(User u) {
        return new CustomerInfo(u.getId(), u.getName(), u.getPhone(), u.getEmail(), u.getGender());
    }

    public static List<String> splitFacilities(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public static String joinFacilities(List<String> facilities) {
        return facilities.stream().map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .distinct().collect(Collectors.joining(","));
    }

    public static RoomDto toRoomDto(Room r) {
        List<BedDto> beds = r.getBeds().stream()
                .map(b -> new BedDto(b.getId(), b.getBedLabel(), b.getStatus())).toList();
        int available = (int) r.getBeds().stream().filter(b -> b.getStatus() == Enums.BedStatus.AVAILABLE).count();
        return new RoomDto(r.getId(), r.getFloorNumber(), r.getRoomNumber(), r.getSharingType(), r.isAc(),
                r.isAttachedBathroom(), r.getDailyRent(), r.getWeeklyRent(), r.getMonthlyRent(), available, beds);
    }

    public static PgSummaryDto toSummary(Pg pg, long availableBeds, long totalBeds, Double distanceKm) {
        List<String> images = pg.getMedia().stream().filter(m -> m.getMediaType() == Enums.MediaType.IMAGE)
                .map(PgMedia::getUrl).toList();
        return new PgSummaryDto(pg.getId(), pg.getName(), pg.getAddressLine(), pg.getArea(), pg.getCity(),
                pg.getGenderPreference(), splitFacilities(pg.getFacilities()), images, minRent(pg), maxRent(pg),
                sharingTypes(pg), pg.getRooms().stream().anyMatch(Room::isAc), availableBeds, totalBeds,
                distanceKm == null ? null : Math.round(distanceKm * 10) / 10.0, pg.getLatitude(), pg.getLongitude());
    }

    public static PgDetailDto toDetail(Pg pg) {
        Map<Integer, List<RoomDto>> byFloor = new TreeMap<>();
        long total = 0;
        long available = 0;
        for (Room room : pg.getRooms()) {
            RoomDto dto = toRoomDto(room);
            byFloor.computeIfAbsent(room.getFloorNumber(), k -> new ArrayList<>()).add(dto);
            total += room.getBeds().size();
            available += dto.availableBeds();
        }
        List<FloorDto> floors = byFloor.entrySet().stream()
                .map(e -> new FloorDto(e.getKey(), e.getValue().stream()
                        .sorted(Comparator.comparing(RoomDto::roomNumber)).toList()))
                .toList();
        List<MediaDto> media = pg.getMedia().stream().map(m -> new MediaDto(m.getMediaType(), m.getUrl())).toList();
        return new PgDetailDto(pg.getId(), pg.getName(), pg.getDescription(), pg.getAddressLine(), pg.getArea(),
                pg.getCity(), pg.getState(), pg.getPincode(), pg.getLatitude(), pg.getLongitude(),
                pg.getGenderPreference(), splitFacilities(pg.getFacilities()), pg.getContactPhone(),
                pg.getHouseRules(), pg.getOwner().getName(), pg.getRegisteredVia(), media, minRent(pg), maxRent(pg),
                sharingTypes(pg), available, total, floors);
    }

    public static BigDecimal minRent(Pg pg) {
        return pg.getRooms().stream().map(Room::getMonthlyRent).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    public static BigDecimal maxRent(Pg pg) {
        return pg.getRooms().stream().map(Room::getMonthlyRent).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    public static List<Integer> sharingTypes(Pg pg) {
        return pg.getRooms().stream().map(Room::getSharingType).distinct().sorted().toList();
    }

    public static BookingDto toBookingDto(Booking b) {
        Bed bed = b.getBed();
        Room room = bed.getRoom();
        int paid = (int) b.getPayments().stream().filter(p -> p.getStatus() == Enums.PaymentStatus.PAID).count();
        return new BookingDto(b.getId(), b.getPg().getId(), b.getPg().getName(), b.getPg().getArea(),
                b.getPg().getCity(), bed.getId(), room.getRoomNumber(), room.getFloorNumber(), bed.getBedLabel(),
                room.getSharingType(), room.isAc(), b.getBookingType(), b.getPaymentPlan(), b.getDuration(),
                b.getStartDate(), b.getEndDate(), b.getTotalAmount(), b.getStatus(), bed.getStatus(),
                b.getCustomerNote(), b.getOwnerNote(), b.getCreatedAt(), toCustomerInfo(b.getCustomer()),
                b.getPayments().size(), paid);
    }

    public static PaymentDto toPaymentDto(Payment p) {
        Booking b = p.getBooking();
        Bed bed = b.getBed();
        boolean overdue = p.getStatus() == Enums.PaymentStatus.DUE && p.getDueDate().isBefore(LocalDate.now());
        return new PaymentDto(p.getId(), b.getId(), p.getInstallmentNo(), b.getPayments().size(), p.getAmount(),
                p.getDueDate(), p.getPeriodStart(), p.getPeriodEnd(), p.getStatus(), overdue, p.getPaymentMethod(),
                p.getTransactionRef(), p.getOwnerNote(), p.getSubmittedAt(), p.getPaidAt(), p.getInvoiceNumber(),
                b.getPg().getId(), b.getPg().getName(), bed.getRoom().getRoomNumber(), bed.getBedLabel(),
                b.getBookingType(), b.getStatus(), toCustomerInfo(b.getCustomer()));
    }

    public static NotificationDto toNotificationDto(Notification n) {
        return new NotificationDto(n.getId(), n.getTitle(), n.getMessage(), n.getLink(), n.isRead(), n.getCreatedAt());
    }

    public static SupportRequestDto toSupportDto(SupportRequest s) {
        return new SupportRequestDto(s.getId(), s.getName(), s.getPhone(), s.getEmail(), s.getRequestType(),
                s.getMessage(), s.getStatus(), s.getCreatedAt());
    }
}

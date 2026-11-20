package com.pgmanagement.support;

import com.pgmanagement.entity.Bed;
import com.pgmanagement.entity.Booking;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.Payment;
import com.pgmanagement.entity.Pg;
import com.pgmanagement.entity.PgMedia;
import com.pgmanagement.entity.Room;
import com.pgmanagement.entity.User;

import java.math.BigDecimal;
import java.time.LocalDate;

/** In-memory entities for unit tests. Children are attached to their parents like JPA would return them. */
public final class Fixtures {

    public static final String PHONE = "9876543210";

    private Fixtures() {
    }

    public static User user(long id, Enums.UserRole role, String name, String email) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setName(name);
        user.setEmail(email);
        user.setPhone(PHONE);
        user.setPasswordHash("not-a-real-hash");
        return user;
    }

    public static User owner(long id) {
        return user(id, Enums.UserRole.OWNER, "Ravi Kumar", "ravi" + id + "@example.com");
    }

    public static User customer(long id) {
        return user(id, Enums.UserRole.CUSTOMER, "Anil Varma", "anil" + id + "@example.com");
    }

    public static Pg pg(long id, User owner, String name, String area, String city) {
        Pg pg = new Pg();
        pg.setId(id);
        pg.setOwner(owner);
        pg.setName(name);
        pg.setAddressLine("12 Main Road");
        pg.setArea(area);
        pg.setCity(city);
        pg.setState("Telangana");
        pg.setPincode("500081");
        pg.setContactPhone(PHONE);
        pg.setFacilities("WIFI,FOOD");
        return pg;
    }

    public static PgMedia media(Pg pg, Enums.MediaType type, String url) {
        PgMedia media = new PgMedia();
        media.setPg(pg);
        media.setMediaType(type);
        media.setUrl(url);
        media.setSortOrder(pg.getMedia().size());
        pg.getMedia().add(media);
        return media;
    }

    /** Daily rent = monthly / 20, weekly rent = daily x 7. */
    public static Room room(long id, Pg pg, String number, int sharing, boolean ac, int monthlyRent) {
        Room room = new Room();
        room.setId(id);
        room.setPg(pg);
        room.setFloorNumber(1);
        room.setRoomNumber(number);
        room.setSharingType(sharing);
        room.setAc(ac);
        room.setMonthlyRent(BigDecimal.valueOf(monthlyRent));
        room.setDailyRent(BigDecimal.valueOf(monthlyRent / 20));
        room.setWeeklyRent(BigDecimal.valueOf(monthlyRent / 20 * 7L));
        pg.getRooms().add(room);
        return room;
    }

    public static Bed bed(long id, Room room, String label, Enums.BedStatus status) {
        Bed bed = new Bed();
        bed.setId(id);
        bed.setRoom(room);
        bed.setBedLabel(label);
        bed.setStatus(status);
        room.getBeds().add(bed);
        return bed;
    }

    public static Booking booking(long id, User customer, Bed bed, Enums.BookingType type, int duration,
                                  LocalDate start, Enums.BookingStatus status) {
        Booking booking = new Booking();
        booking.setId(id);
        booking.setCustomer(customer);
        booking.setBed(bed);
        booking.setPg(bed.getRoom().getPg());
        booking.setBookingType(type);
        booking.setPaymentPlan(type == Enums.BookingType.MONTHLY || type == Enums.BookingType.YEARLY
                ? Enums.PaymentPlan.MONTHLY : Enums.PaymentPlan.FULL);
        booking.setDuration(duration);
        booking.setStartDate(start);
        booking.setEndDate(start.plusMonths(duration));
        booking.setTotalAmount(bed.getRoom().getMonthlyRent().multiply(BigDecimal.valueOf(duration)));
        booking.setStatus(status);
        return booking;
    }

    public static Payment payment(long id, Booking booking, int installmentNo, int amount, LocalDate dueDate,
                                  Enums.PaymentStatus status) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setBooking(booking);
        payment.setInstallmentNo(installmentNo);
        payment.setAmount(BigDecimal.valueOf(amount));
        payment.setDueDate(dueDate);
        payment.setPeriodStart(dueDate);
        payment.setPeriodEnd(dueDate.plusMonths(1).minusDays(1));
        payment.setStatus(status);
        booking.getPayments().add(payment);
        return payment;
    }
}

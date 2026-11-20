package com.pgmanagement.service;

import com.pgmanagement.dto.BookingDtos.BookingDto;
import com.pgmanagement.dto.BookingDtos.PaymentDto;
import com.pgmanagement.dto.PgDtos.PgDetailDto;
import com.pgmanagement.dto.PgDtos.PgSummaryDto;
import com.pgmanagement.entity.Bed;
import com.pgmanagement.entity.Booking;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.Payment;
import com.pgmanagement.entity.Pg;
import com.pgmanagement.entity.Room;
import com.pgmanagement.entity.User;
import com.pgmanagement.support.Fixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DtoMapperTest {

    private final User owner = Fixtures.owner(1L);
    private final User customer = Fixtures.customer(2L);

    @Test
    void facilitiesAreSplitAndJoinedCleanly() {
        assertThat(DtoMapper.splitFacilities(null)).isEmpty();
        assertThat(DtoMapper.splitFacilities("  ")).isEmpty();
        assertThat(DtoMapper.splitFacilities("WIFI, FOOD,,AC ")).containsExactly("WIFI", "FOOD", "AC");
        assertThat(DtoMapper.joinFacilities(Arrays.asList(" wifi", "FOOD", "Wifi", " "))).isEqualTo("WIFI,FOOD");
    }

    @Test
    void userDtoCarriesTheVerificationFlag() {
        customer.setEmailVerified(false);

        assertThat(DtoMapper.toUserDto(customer).emailVerified()).isFalse();
        assertThat(DtoMapper.toUserDto(owner).emailVerified()).isTrue();
        assertThat(DtoMapper.toCustomerInfo(customer).email()).isEqualTo(customer.getEmail());
    }

    @Test
    void summaryAggregatesRentSharingImagesAndDistance() {
        Pg pg = pgWithRooms();
        Fixtures.media(pg, Enums.MediaType.IMAGE, "https://img/1.jpg");
        Fixtures.media(pg, Enums.MediaType.VIDEO, "https://vid/1.mp4");

        PgSummaryDto summary = DtoMapper.toSummary(pg, 3, 5, 2.449);

        assertThat(summary.images()).containsExactly("https://img/1.jpg");
        assertThat(summary.minRent()).isEqualByComparingTo("8000");
        assertThat(summary.maxRent()).isEqualByComparingTo("12000");
        assertThat(summary.sharingTypes()).containsExactly(1, 2);
        assertThat(summary.hasAc()).isTrue();
        assertThat(summary.availableBeds()).isEqualTo(3);
        assertThat(summary.totalBeds()).isEqualTo(5);
        assertThat(summary.distanceKm()).isEqualTo(2.4);
        assertThat(summary.facilities()).containsExactly("WIFI", "FOOD");
        assertThat(DtoMapper.toSummary(pg, 0, 0, null).distanceKm()).isNull();
    }

    @Test
    void rentOfAPgWithoutRoomsIsZero() {
        Pg empty = Fixtures.pg(9L, owner, "Empty", "Area", "City");

        assertThat(DtoMapper.minRent(empty)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(DtoMapper.maxRent(empty)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void detailGroupsRoomsByFloorAndCountsBeds() {
        Pg pg = pgWithRooms();
        pg.getRooms().get(1).setFloorNumber(2);

        PgDetailDto detail = DtoMapper.toDetail(pg);

        assertThat(detail.floors()).extracting("floorNumber").containsExactly(1, 2);
        assertThat(detail.totalBeds()).isEqualTo(3);
        assertThat(detail.availableBeds()).isEqualTo(2);
        assertThat(detail.ownerName()).isEqualTo(owner.getName());
    }

    @Test
    void bookingAndPaymentDtosReportProgressAndOverdue() {
        Pg pg = pgWithRooms();
        Bed bed = pg.getRooms().get(0).getBeds().get(0);
        Booking booking = Fixtures.booking(5L, customer, bed, Enums.BookingType.MONTHLY, 2, LocalDate.now().minusMonths(1),
                Enums.BookingStatus.BOOKED);
        Payment paid = Fixtures.payment(1L, booking, 1, 8000, LocalDate.now().minusMonths(1), Enums.PaymentStatus.PAID);
        Payment late = Fixtures.payment(2L, booking, 2, 8000, LocalDate.now().minusDays(1), Enums.PaymentStatus.DUE);

        BookingDto bookingDto = DtoMapper.toBookingDto(booking);
        PaymentDto lateDto = DtoMapper.toPaymentDto(late);

        assertThat(bookingDto.totalInstallments()).isEqualTo(2);
        assertThat(bookingDto.paidInstallments()).isEqualTo(1);
        assertThat(lateDto.overdue()).isTrue();
        assertThat(DtoMapper.toPaymentDto(paid).overdue()).isFalse();
        assertThat(lateDto.customer().name()).isEqualTo(customer.getName());
    }

    private Pg pgWithRooms() {
        Pg pg = Fixtures.pg(1L, owner, "Green Nest", "Madhapur", "Hyderabad");
        Room shared = Fixtures.room(1L, pg, "101", 2, false, 8000);
        Fixtures.bed(1L, shared, "A", Enums.BedStatus.AVAILABLE);
        Fixtures.bed(2L, shared, "B", Enums.BedStatus.BOOKED);
        Room single = Fixtures.room(2L, pg, "102", 1, true, 12000);
        Fixtures.bed(3L, single, "A", Enums.BedStatus.AVAILABLE);
        assertThat(List.of(shared, single)).hasSize(2);
        return pg;
    }
}

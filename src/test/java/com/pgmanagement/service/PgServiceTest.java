package com.pgmanagement.service;

import com.pgmanagement.dto.PgDtos.BedRequest;
import com.pgmanagement.dto.PgDtos.MediaDto;
import com.pgmanagement.dto.PgDtos.PgDetailDto;
import com.pgmanagement.dto.PgDtos.PgRequest;
import com.pgmanagement.dto.PgDtos.PgSummaryDto;
import com.pgmanagement.dto.PgDtos.PreviousStayDto;
import com.pgmanagement.dto.PgDtos.RoomRequest;
import com.pgmanagement.dto.PgDtos.SearchCriteria;
import com.pgmanagement.entity.Bed;
import com.pgmanagement.entity.Booking;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.Pg;
import com.pgmanagement.entity.Room;
import com.pgmanagement.entity.User;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.repository.BedRepository;
import com.pgmanagement.repository.BookingRepository;
import com.pgmanagement.repository.PgRepository;
import com.pgmanagement.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PgServiceTest {

    private static final String HYDERABAD = "Hyderabad";
    private static final String STATUS = "status";

    @Mock
    private PgRepository pgRepository;
    @Mock
    private BedRepository bedRepository;
    @Mock
    private BookingRepository bookingRepository;
    @InjectMocks
    private PgService service;

    private final User owner = Fixtures.owner(1L);
    private Pg madhapur;
    private Pg koramangala;
    private Pg noCoordinates;

    @BeforeEach
    void setUp() {
        madhapur = Fixtures.pg(1L, owner, "Sri Sai Men's PG", "Madhapur", HYDERABAD);
        madhapur.setLatitude(17.4486);
        madhapur.setLongitude(78.3908);
        madhapur.setGenderPreference(Enums.GenderPreference.MALE);
        Fixtures.room(1L, madhapur, "101", 2, false, 8000);
        koramangala = Fixtures.pg(2L, owner, "Stay Inn", "Koramangala", "Bengaluru");
        koramangala.setLatitude(12.9352);
        koramangala.setLongitude(77.6245);
        koramangala.setGenderPreference(Enums.GenderPreference.FEMALE);
        Fixtures.room(2L, koramangala, "101", 1, true, 15000);
        noCoordinates = Fixtures.pg(3L, owner, "Budget PG", "Ameerpet", HYDERABAD);
        Fixtures.room(3L, noCoordinates, "101", 3, false, 5000);
        lenient().when(pgRepository.findAllActiveWithRooms()).thenReturn(List.of(madhapur, koramangala, noCoordinates));
        lenient().when(bedRepository.countByPgAndStatus(anyList())).thenReturn(List.of(
                new Object[]{1L, Enums.BedStatus.AVAILABLE, 1L}, new Object[]{1L, Enums.BedStatus.BOOKED, 1L},
                new Object[]{2L, Enums.BedStatus.AVAILABLE, 4L}, new Object[]{3L, Enums.BedStatus.BOOKED, 3L}));
    }

    @ParameterizedTest(name = "q={0} city={1} sharing={2} ac={3} min={4} max={5} gender={6} -> {7}")
    @CsvSource(value = {
            "NULL, NULL, NULL, NULL, NULL, NULL, NULL, 1|2|3",
            "sri sai, NULL, NULL, NULL, NULL, NULL, NULL, 1",
            "500081, NULL, NULL, NULL, NULL, NULL, NULL, 1|2|3",
            "NULL, hyderabad, NULL, NULL, NULL, NULL, NULL, 1|3",
            "NULL, NULL, 1, NULL, NULL, NULL, NULL, 2",
            "NULL, NULL, NULL, true, NULL, NULL, NULL, 2",
            "NULL, NULL, NULL, NULL, 6000, 10000, NULL, 1",
            "NULL, NULL, NULL, NULL, NULL, NULL, FEMALE, 2",
            "nothing-here, NULL, NULL, NULL, NULL, NULL, NULL, ''"}, nullValues = "NULL")
    void searchAppliesEveryFilter(String q, String city, Integer sharing, Boolean ac, BigDecimal min, BigDecimal max,
                                  Enums.GenderPreference gender, String expectedIds) {
        List<PgSummaryDto> result = service.search(new SearchCriteria(q, city, sharing, min, max, gender, ac, null,
                null, null, "PRICE_DESC"));

        assertThat(result).extracting(pg -> String.valueOf(pg.id()))
                .containsExactlyInAnyOrder(expectedIds.isEmpty() ? new String[0] : expectedIds.split("\\|"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(value = {"PRICE_ASC, 3|1|2", "price_desc, 2|1|3", "AVAILABILITY, 2|1|3", "NULL, 2|1|3", "' ', 2|1|3"},
            nullValues = "NULL")
    void searchSortsWithoutLocation(String sort, String expectedOrder) {
        List<PgSummaryDto> result = service.search(criteria(null, null, null, sort));

        assertThat(result).extracting(pg -> String.valueOf(pg.id())).containsExactly(expectedOrder.split("\\|"));
    }

    @Test
    void withALocationResultsAreSortedByDistanceAndLimitedToTheRadius() {
        List<PgSummaryDto> everything = service.search(criteria(17.45, 78.39, null, null));
        List<PgSummaryDto> nearby = service.search(criteria(17.45, 78.39, 10.0, "DISTANCE"));

        assertThat(everything).extracting(PgSummaryDto::id).containsExactly(1L, 2L, 3L);
        assertThat(everything.get(0).distanceKm()).isLessThan(1.0);
        assertThat(everything.get(2).distanceKm()).as("no coordinates, listed last").isNull();
        assertThat(nearby).extracting(PgSummaryDto::id).containsExactly(1L);
        assertThat(nearby.get(0).availableBeds()).isEqualTo(1);
        assertThat(nearby.get(0).totalBeds()).isEqualTo(2);
    }

    @Test
    void detailIsOnlyAvailableForActivePgs() {
        Pg inactive = Fixtures.pg(4L, owner, "Closed", "Area", HYDERABAD);
        inactive.setActive(false);
        when(pgRepository.findById(1L)).thenReturn(Optional.of(madhapur));
        when(pgRepository.findById(4L)).thenReturn(Optional.of(inactive));
        when(pgRepository.findById(5L)).thenReturn(Optional.empty());

        assertThat(service.getDetail(1L).name()).isEqualTo("Sri Sai Men's PG");
        assertThatThrownBy(() -> service.getDetail(4L)).isInstanceOf(ApiException.class).hasMessage("PG not found");
        assertThatThrownBy(() -> service.getDetail(5L)).isInstanceOf(ApiException.class).hasMessage("PG not found");
    }

    @Test
    void ownersOnlySeeTheirOwnPgs() {
        when(pgRepository.findByOwnerIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(madhapur));
        when(pgRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.of(madhapur));
        when(pgRepository.findByIdAndOwnerId(1L, 9L)).thenReturn(Optional.empty());

        assertThat(service.ownerPgs(1L)).hasSize(1);
        assertThat(service.ownerPg(1L, 1L).id()).isEqualTo(1L);
        assertThatThrownBy(() -> service.ownerPg(9L, 1L)).isInstanceOf(ApiException.class)
                .extracting(STATUS).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void previousStaysAreGroupedPerPgNewestFirstWithoutInactivePgs() {
        User customer = Fixtures.customer(2L);
        Pg closed = Fixtures.pg(4L, owner, "Closed", "Area", HYDERABAD);
        closed.setActive(false);
        List<Booking> bookings = new ArrayList<>();
        bookings.add(stay(1L, customer, koramangala, LocalDate.of(2026, 9, 1)));
        bookings.add(stay(2L, customer, madhapur, LocalDate.of(2026, 6, 1)));
        bookings.add(stay(3L, customer, closed, LocalDate.of(2026, 5, 1)));
        bookings.add(stay(4L, customer, koramangala, LocalDate.of(2026, 1, 1)));
        when(bookingRepository.findByCustomerIdAndStatusInOrderByStartDateDesc(eq(2L), anyCollection()))
                .thenReturn(bookings);

        List<PreviousStayDto> stays = service.previousStays(2L);

        assertThat(stays).extracting(stay -> stay.pg().id()).containsExactly(2L, 1L);
        assertThat(stays.get(0).stays()).isEqualTo(2);
        assertThat(stays.get(0).lastStayFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(stays.get(0).pg().availableBeds()).isEqualTo(4);
        assertThat(stays.get(1).stays()).isEqualTo(1);
    }

    @Test
    void previousStaysAreLimitedToSixAndEmptyWithoutHistory() {
        User customer = Fixtures.customer(2L);
        List<Booking> bookings = new ArrayList<>();
        for (long id = 10; id < 18; id++) {
            Pg pg = Fixtures.pg(id, owner, "PG " + id, "Area", HYDERABAD);
            Fixtures.room(id, pg, "101", 2, false, 7000);
            bookings.add(stay(id, customer, pg, LocalDate.of(2026, 1, 1).plusDays(id)));
        }
        when(bookingRepository.findByCustomerIdAndStatusInOrderByStartDateDesc(eq(2L), anyCollection()))
                .thenReturn(bookings);
        when(bookingRepository.findByCustomerIdAndStatusInOrderByStartDateDesc(eq(3L), anyCollection()))
                .thenReturn(List.of());

        assertThat(service.previousStays(2L)).hasSize(6);
        assertThat(service.previousStays(3L)).isEmpty();
    }

    @Test
    void createBuildsBedsDefaultsWeeklyRentAndNormalisesText() {
        when(pgRepository.save(any(Pg.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RoomRequest room = new RoomRequest(1, " 101 ", 3, true, true, BigDecimal.valueOf(400), null,
                BigDecimal.valueOf(8000), List.of(new BedRequest("b", false)));

        PgDetailDto created = service.create(owner, request(" Green Nest ", " ", room), Enums.RegisteredVia.CUSTOMER_CARE);

        assertThat(created.name()).isEqualTo("Green Nest");
        assertThat(created.upiId()).isNull();
        assertThat(created.facilities()).containsExactly("WIFI", "FOOD");
        assertThat(created.registeredVia()).isEqualTo(Enums.RegisteredVia.CUSTOMER_CARE);
        assertThat(created.totalBeds()).isEqualTo(3);
        assertThat(created.availableBeds()).isEqualTo(2);
        assertThat(created.floors().get(0).rooms().get(0).weeklyRent()).isEqualByComparingTo("2800");
        assertThat(created.floors().get(0).rooms().get(0).beds()).extracting("bedLabel", STATUS).containsExactly(
                tuple("A", Enums.BedStatus.AVAILABLE),
                tuple("B", Enums.BedStatus.UNAVAILABLE),
                tuple("C", Enums.BedStatus.AVAILABLE));
    }

    @Test
    void duplicateRoomNumbersAreRejected() {
        RoomRequest first = room("101", 2);
        RoomRequest second = room(" 101", 1);
        PgRequest request = request("Green Nest", "name@okaxis", first, second);

        assertThatThrownBy(() -> service.create(owner, request, Enums.RegisteredVia.OWNER))
                .isInstanceOf(ApiException.class).hasMessageContaining("Duplicate room number");
    }

    @Test
    void updateAddsRemovesAndResizesRoomsThatHaveNoBookings() {
        Pg pg = editablePg();
        when(pgRepository.findByIdAndOwnerId(5L, 1L)).thenReturn(Optional.of(pg));
        when(pgRepository.saveAndFlush(pg)).thenReturn(pg);

        PgDetailDto updated = service.update(1L, 5L, request("Renamed", "name@okaxis", room("101", 1), room("103", 2)));

        assertThat(updated.name()).isEqualTo("Renamed");
        assertThat(updated.upiId()).isEqualTo("name@okaxis");
        assertThat(pg.getRooms()).extracting(Room::getRoomNumber).containsExactly("101", "103");
        assertThat(pg.getRooms().get(0).getBeds()).extracting(Bed::getBedLabel).containsExactly("A");
        assertThat(pg.getRooms().get(1).getBeds()).hasSize(2);
    }

    @Test
    void updateRefusesToRemoveRoomsOrBedsWithBookings() {
        Pg pg = editablePg();
        when(pgRepository.findByIdAndOwnerId(5L, 1L)).thenReturn(Optional.of(pg));
        when(bookingRepository.existsByBedId(52L)).thenReturn(true);
        PgRequest withoutRoom102 = request("Same", null, room("101", 2));
        pg.getRooms().get(0).getBeds().get(1).setStatus(Enums.BedStatus.BOOKED);
        PgRequest smallerRoom101 = request("Same", null, room("101", 1), room("102", 1));

        assertThatThrownBy(() -> service.update(1L, 5L, withoutRoom102)).isInstanceOf(ApiException.class)
                .hasMessageContaining("Room 102 has booking history");
        assertThatThrownBy(() -> service.update(1L, 5L, smallerRoom101)).isInstanceOf(ApiException.class)
                .hasMessageContaining("Bed 101-B has bookings");
    }

    @Test
    void availabilityTogglesNeverTouchBlockedOrBookedBeds() {
        Pg pg = editablePg();
        pg.getRooms().get(0).getBeds().get(0).setStatus(Enums.BedStatus.BLOCKED);
        when(pgRepository.findByIdAndOwnerId(5L, 1L)).thenReturn(Optional.of(pg));
        when(pgRepository.saveAndFlush(pg)).thenReturn(pg);
        RoomRequest room101 = new RoomRequest(1, "101", 2, false, true, BigDecimal.valueOf(400), BigDecimal.valueOf(2500),
                BigDecimal.valueOf(8000), List.of(new BedRequest("A", false), new BedRequest("B", false)));

        service.update(1L, 5L, request("Same", null, room101, room("102", 1)));

        assertThat(pg.getRooms().get(0).getBeds()).extracting(Bed::getStatus)
                .containsExactly(Enums.BedStatus.BLOCKED, Enums.BedStatus.UNAVAILABLE);
    }

    @Test
    void ownersToggleOnlyFreeBedsOfTheirOwnPg() {
        Pg pg = editablePg();
        Bed free = pg.getRooms().get(0).getBeds().get(0);
        Bed booked = pg.getRooms().get(0).getBeds().get(1);
        booked.setStatus(Enums.BedStatus.BOOKED);
        when(bedRepository.findById(51L)).thenReturn(Optional.of(free));
        when(bedRepository.findById(53L)).thenReturn(Optional.of(booked));
        when(bedRepository.findById(404L)).thenReturn(Optional.empty());

        service.updateBedStatus(1L, 51L, Enums.BedStatus.UNAVAILABLE);

        assertThat(free.getStatus()).isEqualTo(Enums.BedStatus.UNAVAILABLE);
        assertThatThrownBy(() -> service.updateBedStatus(1L, 51L, Enums.BedStatus.BOOKED))
                .isInstanceOf(ApiException.class).extracting(STATUS).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> service.updateBedStatus(1L, 404L, Enums.BedStatus.AVAILABLE))
                .isInstanceOf(ApiException.class).extracting(STATUS).isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> service.updateBedStatus(9L, 51L, Enums.BedStatus.AVAILABLE))
                .isInstanceOf(ApiException.class).extracting(STATUS).isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> service.updateBedStatus(1L, 53L, Enums.BedStatus.AVAILABLE))
                .isInstanceOf(ApiException.class).extracting(STATUS).isEqualTo(HttpStatus.CONFLICT);
    }

    /** PG 5 with room 101 (beds 51 A, 53 B) and room 102 (bed 52 A). */
    private Pg editablePg() {
        Pg pg = Fixtures.pg(5L, owner, "Editable", "Area", HYDERABAD);
        Room first = Fixtures.room(51L, pg, "101", 2, false, 8000);
        Fixtures.bed(51L, first, "A", Enums.BedStatus.AVAILABLE);
        Fixtures.bed(53L, first, "B", Enums.BedStatus.AVAILABLE);
        Fixtures.bed(52L, Fixtures.room(52L, pg, "102", 1, false, 9000), "A", Enums.BedStatus.AVAILABLE);
        return pg;
    }

    private static Booking stay(long id, User customer, Pg pg, LocalDate start) {
        Bed bed = Fixtures.bed(id, pg.getRooms().isEmpty() ? Fixtures.room(id, pg, "101", 1, false, 7000)
                : pg.getRooms().get(0), "Z" + id, Enums.BedStatus.BOOKED);
        return Fixtures.booking(id, customer, bed, Enums.BookingType.MONTHLY, 1, start, Enums.BookingStatus.BOOKED);
    }

    private static SearchCriteria criteria(Double lat, Double lng, Double radiusKm, String sort) {
        return new SearchCriteria(null, null, null, null, null, null, null, lat, lng, radiusKm, sort);
    }

    private static RoomRequest room(String number, int sharing) {
        return new RoomRequest(1, number, sharing, false, true, BigDecimal.valueOf(400), BigDecimal.valueOf(2500),
                BigDecimal.valueOf(8000), null);
    }

    private static PgRequest request(String name, String upiId, RoomRequest... rooms) {
        return new PgRequest(name, "Nice place", " 12 Main Road ", " Madhapur ", HYDERABAD, "Telangana", "500081",
                17.44, 78.39, Enums.GenderPreference.ANY, List.of("wifi", "FOOD", "WIFI"), "9876543210", upiId,
                "No smoking", List.of(new MediaDto(Enums.MediaType.IMAGE, " https://img/1.jpg ")), List.of(rooms));
    }
}

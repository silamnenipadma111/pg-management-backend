package com.pgmanagement.service;

import com.pgmanagement.dto.PgDtos.*;
import com.pgmanagement.entity.*;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.repository.BedRepository;
import com.pgmanagement.repository.BookingRepository;
import com.pgmanagement.repository.PgRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class PgService {

    private static final String BED_LABELS = "ABCDEFGHIJKL";

    private final PgRepository pgRepository;
    private final BedRepository bedRepository;
    private final BookingRepository bookingRepository;

    @Transactional(readOnly = true)
    public List<PgSummaryDto> search(SearchCriteria c) {
        List<Pg> pgs = pgRepository.findAllActiveWithRooms();
        Map<Long, long[]> counts = bedCounts(pgs.stream().map(Pg::getId).toList());
        boolean hasLocation = c.lat() != null && c.lng() != null;

        Stream<Pg> stream = pgs.stream()
                .filter(textMatches(c.q()))
                .filter(p -> isBlank(c.city()) || p.getCity().equalsIgnoreCase(c.city().trim()))
                .filter(p -> c.gender() == null || p.getGenderPreference() == c.gender())
                .filter(p -> p.getRooms().stream().anyMatch(roomMatches(c)));

        List<PgSummaryDto> result = stream.map(p -> {
                    long[] cnt = counts.getOrDefault(p.getId(), new long[2]);
                    Double distance = hasLocation && p.getLatitude() != null && p.getLongitude() != null
                            ? GeoUtils.distanceKm(c.lat(), c.lng(), p.getLatitude(), p.getLongitude()) : null;
                    return DtoMapper.toSummary(p, cnt[0], cnt[1], distance);
                })
                .filter(s -> c.radiusKm() == null || !hasLocation
                        || (s.distanceKm() != null && s.distanceKm() <= c.radiusKm()))
                .toList();
        return sort(result, c.sort(), hasLocation);
    }

    private static Predicate<Pg> textMatches(String q) {
        if (isBlank(q)) {
            return p -> true;
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        return p -> Stream.of(p.getName(), p.getArea(), p.getCity(), p.getAddressLine(), p.getPincode(), p.getState())
                .filter(Objects::nonNull)
                .anyMatch(v -> v.toLowerCase(Locale.ROOT).contains(needle));
    }

    private static Predicate<Room> roomMatches(SearchCriteria c) {
        return r -> (c.sharing() == null || r.getSharingType() == c.sharing())
                && (c.ac() == null || r.isAc() == c.ac())
                && (c.minRent() == null || r.getMonthlyRent().compareTo(c.minRent()) >= 0)
                && (c.maxRent() == null || r.getMonthlyRent().compareTo(c.maxRent()) <= 0);
    }

    private static List<PgSummaryDto> sort(List<PgSummaryDto> list, String sort, boolean hasLocation) {
        Comparator<PgSummaryDto> cmp;
        String key = sort == null ? (hasLocation ? "DISTANCE" : "RECOMMENDED") : sort.toUpperCase(Locale.ROOT);
        switch (key) {
            case "PRICE_ASC" -> cmp = Comparator.comparing(PgSummaryDto::minRent);
            case "PRICE_DESC" -> cmp = Comparator.comparing(PgSummaryDto::minRent).reversed();
            case "AVAILABILITY" -> cmp = Comparator.comparingLong(PgSummaryDto::availableBeds).reversed();
            case "DISTANCE" -> cmp = Comparator.comparing(PgSummaryDto::distanceKm,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            default -> cmp = Comparator.comparingLong(PgSummaryDto::availableBeds).reversed()
                    .thenComparing(PgSummaryDto::id);
        }
        return list.stream().sorted(cmp).toList();
    }

    /** pgId -> [available, total] */
    private Map<Long, long[]> bedCounts(List<Long> pgIds) {
        Map<Long, long[]> map = new HashMap<>();
        if (pgIds.isEmpty()) {
            return map;
        }
        for (Object[] row : bedRepository.countByPgAndStatus(pgIds)) {
            long[] arr = map.computeIfAbsent((Long) row[0], k -> new long[2]);
            long count = (Long) row[2];
            if (row[1] == Enums.BedStatus.AVAILABLE) {
                arr[0] += count;
            }
            arr[1] += count;
        }
        return map;
    }

    @Transactional(readOnly = true)
    public PgDetailDto getDetail(Long id) {
        Pg pg = pgRepository.findById(id).filter(Pg::isActive).orElseThrow(() -> ApiException.notFound("PG"));
        return DtoMapper.toDetail(pg);
    }

    @Transactional(readOnly = true)
    public List<PgDetailDto> ownerPgs(Long ownerId) {
        return pgRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream().map(DtoMapper::toDetail).toList();
    }

    @Transactional(readOnly = true)
    public PgDetailDto ownerPg(Long ownerId, Long pgId) {
        return DtoMapper.toDetail(findOwned(ownerId, pgId));
    }

    @Transactional
    public PgDetailDto create(User owner, PgRequest req, Enums.RegisteredVia via) {
        validateRooms(req.rooms());
        Pg pg = new Pg();
        pg.setOwner(owner);
        pg.setRegisteredVia(via);
        applyBasics(pg, req);
        for (RoomRequest rr : req.rooms()) {
            Room room = new Room();
            room.setPg(pg);
            applyRoom(room, rr);
            syncBeds(room, rr);
            pg.getRooms().add(room);
        }
        return DtoMapper.toDetail(pgRepository.save(pg));
    }

    @Transactional
    public PgDetailDto update(Long ownerId, Long pgId, PgRequest req) {
        validateRooms(req.rooms());
        Pg pg = findOwned(ownerId, pgId);
        applyBasics(pg, req);

        Map<String, RoomRequest> requested = new LinkedHashMap<>();
        req.rooms().forEach(r -> requested.put(r.roomNumber().trim(), r));

        Iterator<Room> it = pg.getRooms().iterator();
        while (it.hasNext()) {
            Room room = it.next();
            RoomRequest rr = requested.remove(room.getRoomNumber());
            if (rr == null) {
                if (room.getBeds().stream().anyMatch(this::isReferenced)) {
                    throw ApiException.badRequest("Room " + room.getRoomNumber()
                            + " has booking history and cannot be removed. Mark its beds unavailable instead.");
                }
                it.remove();
            } else {
                applyRoom(room, rr);
                syncBeds(room, rr);
            }
        }
        for (RoomRequest rr : requested.values()) {
            Room room = new Room();
            room.setPg(pg);
            applyRoom(room, rr);
            syncBeds(room, rr);
            pg.getRooms().add(room);
        }
        return DtoMapper.toDetail(pgRepository.saveAndFlush(pg));
    }

    @Transactional
    public PgDetailDto updateBedStatus(Long ownerId, Long bedId, Enums.BedStatus status) {
        if (status != Enums.BedStatus.AVAILABLE && status != Enums.BedStatus.UNAVAILABLE) {
            throw ApiException.badRequest("Owners can only mark beds AVAILABLE or UNAVAILABLE");
        }
        Bed bed = bedRepository.findById(bedId).orElseThrow(() -> ApiException.notFound("Bed"));
        Pg pg = bed.getRoom().getPg();
        if (!pg.getOwner().getId().equals(ownerId)) {
            throw ApiException.forbidden("This bed does not belong to your PG");
        }
        if (bed.getStatus() == Enums.BedStatus.BLOCKED || bed.getStatus() == Enums.BedStatus.BOOKED) {
            throw ApiException.conflict("Bed is " + bed.getStatus().name().toLowerCase(Locale.ROOT)
                    + " by a customer and cannot be changed");
        }
        bed.setStatus(status);
        return DtoMapper.toDetail(pg);
    }

    public Pg findOwned(Long ownerId, Long pgId) {
        return pgRepository.findByIdAndOwnerId(pgId, ownerId).orElseThrow(() -> ApiException.notFound("PG"));
    }

    private void applyBasics(Pg pg, PgRequest req) {
        pg.setName(req.name().trim());
        pg.setDescription(req.description());
        pg.setAddressLine(req.addressLine().trim());
        pg.setArea(req.area().trim());
        pg.setCity(req.city().trim());
        pg.setState(req.state().trim());
        pg.setPincode(req.pincode().trim());
        pg.setLatitude(req.latitude());
        pg.setLongitude(req.longitude());
        pg.setGenderPreference(req.genderPreference());
        pg.setFacilities(DtoMapper.joinFacilities(req.facilities()));
        pg.setContactPhone(req.contactPhone().trim());
        pg.setHouseRules(req.houseRules());
        pg.getMedia().clear();
        int order = 0;
        for (MediaDto m : req.media()) {
            PgMedia media = new PgMedia();
            media.setPg(pg);
            media.setMediaType(m.mediaType());
            media.setUrl(m.url().trim());
            media.setSortOrder(order++);
            pg.getMedia().add(media);
        }
    }

    private void applyRoom(Room room, RoomRequest rr) {
        room.setFloorNumber(rr.floorNumber());
        room.setRoomNumber(rr.roomNumber().trim());
        room.setSharingType(rr.sharingType());
        room.setAc(rr.ac());
        room.setAttachedBathroom(rr.attachedBathroom());
        room.setMonthlyRent(rr.monthlyRent());
        room.setDailyRent(rr.dailyRent());
        room.setWeeklyRent(rr.weeklyRent() != null ? rr.weeklyRent()
                : rr.dailyRent().multiply(BigDecimal.valueOf(7)).setScale(2, RoundingMode.HALF_UP));
    }

    /** Ensures the room has exactly sharingType beds, labelled A, B, C ... */
    private void syncBeds(Room room, RoomRequest rr) {
        Map<String, Boolean> availability = new HashMap<>();
        if (rr.beds() != null) {
            rr.beds().forEach(b -> availability.put(b.bedLabel().trim().toUpperCase(Locale.ROOT), b.available()));
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (int i = 0; i < rr.sharingType(); i++) {
            wanted.add(String.valueOf(BED_LABELS.charAt(i)));
        }
        Iterator<Bed> it = room.getBeds().iterator();
        while (it.hasNext()) {
            Bed bed = it.next();
            if (!wanted.remove(bed.getBedLabel())) {
                if (bed.getStatus() == Enums.BedStatus.BLOCKED || bed.getStatus() == Enums.BedStatus.BOOKED
                        || isReferenced(bed)) {
                    throw ApiException.badRequest("Bed " + room.getRoomNumber() + "-" + bed.getBedLabel()
                            + " has bookings and cannot be removed");
                }
                it.remove();
            } else {
                applyAvailability(bed, availability.get(bed.getBedLabel()));
            }
        }
        for (String label : wanted) {
            Bed bed = new Bed();
            bed.setRoom(room);
            bed.setBedLabel(label);
            applyAvailability(bed, availability.get(label));
            room.getBeds().add(bed);
        }
    }

    private static void applyAvailability(Bed bed, Boolean available) {
        if (available == null || bed.getStatus() == Enums.BedStatus.BLOCKED
                || bed.getStatus() == Enums.BedStatus.BOOKED) {
            return;
        }
        bed.setStatus(available ? Enums.BedStatus.AVAILABLE : Enums.BedStatus.UNAVAILABLE);
    }

    private boolean isReferenced(Bed bed) {
        return bed.getId() != null && bookingRepository.existsByBedId(bed.getId());
    }

    private static void validateRooms(List<RoomRequest> rooms) {
        Set<String> seen = new HashSet<>();
        for (RoomRequest r : rooms) {
            if (!seen.add(r.roomNumber().trim())) {
                throw ApiException.badRequest("Duplicate room number " + r.roomNumber());
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

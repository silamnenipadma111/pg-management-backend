package com.pgmanagement.service;

import com.pgmanagement.config.AppProperties;
import com.pgmanagement.dto.CommonDtos.AddressDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Address lookup behind "use my current location" (coordinates to address) and the pincode auto-fill
 * (pincode to city / state). The website never talks to the map provider directly: requests go through this
 * service, which caches results and identifies the application as the provider's usage policy requires.
 */
@Slf4j
@Service
public class GeocodingService {

    private static final int CACHE_SIZE = 2000;
    /** Cached "provider does not know this place" so the same miss is not looked up again and again. */
    private static final AddressDto NOT_FOUND = new AddressDto(null, null, null, null, null, null, null);
    private static final String ADDRESS = "address";
    private static final String SUBURB = "suburb";
    private static final String NEIGHBOURHOOD = "neighbourhood";
    private static final String VILLAGE = "village";
    private static final String TOWN = "town";
    private static final int PINCODE_LENGTH = 6;
    /** Official names for the cities whose old names are still returned by some postal data. */
    private static final Map<String, String> CITY_ALIASES = Map.of(
            "bangalore", "Bengaluru", "bombay", "Mumbai", "madras", "Chennai", "calcutta", "Kolkata",
            "gurgaon", "Gurugram", "mysore", "Mysuru", "poona", "Pune", "trivandrum", "Thiruvananthapuram");

    private final AppProperties.Geocoding config;
    private final RestClient provider;
    private final RestClient pincodeFallback;
    private final Map<String, AddressDto> cache = Collections.synchronizedMap(new LruCache(CACHE_SIZE));

    public GeocodingService(AppProperties appProperties, RestClient.Builder restClientBuilder) {
        this.config = appProperties.geocoding();
        this.provider = restClientBuilder.clone().baseUrl(config.baseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, config.userAgent()).build();
        this.pincodeFallback = restClientBuilder.clone().baseUrl(config.pincodeFallbackUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, config.userAgent()).build();
    }

    /** Address of a location, e.g. the browser position of a visitor or of an owner registering a PG. */
    public Optional<AddressDto> reverse(double latitude, double longitude) {
        // 4 decimals is about 11 metres: close positions share one cache entry
        String key = String.format(Locale.ROOT, "geo:%.4f,%.4f", latitude, longitude);
        return cached(key, () -> fetchReverse(latitude, longitude));
    }

    /** City, state and approximate coordinates of an Indian pincode. */
    public Optional<AddressDto> byPincode(String pincode) {
        return cached("pin:" + pincode, () -> fetchPincode(pincode).or(() -> fetchPincodeFallback(pincode)));
    }

    private Optional<AddressDto> cached(String key, Supplier<Optional<AddressDto>> lookup) {
        if (!config.enabled()) {
            return Optional.empty();
        }
        AddressDto hit = cache.get(key);
        if (hit != null) {
            return hit == NOT_FOUND ? Optional.empty() : Optional.of(hit);
        }
        try {
            Optional<AddressDto> result = lookup.get();
            cache.put(key, result.orElse(NOT_FOUND));
            return result;
        } catch (RestClientException | JacksonException ex) {
            // provider down or rate limited: not cached, the user simply types the address
            log.warn("Address lookup failed for {}: {}", key, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<AddressDto> fetchReverse(double latitude, double longitude) {
        JsonNode root = provider.get()
                .uri(uri -> uri.path("/reverse").queryParam("format", "jsonv2").queryParam("lat", latitude)
                        .queryParam("lon", longitude).queryParam("zoom", 18).queryParam("addressdetails", 1)
                        .queryParam("accept-language", "en").build())
                .retrieve().body(JsonNode.class);
        if (root == null || !root.path(ADDRESS).isObject()) {
            return Optional.empty();
        }
        return Optional.of(toAddress(root.path(ADDRESS), latitude, longitude));
    }

    private Optional<AddressDto> fetchPincode(String pincode) {
        JsonNode root = provider.get()
                .uri(uri -> uri.path("/search").queryParam("postalcode", pincode).queryParam("countrycodes", "in")
                        .queryParam("format", "jsonv2").queryParam("addressdetails", 1).queryParam("limit", 1)
                        .queryParam("accept-language", "en").build())
                .retrieve().body(JsonNode.class);
        if (root == null || !root.isArray() || root.isEmpty() || !root.path(0).path(ADDRESS).isObject()) {
            return Optional.empty();
        }
        JsonNode place = root.path(0);
        AddressDto address = toAddress(place.path(ADDRESS), parse(place.path("lat")), parse(place.path("lon")));
        // a pincode covers many streets: it tells the city and state, not the address line
        return Optional.of(new AddressDto(null, address.area(), address.city(), address.state(), pincode,
                address.latitude(), address.longitude()));
    }

    /** India Post knows every pincode but has no coordinates. */
    private Optional<AddressDto> fetchPincodeFallback(String pincode) {
        JsonNode root = pincodeFallback.get().uri("/pincode/{pincode}", pincode).retrieve().body(JsonNode.class);
        if (root == null || !root.isArray() || root.isEmpty()) {
            return Optional.empty();
        }
        JsonNode office = root.path(0).path("PostOffice").path(0);
        String city = text(office, "District");
        String state = text(office, "State");
        if (city == null && state == null) {
            return Optional.empty();
        }
        return Optional.of(new AddressDto(null, text(office, "Name"), officialCity(city), state, pincode, null, null));
    }

    private static AddressDto toAddress(JsonNode address, Double latitude, Double longitude) {
        String road = join(" ", text(address, "house_number"), text(address, "road"));
        String neighbourhood = first(address, NEIGHBOURHOOD, "residential", "quarter");
        String area = first(address, SUBURB, NEIGHBOURHOOD, "residential", VILLAGE, TOWN, "city_district");
        String city = first(address, "city", TOWN, "municipality", VILLAGE, "county", "state_district");
        String line = join(", ", road, neighbourhood);
        String postcode = text(address, "postcode");
        String pincode = postcode == null ? null : postcode.replaceAll("\\D", "");
        return new AddressDto(line == null ? area : line, area, officialCity(city), text(address, "state"),
                pincode != null && pincode.length() == PINCODE_LENGTH ? pincode : null, latitude, longitude);
    }

    private static String officialCity(String city) {
        return city == null ? null : CITY_ALIASES.getOrDefault(city.toLowerCase(Locale.ROOT), city);
    }

    private static String first(JsonNode node, String... fields) {
        return Stream.of(fields).map(field -> text(node, field)).filter(value -> value != null).findFirst().orElse(null);
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asString("").trim();
        return value.isEmpty() ? null : value;
    }

    private static String join(String separator, String... parts) {
        String joined = Stream.of(parts).filter(part -> part != null).collect(Collectors.joining(separator));
        return joined.isEmpty() ? null : joined;
    }

    private static Double parse(JsonNode number) {
        try {
            return Double.valueOf(number.asString(""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Least recently used entries are dropped once the cache is full. */
    private static final class LruCache extends LinkedHashMap<String, AddressDto> {

        private final int capacity;

        LruCache(int capacity) {
            super(64, 0.75f, true);
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, AddressDto> eldest) {
            return size() > capacity;
        }
    }
}

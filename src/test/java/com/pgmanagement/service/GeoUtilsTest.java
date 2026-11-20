package com.pgmanagement.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeoUtilsTest {

    private static final double HYD_LAT = 17.385;
    private static final double HYD_LNG = 78.4867;
    private static final double BLR_LAT = 12.9716;
    private static final double BLR_LNG = 77.5946;

    @Test
    void distanceToTheSamePointIsZero() {
        assertThat(GeoUtils.distanceKm(HYD_LAT, HYD_LNG, HYD_LAT, HYD_LNG)).isZero();
    }

    @Test
    void hyderabadToBengaluruIsRoughlyFiveHundredKilometres() {
        assertThat(GeoUtils.distanceKm(HYD_LAT, HYD_LNG, BLR_LAT, BLR_LNG)).isCloseTo(499, within(5.0));
    }

    @Test
    void distanceIsSymmetric() {
        assertThat(GeoUtils.distanceKm(HYD_LAT, HYD_LNG, BLR_LAT, BLR_LNG))
                .isCloseTo(GeoUtils.distanceKm(BLR_LAT, BLR_LNG, HYD_LAT, HYD_LNG), within(1e-9));
    }
}

package com.pgmanagement.dto;

import java.math.BigDecimal;
import java.util.List;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    public record PgStatsDto(Long pgId, String pgName, String area, String city, long totalBeds,
                             long availableBeds, long blockedBeds, long bookedBeds, long unavailableBeds,
                             long pendingApprovals, long pendingPaymentConfirmations,
                             BigDecimal pendingRentAmount, long overdueCount, int occupancyPercent) {
    }

    public record OwnerDashboardDto(PgStatsDto totals, List<PgStatsDto> pgs) {
    }
}

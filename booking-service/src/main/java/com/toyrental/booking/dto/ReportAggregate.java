package com.toyrental.booking.dto;

import java.math.BigDecimal;
import java.util.List;

public record ReportAggregate(
        int totalBookings,
        BigDecimal totalRevenue,
        BigDecimal totalDeposits,
        int pendingReturns,
        TopToyResult topToy,
        List<RevenueByWeekResult> revenueByWeek
) {
}

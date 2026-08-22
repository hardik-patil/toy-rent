package com.toyrental.booking.dto;

import com.toyrental.booking.entity.MonthlyReport;
import com.toyrental.booking.entity.ReportStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ReportResponse(
        String id,
        int month,
        int year,
        int totalBookings,
        BigDecimal totalRevenue,
        BigDecimal totalDeposits,
        int pendingReturns,
        String topToyId,
        String topToyName,
        ReportStatus status,
        LocalDateTime generatedAt,
        LocalDateTime createdAt
) {

    public static ReportResponse from(MonthlyReport report) {
        return new ReportResponse(
                report.getId(),
                report.getMonth(),
                report.getYear(),
                report.getTotalBookings(),
                report.getTotalRevenue(),
                report.getTotalDeposits(),
                report.getPendingReturns(),
                report.getTopToyId(),
                report.getTopToyName(),
                report.getStatus(),
                report.getGeneratedAt(),
                report.getCreatedAt()
        );
    }
}

package com.toyrental.booking.couchbase;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Matches CLAUDE.md's documented monthly-reports document shape exactly. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class MonthlyReportDocument {

    private String id;
    private String reportId;
    private int month;
    private int year;
    private int totalBookings;
    private BigDecimal totalRevenue;
    private BigDecimal totalDeposits;
    private int pendingReturns;
    private TopToy topToy;
    private List<RevenueByWeek> revenueByWeek;
    private String pdfStoragePath;
    private String status;
    private Instant generatedAt;

    public static String documentId(int month, int year) {
        return "report::" + String.format("%04d-%02d", year, month);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TopToy {
        private String toyId;
        private String name;
        private int rentals;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RevenueByWeek {
        private int week;
        private BigDecimal revenue;
    }

}

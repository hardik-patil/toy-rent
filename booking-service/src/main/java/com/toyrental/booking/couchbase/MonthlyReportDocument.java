package com.toyrental.booking.couchbase;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Minimal Sprint 4 skeleton — just enough for MonthEndTriggerConsumer's idempotency check
 * (does report::yyyy-mm already exist?) and a status marker. The full document shape CLAUDE.md
 * documents (totalBookings, totalRevenue, topToy, revenueByWeek, pdfStoragePath, ...) is Sprint
 * 5 scope, once ReportService/PdfGeneratorService actually compute those fields.
 */
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
    private String status;
    private Instant generatedAt;

    public static String documentId(int month, int year) {
        return "report::" + String.format("%04d-%02d", year, month);
    }

}

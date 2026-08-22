package com.toyrental.booking.kafka;

public record MonthlyReportGeneratedPayload(String reportId, int month, int year, String status) {
}

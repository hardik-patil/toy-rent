package com.toyrental.toy.dto;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

public record AvailabilityResponse(
        String toyId,
        String toyName,
        String status,
        Boolean available,
        List<BlockedDateRange> blockedDates,
        LocalDate nextAvailable,
        Instant lastUpdated
) {

    public record BlockedDateRange(String bookingId, LocalDate from, LocalDate to, String reason) {
    }
}

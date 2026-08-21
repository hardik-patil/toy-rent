package com.toyrental.booking.kafka;

import java.time.LocalDate;

public record BookingEventPayload(
        String bookingId,
        String toyId,
        String customerId,
        LocalDate startDate,
        LocalDate endDate
) {
}

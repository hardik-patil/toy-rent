package com.toyrental.toy.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingEventPayload(
        String bookingId,
        String toyId,
        String customerId,
        LocalDate startDate,
        LocalDate endDate
) {
}

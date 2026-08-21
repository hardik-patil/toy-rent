package com.toyrental.booking.dto;

/** Mirrors toy-service's AvailabilityResponse (GET /api/v1/toys/{toyId}/availability). */
public record ToyAvailabilityResponse(
        String toyId,
        String toyName,
        String status,
        Boolean available
) {
}

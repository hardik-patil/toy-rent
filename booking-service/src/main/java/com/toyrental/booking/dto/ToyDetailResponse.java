package com.toyrental.booking.dto;

import java.math.BigDecimal;

/**
 * Mirrors the fields booking-service needs from toy-service's ToyResponse
 * (GET /internal/v1/toys/{toyId}). Unknown JSON properties are ignored by Spring Boot's default
 * Jackson config, so only the fields actually used here need to be declared.
 */
public record ToyDetailResponse(
        String id,
        String name,
        String status,
        BigDecimal weeklyPrice,
        BigDecimal monthlyPrice,
        BigDecimal depositAmount,
        boolean active
) {
}

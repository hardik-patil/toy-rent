package com.toyrental.booking.dto;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        CustomerResponse customer
) {
}

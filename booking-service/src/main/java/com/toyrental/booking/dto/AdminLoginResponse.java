package com.toyrental.booking.dto;

public record AdminLoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds
) {
}

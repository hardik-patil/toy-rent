package com.toyrental.booking.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(

        @NotBlank(message = "phone is required")
        String phone,

        @NotBlank(message = "password is required")
        String password
) {
}

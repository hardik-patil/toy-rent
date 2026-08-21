package com.toyrental.booking.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CustomerProfileUpdateRequest(

        @NotBlank(message = "name is required")
        String name,

        @Email(message = "email must be valid")
        String email
) {
}

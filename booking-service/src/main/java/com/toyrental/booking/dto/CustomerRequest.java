package com.toyrental.booking.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CustomerRequest(

        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "phone is required")
        @Pattern(regexp = "\\d{10}", message = "phone must be a 10-digit number")
        String phone,

        @Email(message = "email must be valid")
        String email,

        @NotBlank(message = "password is required")
        @Size(min = 8, message = "password must be at least 8 characters")
        String password,

        String area,
        String flat,
        String building,
        String city,
        String pincode
) {
}

package com.toyrental.booking.dto;

import jakarta.validation.constraints.NotBlank;

public record AddressUpdateRequest(

        @NotBlank(message = "flat is required")
        String flat,

        @NotBlank(message = "building is required")
        String building,

        @NotBlank(message = "area is required")
        String area,

        @NotBlank(message = "city is required")
        String city,

        String pincode
) {
}

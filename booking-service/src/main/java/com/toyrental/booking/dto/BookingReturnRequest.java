package com.toyrental.booking.dto;

import jakarta.validation.constraints.NotBlank;

public record BookingReturnRequest(

        @NotBlank(message = "condition is required")
        String condition,

        String damageNotes
) {
}

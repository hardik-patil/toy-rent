package com.toyrental.booking.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record BookingExtendRequest(

        @NotNull(message = "newEndDate is required")
        LocalDate newEndDate
) {
}

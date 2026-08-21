package com.toyrental.toy.dto;

import com.toyrental.toy.entity.AvailabilityAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record InternalAvailabilityUpdateRequest(

        @NotNull(message = "action is required")
        AvailabilityAction action,

        @NotBlank(message = "bookingId is required")
        String bookingId,

        // required when action=BLOCKED, ignored when action=RELEASED
        LocalDate from,
        LocalDate to
) {
}

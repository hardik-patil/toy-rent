package com.toyrental.booking.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MonthEndTriggerRequest(

        @NotNull(message = "month is required")
        @Min(value = 1, message = "month must be between 1 and 12")
        @Max(value = 12, message = "month must be between 1 and 12")
        Integer month,

        @NotNull(message = "year is required")
        @Min(value = 2020, message = "year must be a real year")
        Integer year
) {
}

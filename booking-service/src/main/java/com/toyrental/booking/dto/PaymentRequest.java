package com.toyrental.booking.dto;

import com.toyrental.booking.entity.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PaymentRequest(

        @NotBlank(message = "bookingId is required")
        String bookingId,

        @NotNull(message = "method is required")
        PaymentMethod method
) {
}

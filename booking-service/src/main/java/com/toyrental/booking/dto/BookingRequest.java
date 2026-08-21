package com.toyrental.booking.dto;

import com.toyrental.booking.entity.RentalType;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record BookingRequest(

        @NotBlank(message = "toyId is required")
        String toyId,

        @NotNull(message = "startDate is required")
        @FutureOrPresent(message = "startDate cannot be in the past")
        LocalDate startDate,

        @NotNull(message = "endDate is required")
        LocalDate endDate,

        @NotNull(message = "rentalType is required")
        RentalType rentalType,

        @NotBlank(message = "deliveryFlat is required")
        String deliveryFlat,

        @NotBlank(message = "deliveryBuilding is required")
        String deliveryBuilding,

        @NotBlank(message = "deliveryArea is required")
        String deliveryArea,

        @NotBlank(message = "deliveryCity is required")
        String deliveryCity,

        String deliveryPincode
) {
}

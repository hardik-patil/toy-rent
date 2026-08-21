package com.toyrental.toy.dto;

import com.toyrental.toy.entity.ToyCondition;
import com.toyrental.toy.entity.ToyStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ToyRequest(

        @NotBlank(message = "name is required")
        String name,

        String description,

        String brand,

        @NotBlank(message = "category is required")
        String category,

        @NotBlank(message = "ageGroup is required")
        String ageGroup,

        @NotNull(message = "condition is required")
        ToyCondition condition,

        ToyStatus status,

        @NotNull(message = "mrp is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "mrp must be positive")
        BigDecimal mrp,

        @NotNull(message = "weeklyPrice is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "weeklyPrice must be positive")
        BigDecimal weeklyPrice,

        @NotNull(message = "monthlyPrice is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "monthlyPrice must be positive")
        BigDecimal monthlyPrice,

        @NotNull(message = "depositAmount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "depositAmount must be positive")
        BigDecimal depositAmount
) {
}

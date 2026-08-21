package com.toyrental.toy.dto;

import com.toyrental.toy.entity.ToyCondition;
import jakarta.validation.constraints.NotNull;

public record ToyConditionRequest(

        @NotNull(message = "condition is required")
        ToyCondition condition
) {
}

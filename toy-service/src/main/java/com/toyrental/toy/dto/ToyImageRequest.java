package com.toyrental.toy.dto;

import jakarta.validation.constraints.NotBlank;

public record ToyImageRequest(

        @NotBlank(message = "url is required")
        String url,

        boolean primary,

        int sortOrder
) {
}

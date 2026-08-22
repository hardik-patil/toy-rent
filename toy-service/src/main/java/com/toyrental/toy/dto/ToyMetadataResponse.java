package com.toyrental.toy.dto;

import com.toyrental.toy.entity.ToyCondition;
import com.toyrental.toy.entity.ToyStatus;

import java.util.Arrays;
import java.util.List;

/**
 * Powers admin form dropdowns/radio groups without hardcoding option lists on the frontend.
 * categories/ageGroups are drawn from what's actually in the catalogue (free-text columns);
 * conditions/statuses are the fixed enums, listed here since there's no other endpoint that
 * exposes enum values as strings.
 */
public record ToyMetadataResponse(
        List<String> categories,
        List<String> ageGroups,
        List<String> conditions,
        List<String> statuses
) {

    public static ToyMetadataResponse of(List<String> categories, List<String> ageGroups) {
        return new ToyMetadataResponse(
                categories,
                ageGroups,
                Arrays.stream(ToyCondition.values()).map(Enum::name).toList(),
                Arrays.stream(ToyStatus.values()).map(Enum::name).toList()
        );
    }
}

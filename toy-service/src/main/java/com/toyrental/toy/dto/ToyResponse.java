package com.toyrental.toy.dto;

import com.toyrental.toy.entity.Toy;
import com.toyrental.toy.entity.ToyCondition;
import com.toyrental.toy.entity.ToyImage;
import com.toyrental.toy.entity.ToyStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ToyResponse(
        String id,
        String name,
        String description,
        String brand,
        String category,
        String ageGroup,
        ToyCondition condition,
        ToyStatus status,
        BigDecimal mrp,
        BigDecimal weeklyPrice,
        BigDecimal monthlyPrice,
        BigDecimal depositAmount,
        boolean active,
        List<ToyImageResponse> images,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public record ToyImageResponse(String id, String url, boolean primary, int sortOrder) {

        public static ToyImageResponse from(ToyImage image) {
            return new ToyImageResponse(image.getId(), image.getUrl(), image.isPrimary(), image.getSortOrder());
        }
    }

    public static ToyResponse from(Toy toy, List<ToyImageResponse> images) {
        return new ToyResponse(
                toy.getId(),
                toy.getName(),
                toy.getDescription(),
                toy.getBrand(),
                toy.getCategory(),
                toy.getAgeGroup(),
                toy.getCondition(),
                toy.getStatus(),
                toy.getMrp(),
                toy.getWeeklyPrice(),
                toy.getMonthlyPrice(),
                toy.getDepositAmount(),
                toy.isActive(),
                images,
                toy.getCreatedAt(),
                toy.getUpdatedAt()
        );
    }
}

package com.toyrental.toy.couchbase;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToyAvailabilityDocument {

    private String id;
    private String toyId;
    private String toyName;
    private String status;

    @Builder.Default
    private List<BlockedDate> blockedDates = new ArrayList<>();

    private LocalDate nextAvailable;
    private Instant lastUpdated;

    public static String documentId(String toyId) {
        return "avail::" + toyId;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BlockedDate {
        private String bookingId;
        private LocalDate from;
        private LocalDate to;
        private String reason;
    }
}

package com.toyrental.toy.couchbase;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class LogicalDateDocument {

    public static final String DOCUMENT_ID = "logical-date::current";

    private String id;
    private LocalDate currentDate;
    private Instant currentDateTime;

    // Lombok would otherwise generate isMonthEnd()/isOverdueCheckDay() getters, which Jackson
    // maps to JSON properties "monthEnd"/"overdueCheckDay" instead of the document's actual
    // "isMonthEnd"/"isOverdueCheckDay" keys — pin the property names explicitly.
    @JsonProperty("isMonthEnd")
    private boolean isMonthEnd;

    @JsonProperty("isOverdueCheckDay")
    private boolean isOverdueCheckDay;

    private int businessMonth;
    private int businessYear;
    private String timezone;
    private String mode;
    private Instant lastUpdated;
    private String updatedBy;

}

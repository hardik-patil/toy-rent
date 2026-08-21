package com.toyrental.toy.controller;

import com.toyrental.toy.dto.AvailabilityResponse;
import com.toyrental.toy.dto.PagedResponse;
import com.toyrental.toy.dto.ToyResponse;
import com.toyrental.toy.service.AvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE;

@Tag(name = "Availability", description = "Toy availability checks, backed by Couchbase")
@RestController
@RequestMapping("/api/v1/toys")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @Operation(summary = "Check whether a toy is available for a date range")
    @GetMapping("/{toyId}/availability")
    public AvailabilityResponse checkAvailability(@PathVariable String toyId,
                                                    @RequestParam @DateTimeFormat(iso = DATE) LocalDate from,
                                                    @RequestParam @DateTimeFormat(iso = DATE) LocalDate to) {
        return availabilityService.checkAvailability(toyId, from, to);
    }

    @Operation(summary = "Full blocked-date calendar for a toy")
    @GetMapping("/{toyId}/availability/calendar")
    public AvailabilityResponse calendar(@PathVariable String toyId) {
        return availabilityService.getCalendar(toyId);
    }

    @Operation(summary = "Browse toys available for a given date range")
    @GetMapping("/available")
    public PagedResponse<ToyResponse> available(@RequestParam @DateTimeFormat(iso = DATE) LocalDate from,
                                                  @RequestParam @DateTimeFormat(iso = DATE) LocalDate to,
                                                  Pageable pageable) {
        return availabilityService.browseAvailable(from, to, pageable);
    }

}

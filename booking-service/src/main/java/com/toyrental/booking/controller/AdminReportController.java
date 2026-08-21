package com.toyrental.booking.controller;

import com.toyrental.booking.dto.MonthEndTriggerRequest;
import com.toyrental.booking.kafka.MonthEndTriggerProducer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Only the trigger endpoint is built this sprint — list/detail/PDF-download (which need actual
 * report data) are Sprint 5 scope, once ReportService computes it.
 */
@Tag(name = "Reports Admin", description = "Admin-only month-end report generation (requires ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/reports")
public class AdminReportController {

    private final MonthEndTriggerProducer monthEndTriggerProducer;

    public AdminReportController(MonthEndTriggerProducer monthEndTriggerProducer) {
        this.monthEndTriggerProducer = monthEndTriggerProducer;
    }

    @Operation(summary = "Trigger month-end report generation")
    @PostMapping("/trigger")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void trigger(@Valid @RequestBody MonthEndTriggerRequest request) {
        monthEndTriggerProducer.publish(request.month(), request.year());
    }

}

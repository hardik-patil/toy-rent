package com.toyrental.booking.controller;

import com.toyrental.booking.dto.MonthEndTriggerRequest;
import com.toyrental.booking.dto.PagedResponse;
import com.toyrental.booking.dto.ReportResponse;
import com.toyrental.booking.entity.MonthlyReport;
import com.toyrental.booking.entity.ReportStatus;
import com.toyrental.booking.exception.ReportNotFoundException;
import com.toyrental.booking.kafka.MonthEndTriggerProducer;
import com.toyrental.booking.repository.MonthlyReportRepository;
import com.toyrental.booking.service.MinioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Reports Admin", description = "Admin-only month-end report generation and retrieval (requires ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/reports")
public class AdminReportController {

    private final MonthEndTriggerProducer monthEndTriggerProducer;
    private final MonthlyReportRepository monthlyReportRepository;
    private final MinioService minioService;

    public AdminReportController(MonthEndTriggerProducer monthEndTriggerProducer,
                                  MonthlyReportRepository monthlyReportRepository,
                                  MinioService minioService) {
        this.monthEndTriggerProducer = monthEndTriggerProducer;
        this.monthlyReportRepository = monthlyReportRepository;
        this.minioService = minioService;
    }

    @Operation(summary = "Trigger month-end report generation")
    @PostMapping("/trigger")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void trigger(@Valid @RequestBody MonthEndTriggerRequest request) {
        monthEndTriggerProducer.publish(request.month(), request.year());
    }

    @Operation(summary = "List generated reports")
    @GetMapping
    public PagedResponse<ReportResponse> list(Pageable pageable) {
        return PagedResponse.from(monthlyReportRepository.findAll(pageable).map(ReportResponse::from));
    }

    @Operation(summary = "Report metadata")
    @GetMapping("/{reportId}")
    public ReportResponse getById(@PathVariable String reportId) {
        return ReportResponse.from(requireReport(reportId));
    }

    @Operation(summary = "Download a report's PDF")
    @GetMapping(value = "/{reportId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadPdf(@PathVariable String reportId) {
        MonthlyReport report = requireReport(reportId);
        if (report.getStatus() != ReportStatus.SUCCESS || report.getPdfStoragePath() == null) {
            throw new ReportNotFoundException("Report " + reportId + " has no PDF available (status=" + report.getStatus() + ")");
        }
        byte[] pdf = minioService.download(report.getPdfStoragePath());
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + reportId + ".pdf\"")
                .body(pdf);
    }

    private MonthlyReport requireReport(String reportId) {
        return monthlyReportRepository.findById(reportId)
                .orElseThrow(() -> new ReportNotFoundException("Report not found: " + reportId));
    }

}

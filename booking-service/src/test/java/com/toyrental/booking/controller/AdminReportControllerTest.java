package com.toyrental.booking.controller;

import com.toyrental.booking.config.JwtKeyConfig;
import com.toyrental.booking.config.SecurityConfig;
import com.toyrental.booking.entity.MonthlyReport;
import com.toyrental.booking.entity.ReportStatus;
import com.toyrental.booking.kafka.MonthEndTriggerProducer;
import com.toyrental.booking.repository.MonthlyReportRepository;
import com.toyrental.booking.service.MinioService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminReportController.class)
@Import({SecurityConfig.class, JwtKeyConfig.class})
class AdminReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MonthEndTriggerProducer monthEndTriggerProducer;
    @MockBean
    private MonthlyReportRepository monthlyReportRepository;
    @MockBean
    private MinioService minioService;

    private MonthlyReport sampleReport() {
        return MonthlyReport.builder().id("rpt-abc12345").month(8).year(2026)
                .totalBookings(5).totalRevenue(BigDecimal.valueOf(1500)).totalDeposits(BigDecimal.valueOf(3000))
                .pendingReturns(1).status(ReportStatus.SUCCESS).pdfStoragePath("reports/2026/08/monthly-report-2026-08.pdf")
                .build();
    }

    @Test
    void triggerWithoutAdminRoleIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/admin/reports/trigger")
                        .with(SecurityMockMvcRequestPostProcessors.jwt())
                        .contentType("application/json")
                        .content("{\"month\":8,\"year\":2026}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listReturnsReportsForAdmin() throws Exception {
        when(monthlyReportRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleReport())));

        mockMvc.perform(get("/api/v1/admin/reports")
                        .with(SecurityMockMvcRequestPostProcessors.jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("rpt-abc12345"))
                .andExpect(jsonPath("$.content[0].totalBookings").value(5));
    }

    @Test
    void getByIdReturns404WhenMissing() throws Exception {
        when(monthlyReportRepository.findById("rpt-missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/reports/rpt-missing")
                        .with(SecurityMockMvcRequestPostProcessors.jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("REPORT_NOT_FOUND"));
    }

    @Test
    void downloadPdfReturnsPdfBytesForSuccessfulReport() throws Exception {
        when(monthlyReportRepository.findById("rpt-abc12345")).thenReturn(Optional.of(sampleReport()));
        when(minioService.download("reports/2026/08/monthly-report-2026-08.pdf")).thenReturn(new byte[]{'%', 'P', 'D', 'F'});

        mockMvc.perform(get("/api/v1/admin/reports/rpt-abc12345/pdf")
                        .with(SecurityMockMvcRequestPostProcessors.jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/pdf"));
    }

}

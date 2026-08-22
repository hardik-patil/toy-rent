package com.toyrental.booking.service;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.UnitValue;
import com.toyrental.booking.dto.ReportAggregate;
import com.toyrental.booking.dto.RevenueByWeekResult;
import com.toyrental.booking.entity.Booking;
import com.toyrental.booking.entity.Customer;
import com.toyrental.booking.entity.MonthlyReport;
import com.toyrental.booking.entity.Payment;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.Locale;

@Slf4j
@Service
public class PdfGeneratorService {

    private final Timer receiptPdfTimer;
    private final Timer reportPdfTimer;

    public PdfGeneratorService(MeterRegistry meterRegistry) {
        this.receiptPdfTimer = Timer.builder("pdf.generation.duration")
                .description("Time to generate a PDF document")
                .tag("type", "booking_receipt")
                .register(meterRegistry);
        this.reportPdfTimer = Timer.builder("pdf.generation.duration")
                .description("Time to generate a PDF document")
                .tag("type", "monthly_report")
                .register(meterRegistry);
    }

    public byte[] generateBookingReceipt(Booking booking, Customer customer, Payment payment) {
        return receiptPdfTimer.record(() -> {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                 PdfDocument pdfDoc = new PdfDocument(new PdfWriter(out));
                 Document document = new Document(pdfDoc)) {

                document.add(new Paragraph("ToyRental Platform — Booking Receipt")
                        .setBold().setFontSize(16));
                document.add(new Paragraph("Booking ID: " + booking.getId()));
                document.add(new Paragraph("Customer: " + customer.getName() + " (" + customer.getPhone() + ")"));
                document.add(new Paragraph(" "));

                Table table = new Table(UnitValue.createPercentArray(new float[]{1, 1}))
                        .useAllAvailableWidth();
                addRow(table, "Toy ID", booking.getToyId());
                addRow(table, "Rental Period", booking.getStartDate() + " to " + booking.getEndDate());
                addRow(table, "Rental Type", booking.getRentalType().name());
                addRow(table, "Rental Amount", "INR " + booking.getRentalAmount());
                addRow(table, "Deposit Amount", "INR " + booking.getDepositAmount());
                addRow(table, "Total Amount", "INR " + booking.getTotalAmount());
                addRow(table, "Booking Status", booking.getStatus().name());
                if (payment != null) {
                    addRow(table, "Payment Status", payment.getStatus().name());
                    addRow(table, "Payment Method", payment.getMethod().name());
                    if (payment.getRazorpayPaymentId() != null) {
                        addRow(table, "Payment Reference", payment.getRazorpayPaymentId());
                    }
                }
                addRow(table, "Delivery Address", deliveryAddress(booking));
                document.add(table);

                document.close();
                log.info("Generated receipt PDF for bookingId={}", booking.getId());
                return out.toByteArray();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to generate receipt PDF for booking " + booking.getId(), e);
            }
        });
    }

    public byte[] generateMonthlyReportPdf(MonthlyReport report, ReportAggregate aggregate) {
        return reportPdfTimer.record(() -> {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                 PdfDocument pdfDoc = new PdfDocument(new PdfWriter(out));
                 Document document = new Document(pdfDoc)) {

                String monthName = Month.of(report.getMonth()).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                document.add(new Paragraph("ToyRental Platform — Monthly Report").setBold().setFontSize(16));
                document.add(new Paragraph(monthName + " " + report.getYear()));
                document.add(new Paragraph(" "));

                Table summary = new Table(UnitValue.createPercentArray(new float[]{1, 1})).useAllAvailableWidth();
                addRow(summary, "Total Bookings", String.valueOf(aggregate.totalBookings()));
                addRow(summary, "Total Revenue", "INR " + aggregate.totalRevenue());
                addRow(summary, "Total Deposits Held", "INR " + aggregate.totalDeposits());
                addRow(summary, "Pending Returns", String.valueOf(aggregate.pendingReturns()));
                if (aggregate.topToy() != null) {
                    addRow(summary, "Top Toy", aggregate.topToy().name() + " (" + aggregate.topToy().rentals() + " rentals)");
                }
                document.add(summary);

                if (!aggregate.revenueByWeek().isEmpty()) {
                    document.add(new Paragraph(" "));
                    document.add(new Paragraph("Revenue by Week").setBold());
                    Table weekly = new Table(UnitValue.createPercentArray(new float[]{1, 1})).useAllAvailableWidth();
                    addRow(weekly, "Week", "Revenue");
                    for (RevenueByWeekResult week : aggregate.revenueByWeek()) {
                        addRow(weekly, "Week " + week.week(), "INR " + week.revenue());
                    }
                    document.add(weekly);
                }

                document.close();
                log.info("Generated monthly report PDF for reportId={}", report.getId());
                return out.toByteArray();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to generate report PDF for reportId " + report.getId(), e);
            }
        });
    }

    private void addRow(Table table, String label, String value) {
        table.addCell(new Cell().add(new Paragraph(label).setBold()));
        table.addCell(new Cell().add(new Paragraph(value == null ? "-" : value)));
    }

    private String deliveryAddress(Booking booking) {
        return String.join(", ",
                nullSafe(booking.getDeliveryFlat()), nullSafe(booking.getDeliveryBuilding()),
                nullSafe(booking.getDeliveryArea()), nullSafe(booking.getDeliveryCity()),
                nullSafe(booking.getDeliveryPincode()));
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

}

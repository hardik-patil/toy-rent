package com.toyrental.booking.service;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.UnitValue;
import com.toyrental.booking.entity.Booking;
import com.toyrental.booking.entity.Customer;
import com.toyrental.booking.entity.Payment;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;

@Slf4j
@Service
public class PdfGeneratorService {

    private final Timer pdfGenerationTimer;

    public PdfGeneratorService(MeterRegistry meterRegistry) {
        this.pdfGenerationTimer = Timer.builder("pdf.generation.duration")
                .description("Time to generate a PDF document")
                .tag("type", "booking_receipt")
                .register(meterRegistry);
    }

    public byte[] generateBookingReceipt(Booking booking, Customer customer, Payment payment) {
        return pdfGenerationTimer.record(() -> {
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

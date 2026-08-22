package com.toyrental.booking.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBookingNotFound(BookingNotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", ex.getMessage(), request, true);
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCustomerNotFound(CustomerNotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", ex.getMessage(), request, true);
    }

    @ExceptionHandler(ReportNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleReportNotFound(ReportNotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", ex.getMessage(), request, true);
    }

    @ExceptionHandler(ToyNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleToyNotAvailable(ToyNotAvailableException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "TOY_NOT_AVAILABLE", ex.getMessage(), request, true);
    }

    @ExceptionHandler(BookingStateConflictException.class)
    public ResponseEntity<ErrorResponse> handleBookingStateConflict(BookingStateConflictException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "BOOKING_STATE_CONFLICT", ex.getMessage(), request, true);
    }

    @ExceptionHandler(InvalidBookingRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBookingRequest(InvalidBookingRequestException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_BOOKING_REQUEST", ex.getMessage(), request, false);
    }

    @ExceptionHandler(DuplicateCustomerException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateCustomer(DuplicateCustomerException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "CUSTOMER_ALREADY_EXISTS", ex.getMessage(), request, true);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", ex.getMessage(), request, false);
    }

    @ExceptionHandler(PaymentFailedException.class)
    public ResponseEntity<ErrorResponse> handlePaymentFailed(PaymentFailedException ex, HttpServletRequest request) {
        return respond(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_FAILED", ex.getMessage(), request, true);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request, false);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(RuntimeException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.error("Unhandled exception correlationId={}", correlationId, ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred", correlationId, request);
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String error, String message,
                                                    HttpServletRequest request, boolean warnLevel) {
        String correlationId = correlationId(request);
        if (warnLevel) {
            log.warn("{} correlationId={} message={}", error, correlationId, message);
        } else {
            log.info("{} correlationId={} message={}", error, correlationId, message);
        }
        return build(status, error, message, correlationId, request);
    }

    private String correlationId(HttpServletRequest request) {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        return correlationId == null || correlationId.isBlank() ? "unknown" : correlationId;
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String error, String message,
                                                 String correlationId, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                Instant.now().toString(), status.value(), error, message, correlationId, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }

    public record ErrorResponse(String timestamp, int status, String error, String message,
                                 String correlationId, String path) {
    }

}

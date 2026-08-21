package com.toyrental.toy.exception;

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

    @ExceptionHandler(ToyNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ToyNotFoundException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.warn("Toy not found correlationId={} message={}", correlationId, ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "TOY_NOT_FOUND", ex.getMessage(), correlationId, request);
    }

    @ExceptionHandler(ToyNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleNotAvailable(ToyNotAvailableException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.warn("Toy not available correlationId={} message={}", correlationId, ex.getMessage());
        return build(HttpStatus.CONFLICT, "TOY_NOT_AVAILABLE", ex.getMessage(), correlationId, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Validation failed correlationId={} message={}", correlationId, message);
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, correlationId, request);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(RuntimeException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.error("Unhandled exception correlationId={}", correlationId, ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred", correlationId, request);
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

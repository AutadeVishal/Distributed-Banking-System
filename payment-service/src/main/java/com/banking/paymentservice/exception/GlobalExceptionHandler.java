package com.banking.paymentservice.exception;

import com.banking.error.ErrorResponse;
import com.razorpay.RazorpayException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.validation.FieldError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePaymentNotFound(PaymentNotFoundException ex) {
        return response(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .orElse(null);
        String message = fieldError == null || fieldError.getDefaultMessage() == null
                ? "Request validation failed"
                : fieldError.getDefaultMessage();
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getMessage())
                .orElse("Request validation failed");
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", safeMessage(ex.getMessage()));
    }

    @ExceptionHandler(RazorpayException.class)
    public ResponseEntity<ErrorResponse> handlePaymentProvider(RazorpayException ex) {
        log.error("Payment provider error", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "PAYMENT_PROVIDER_ERROR",
                "Unable to process the payment request");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex) {
        log.error("Unexpected payment error", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred");
    }

    private ResponseEntity<ErrorResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, LocalDateTime.now()));
    }

    private String safeMessage(String message) {
        return message == null || message.isBlank() ? "Invalid request" : message;
    }
}

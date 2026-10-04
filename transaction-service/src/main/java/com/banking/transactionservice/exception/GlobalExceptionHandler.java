package com.banking.transactionservice.exception;

import com.banking.error.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(TransactionCreationException.class)
    public ResponseEntity<ErrorResponse> handleTransactionCreation(
            TransactionCreationException ex) {
        log.error(ex.getMessage(), ex);

        return response(HttpStatus.INTERNAL_SERVER_ERROR,
                "TRANSACTION_CREATION_FAILED", "Something Went Wrong");
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTransactionNotFound(
            TransactionNotFoundException ex) {
        return response(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(TransactionStateException.class)
    public ResponseEntity<ErrorResponse> handleTransactionState(
            TransactionStateException ex) {
        return response(HttpStatus.CONFLICT, "INVALID_TRANSACTION_STATE", ex.getMessage());
    }

    @ExceptionHandler(InvalidOtpException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOtp(
            InvalidOtpException ex) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_OTP", ex.getMessage());
    }

    @ExceptionHandler(TransactionServiceException.class)
    public ResponseEntity<ErrorResponse> handleTransactionService(
            TransactionServiceException ex) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR,
                "TRANSACTION_SERVICE_ERROR", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("Request validation failed");
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getMessage())
                .orElse("Request validation failed");
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(
            MissingRequestHeaderException ex) {
        return response(HttpStatus.BAD_REQUEST, "MISSING_REQUEST_HEADER",
                "Required request header is missing: " + ex.getHeaderName());
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ErrorResponse> handleMalformedRequest(Exception ex) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "The request is invalid or malformed");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private ResponseEntity<ErrorResponse> response(
            HttpStatus status,
            String code,
            String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message));
    }
}

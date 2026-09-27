package com.banking.transactionservice.exception;

import com.banking.error.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
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
    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(TransactionNotFoundException ex) {
        return response(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(TransactionStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidState(TransactionStateException ex) {
        return response(HttpStatus.CONFLICT, "INVALID_TRANSACTION_STATE", ex.getMessage());
    }

    @ExceptionHandler(TransactionConsistencyException.class)
    public ResponseEntity<ErrorResponse> handleConsistency(TransactionConsistencyException ex) {
        log.error("Transaction consistency error", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "TRANSACTION_CONSISTENCY_ERROR",
                "The transaction could not be completed");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex) {
        log.error("Unexpected transaction error", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred");
    }

    private ResponseEntity<ErrorResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, LocalDateTime.now()));
    }
}

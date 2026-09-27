package com.banking.transactionservice.exception;

public class TransactionConsistencyException extends RuntimeException {
    public TransactionConsistencyException(String message) {
        super(message);
    }
}

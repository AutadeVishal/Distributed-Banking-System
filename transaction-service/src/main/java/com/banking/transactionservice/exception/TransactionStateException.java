package com.banking.transactionservice.exception;

public class TransactionStateException extends RuntimeException {
    public TransactionStateException(String message) {
        super(message);
    }
}

package com.banking.transactionservice.exception;

public class TransactionNotFoundException extends TransactionServiceException {

    public TransactionNotFoundException(String message) {
        super(message);
    }
}

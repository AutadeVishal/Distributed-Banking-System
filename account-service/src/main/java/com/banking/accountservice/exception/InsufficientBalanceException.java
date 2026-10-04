package com.banking.accountservice.exception;

public class InsufficientBalanceException extends AccountServiceException {

    public InsufficientBalanceException(String message) {
        super(message);
    }
}

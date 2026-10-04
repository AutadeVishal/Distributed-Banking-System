package com.banking.accountservice.exception;

public class AccountNotFoundException extends AccountServiceException {

    public AccountNotFoundException(String message) {
        super(message);
    }
}
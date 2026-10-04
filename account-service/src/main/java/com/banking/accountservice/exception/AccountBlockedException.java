package com.banking.accountservice.exception;

public class AccountBlockedException extends AccountServiceException {

    public AccountBlockedException(String message) {
        super(message);
    }
}

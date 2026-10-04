package com.banking.accountservice.exception;

public class AccountInactiveException extends AccountServiceException {

    public AccountInactiveException(String message) {
        super(message);
    }
}
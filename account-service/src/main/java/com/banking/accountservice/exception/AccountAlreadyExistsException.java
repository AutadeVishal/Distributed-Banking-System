package com.banking.accountservice.exception;

public class AccountAlreadyExistsException extends AccountServiceException {

    public AccountAlreadyExistsException(String message) {
        super(message);
    }

    public AccountAlreadyExistsException(String message, Throwable cause) {
        super(message, cause);
    }
}
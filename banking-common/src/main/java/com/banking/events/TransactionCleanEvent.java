package com.banking.events;

public record TransactionCleanEvent (
        Long transactionId,
        boolean isFraud,
        String reason
){
}

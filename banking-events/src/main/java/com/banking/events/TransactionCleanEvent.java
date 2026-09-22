package com.banking.events;

public record TransactionCleanEvent (
        String transactionId,
        boolean isFraud,
        String reason
){
}

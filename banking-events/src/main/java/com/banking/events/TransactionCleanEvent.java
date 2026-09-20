package com.banking.events;

public record TransactionCleanEvent (
        String transactionId,
        String isFraud,
        String reason
){
}

package com.banking.events;

import java.util.UUID;

public record TransactionCleanEvent (
        Long transactionId,
        String isFraud,
        String reason
){
}

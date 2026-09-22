package com.banking.events;

import java.util.UUID;

public record TransactionCleanEvent (
        String transactionId,
        String isFraud,
        String reason
){
}

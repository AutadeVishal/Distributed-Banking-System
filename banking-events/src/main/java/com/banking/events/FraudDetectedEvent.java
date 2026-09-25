package com.banking.events;

import java.util.UUID;

public record FraudDetectedEvent(
        Long transactionId,
        String senderAccountNumber,
        String reason
) {
}
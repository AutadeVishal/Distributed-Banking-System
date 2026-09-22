package com.banking.events;

import java.util.UUID;

public record FraudDetectedEvent(
        String transactionId,
        String senderAccountNumber,
        String reason
) {
}
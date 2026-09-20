package com.banking.events;

public record FraudDetectedEvent(
        String transactionId,
        String senderAccountNumber,
        String reason
) {
}
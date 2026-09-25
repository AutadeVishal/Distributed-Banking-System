package com.banking.events;

public record FraudDetectedEvent(
        Long transactionId,
        String senderAccountNumber,
        String reason
) {
}
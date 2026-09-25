package com.banking.events;

public record TransactionFailedEvent(
        Long transactionId,
        String senderAccountNumber,
        String receiverAccountNumber,
        String reason
) {
}

package com.banking.events;

public record TransactionSettlementCompletedEvent(
        Long transactionId
) {
}
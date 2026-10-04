package com.banking.events;

import java.math.BigDecimal;

public record TransactionSettlementRequestedEvent(
        Long transactionId,
        String senderAccountNumber,
        String receiverAccountNumber,
        BigDecimal amount
) {
}
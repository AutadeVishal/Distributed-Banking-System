package com.banking.events;

import java.math.BigDecimal;

public record TransactionFailedEvent(
        Long transactionId,
        String senderAccountNumber,
        String receiverAccountNumber,
        BigDecimal amount,
        String reason
) {
}

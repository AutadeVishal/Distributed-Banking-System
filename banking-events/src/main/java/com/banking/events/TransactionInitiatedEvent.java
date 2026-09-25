package com.banking.events;

import java.math.BigDecimal;

public record TransactionInitiatedEvent(
        Long transactionId,
        String senderAccountNumber,
        String receiverAccountNumber,
        BigDecimal amount,
        String description
) {}
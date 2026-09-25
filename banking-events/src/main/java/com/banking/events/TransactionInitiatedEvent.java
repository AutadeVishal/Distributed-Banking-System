package com.banking.events;

import java.math.BigDecimal;
import java.util.UUID;

public record TransactionInitiatedEvent(
        Long transactionId,
        String senderAccountNumber,
        String receiverAccountNumber,
        BigDecimal amount,
        String description
) {}
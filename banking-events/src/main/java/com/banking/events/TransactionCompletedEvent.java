package com.banking.events;

import java.math.BigDecimal;
import java.util.UUID;


public record TransactionCompletedEvent(
         Long transactionId,
         String senderAccountNumber,
         String receiverAccountNumber,
         BigDecimal amount,
         String description
) {
}
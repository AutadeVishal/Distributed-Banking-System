package com.banking.events;

import java.math.BigDecimal;


public record TransactionCompletedEvent(
         String transactionId,
         String senderAccountNumber,
         String receiverAccountNumber,
         BigDecimal amount,
         String description
) {
}
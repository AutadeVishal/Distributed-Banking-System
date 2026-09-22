package com.banking.events;

import java.math.BigDecimal;
import java.util.UUID;

public record TransactionRefundedEvent (
        String transactionId,
        String senderAccountNumber,
        BigDecimal amount,
        String reason

){
}
package com.banking.events;

import java.math.BigDecimal;

public record TransactionRefundedEvent (
        String transactionId,
        String senderAccountNumber,
        BigDecimal amount,
        String reason

){
}
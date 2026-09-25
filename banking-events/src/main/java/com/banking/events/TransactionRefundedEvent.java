package com.banking.events;

import java.math.BigDecimal;
public record TransactionRefundedEvent (
        Long transactionId,
        String senderAccountNumber,
        BigDecimal amount,
        String reason

){
}
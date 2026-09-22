package com.banking.events;

import java.math.BigDecimal;
import java.util.UUID;

public record TransactionRefundedEvent (
        Long transactionId,
        String senderAccountNumber,
        BigDecimal amount,
        String reason

){
}
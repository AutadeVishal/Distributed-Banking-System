package com.banking.events;


import java.math.BigDecimal;

public record VerificationRequiredEvent(
        String transactionId,
        String senderAcountNumber,
        BigDecimal amount,
        String reason
) {
}

package com.banking.events;


import java.math.BigDecimal;

public record VerificationRequiredEvent(
        Long transactionId,
        String senderAccountNumber,
        BigDecimal amount,
        String reason
) {
}

package com.banking.events;


import java.math.BigDecimal;
import java.util.UUID;

public record VerificationRequiredEvent(
        Long transactionId,
        String senderAccountNumber,
        BigDecimal amount,
        String reason
) {
}

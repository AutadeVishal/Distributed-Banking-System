package com.banking.events;

import java.math.BigDecimal;

public record OTPGeneratedEvent(
        String   transactionId,
        String senderAccountNumber,
        String reason,
        String otp,
        BigDecimal amount
){}

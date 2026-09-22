package com.banking.events;

import java.math.BigDecimal;
import java.util.UUID;

public record OTPGeneratedEvent(
        String   transactionId,
        String senderAccountNumber,
        String reason,
        String otp,
        BigDecimal amount
){}

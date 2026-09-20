package com.banking.events;

import java.math.BigDecimal;

public record PaymentFailedEvent (
        String paymentId,
        String senderAccountNumber,
        BigDecimal amount,
        String reason
){
}
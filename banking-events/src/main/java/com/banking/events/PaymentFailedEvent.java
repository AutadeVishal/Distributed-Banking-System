package com.banking.events;

import java.math.BigDecimal;

public record PaymentFailedEvent (
        Long paymentId,
        String senderAccountNumber,
        BigDecimal amount,
        String reason
){
}
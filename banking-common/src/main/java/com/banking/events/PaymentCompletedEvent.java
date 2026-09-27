package com.banking.events;

import java.math.BigDecimal;

public record PaymentCompletedEvent(
        Long paymentId,
        String razorpayPaymentId,
        String senderAccountNumber,
        BigDecimal amount

) {
}
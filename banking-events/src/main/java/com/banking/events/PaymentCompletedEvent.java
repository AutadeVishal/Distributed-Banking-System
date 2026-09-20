package com.banking.events;

import java.math.BigDecimal;

public record PaymentCompletedEvent(
        String paymentId,
        String razorpayPaymentId,
        String senderAccountNumber,
        BigDecimal amount

) {
}
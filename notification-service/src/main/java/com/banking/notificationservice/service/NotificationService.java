package com.banking.notificationservice.service;

import com.banking.events.TransactionCompletedEvent;
import com.banking.events.TransactionFailedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class NotificationService {

    @KafkaListener(topics = "notification.transaction.completed")
    public void consumeTransactionCompleted(
            @Payload TransactionCompletedEvent event
    ) {
        log.info(
                "Notification received - transaction {} completed; amount: {} from: {} to: {}",
                event.transactionId(),
                event.amount(),
                event.senderAccountNumber(),
                event.receiverAccountNumber()
        );
        sendAlert(
                event.senderAccountNumber(),
                "Money Debited",
                String.format(
                        "%s debited from account: %s",
                        event.amount(),
                        event.senderAccountNumber()
                )
        );
        sendAlert(
                event.receiverAccountNumber(),
                "Money Credited",
                String.format(
                        "%s credited to account: %s",
                        event.amount(),
                        event.receiverAccountNumber()
                )
        );
    }

    @KafkaListener(topics = "notification.transaction.failed")
    public void consumeTransactionFailed(
            @Payload TransactionFailedEvent event
    ) {
        log.warn(
                "Notification received - transaction {} failed; amount: {} from: {} to: {}; reason: {}",
                event.transactionId(),
                event.amount(),
                event.senderAccountNumber(),
                event.receiverAccountNumber(),
                event.reason()
        );
        sendAlert(
                event.senderAccountNumber(),
                event.title(),
                event.reason()
        );
    }

    private void sendAlert(String accountNumber, String subject, String message) {
        log.info("Notification for account {} - {}: {}",
                accountNumber, subject, message);
    }
}

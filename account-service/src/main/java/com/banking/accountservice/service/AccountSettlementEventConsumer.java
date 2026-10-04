package com.banking.accountservice.service;

import com.banking.accountservice.exception.AccountBlockedException;
import com.banking.accountservice.exception.AccountServiceException;
import com.banking.accountservice.exception.InsufficientBalanceException;
import com.banking.events.TransactionFailedEvent;
import com.banking.events.TransactionSettlementCompletedEvent;
import com.banking.events.TransactionSettlementRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.TimeUnit;
import com.banking.accountservice.repository.SettlementRecordRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountSettlementEventConsumer {

    private static final String SETTLEMENT_COMPLETED_TOPIC =
            "transaction.settlement.completed";
    private static final String SETTLEMENT_FAILED_TOPIC =
            "transaction.settlement.failed";
    private static final String  SETTLEMENT_REQUESTED_TOPIC =
            "transaction.settlement.requested";
    private final AccountService accountService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final SettlementRecordRepository settlementRecordRepository;

    @KafkaListener(topics =SETTLEMENT_REQUESTED_TOPIC )
    @Transactional
    public void consumeSettlementRequested(
            TransactionSettlementRequestedEvent event
    ) {
    if (settlementRecordRepository.claim(event.transactionId()) == 0) {
        log.info("Ignoring duplicate settlement delivery for transaction {}", event.transactionId());
        return;
    }
        log.info(
                "SETTLEMENT START - transaction: {} amount: {} from: {} to: {}",
                event.transactionId(),
                event.amount(),
                event.senderAccountNumber(),
                event.receiverAccountNumber()
        );
        try {
            accountService.transfer(
                    event.senderAccountNumber(),
                    event.receiverAccountNumber(),
                    event.amount()
            );

            kafkaTemplate.send(
                    SETTLEMENT_COMPLETED_TOPIC,
                    String.valueOf(event.transactionId()),
                    new TransactionSettlementCompletedEvent(event.transactionId())
            ).get(5, TimeUnit.SECONDS);
            log.info(
                    "SETTLEMENT SUCCESS - transaction: {} amount: {} from: {} to: {}",
                    event.transactionId(),
                    event.amount(),
                    event.senderAccountNumber(),
                    event.receiverAccountNumber()
            );
        } catch (AccountServiceException exception) {
            log.warn(
                    "SETTLEMENT FAILED - transaction: {} amount: {} from: {} to: {}; reason: {}",
                    event.transactionId(),
                    event.amount(),
                    event.senderAccountNumber(),
                    event.receiverAccountNumber(),
                    exception.getMessage()
            );
            publishFailure(event, exception.getMessage());
        } catch (Exception exception) {
            log.error("Unexpected settlement failure for transaction {}",
                    event.transactionId(), exception);
            publishFailure(
                    event,
                    "Something went wrong while processing the transaction"
            );
        }
    }

    private void publishFailure(
            TransactionSettlementRequestedEvent event,
            String reason
    ) {
        log.warn(
                "Publishing transaction failure - transaction: {} reason: {}",
                event.transactionId(),
                reason
        );
        kafkaTemplate.send(
                SETTLEMENT_FAILED_TOPIC,
                String.valueOf(event.transactionId()),
                new TransactionFailedEvent(
                        event.transactionId(),
                        event.senderAccountNumber(),
                        event.receiverAccountNumber(),
                        event.amount(),
                        "Settlement Failed",
                        reason
                )
        );
    }
}

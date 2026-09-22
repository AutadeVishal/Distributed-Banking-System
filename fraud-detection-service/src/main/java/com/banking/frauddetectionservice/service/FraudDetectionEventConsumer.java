package com.banking.frauddetectionservice.service;

import com.banking.events.TransactionInitiatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class FraudDetectionEventConsumer {
    private final FraudDetectionService fraudDetectionService;

    /*
        Listens to transaction.initiated topic send by transaction service
        every transaction goes through this befor completion

     */
    @KafkaListener(topics = "transaction.initiated")
    public void consumeTransactionInitiated(
            @Payload TransactionInitiatedEvent event
            ){
        log.info("Received Transaction for Fraud Check : {}",event.transactionId());
        try{
            fraudDetectionService.checkTransaction(event);
        }catch(Exception e){
            log.error("Error processing transaction fraud check event", e);
            throw e;
        }
    }
}

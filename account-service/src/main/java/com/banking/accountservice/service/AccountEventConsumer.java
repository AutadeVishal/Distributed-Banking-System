package com.banking.accountservice.service;

import com.banking.accountservice.entity.Account;
import com.banking.events.FraudDetectedEvent;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import com.banking.events.TransactionCompletedEvent;
import java.math.BigDecimal;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountEventConsumer {
    private final AccountService accountService;
    /*
    consume transaction completed event from kafka
     */
    @KafkaListener(topics="transaction.completed")
    public void consumeTransactionCompleted(
            @Payload TransactionCompletedEvent event
    ){
        try{
            String receiverAccount= event.receiverAccountNumber();
            BigDecimal amount=event.amount();
            log.info("Crediting account : {} amount : {}",receiverAccount,amount);
            accountService.creditBalance(receiverAccount,amount);

        }catch(Exception e){
            log.error("Error in crediting to account : {} ",e.getMessage());
        }
    }

    /*
        Consume Fraud Detected Event From Kafka
        Blocks The Account Debits
     */
    @KafkaListener(topics="fraud.detected")
    public void consumeFraudDetected(
            @Payload FraudDetectedEvent fraudDetectedEvent
            )
    {
        try{
            String senderAccountNumber=fraudDetectedEvent.senderAccountNumber();
            log.info("Fraud Detected -> Blocking Account : {}",senderAccountNumber);
            accountService.blockAccount(senderAccountNumber);
        }catch(Exception e){
            log.error("Error in blocking account : {}",e.getMessage());
        }
    }
}

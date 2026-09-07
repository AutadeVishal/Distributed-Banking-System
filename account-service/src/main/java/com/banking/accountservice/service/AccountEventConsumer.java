package com.banking.accountservice.service;

import com.banking.accountservice.entity.Account;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

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
            @Payload Map<String,Object> payload
    ){
        try{
            String receiverAccount=(String) payload.get("receiverAccountNumber");
            BigDecimal amount=new BigDecimal(payload.get("amount").toString());
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
    @KafkaListener(topics="fraud-detected")
    public void consumeFraudDetected(
            @Payload Map<String,Object> payload
    )
    {
        try{
            String accountNumber=(String) payload.get("accountNumber");
            log.info("Fraud Detected -> Blocking Account : {}",accountNumber);
            accountService.blockAccount(accountNumber);
        }catch(Exception e){
            log.error("Error in blocking account : {}",e.getMessage());
        }
    }
}

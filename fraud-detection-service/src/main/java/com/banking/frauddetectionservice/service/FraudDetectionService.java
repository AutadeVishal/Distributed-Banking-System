package com.banking.frauddetectionservice.service;

import com.banking.frauddetectionservice.client.AccountServiceClient;
import com.banking.frauddetectionservice.modal.FraudCheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FraudDetectionService {
    private final AccountServiceClient accountServiceClient;
    private final String VERIFICATION_REQUIRED_TOPIC="verification.required";
    private final String FRAUD_CHECK_CLEAN_RESULT_TOPIC="fraud.check.clean";
    private final KafkaTemplate<String,Object> kafkaTemplate;
    public void checkTransaction(Map<String,Object> payload){
        String transactionId=(String) payload.get("transactionId");
        String accountNumber=(String) payload.get("senderAccountNumber");
        BigDecimal amount=new BigDecimal(payload.get("amount").toString());

        //fetch real balance from account service
        BigDecimal senderBalance=accountServiceClient.getBalance(accountNumber);

        log.info("Checking Transaction : {} account : {} amount : {} balance: {} ",
                transactionId,accountNumber,amount,senderBalance);
        FraudCheckResult result=performFraudChecks(accountNumber,amount,senderBalance);
        if(result.isFraud()){
            log.info("Suspicious Activity Detected -account : {} resaon: {} .Requesting OTP Verification",accountNumber,result.getReason());
            Map<String,Object> verificationEvent=new HashMap<>();
            verificationEvent.put("transactionId",transactionId);
            verificationEvent.put("accountNumber",accountNumber);
            verificationEvent.put("amount",amount);
            verificationEvent.put("reason",result.getReason())
            kafkaTemplate.send(VERIFICATION_REQUIRED_TOPIC,transactionId,verificationEvent);
        }
        else{
            //proceed with transaction
            log.info("Transaction Clean");
            Map<String,Object> transactionCleanEvent=new HashMap<>();
            transactionCleanEvent.put("transactionId",transactionId);
            transactionCleanEvent.put("isFraud",false);
            transactionCleanEvent.put("reason",null);
            kafkaTemplate.send(FRAUD_CHECK_CLEAN_RESULT_TOPIC,transactionId,transactionCleanEvent);

        }
    }

}

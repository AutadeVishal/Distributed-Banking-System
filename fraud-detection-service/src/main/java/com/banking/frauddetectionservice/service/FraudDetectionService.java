package com.banking.frauddetectionservice.service;

import com.banking.events.TransactionInitiatedEvent;
import com.banking.events.VerificationRequiredEvent;
import com.banking.frauddetectionservice.client.AccountServiceClient;
import com.banking.frauddetectionservice.modal.FraudCheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class FraudDetectionService {
    private final AccountServiceClient accountServiceClient;
    private final String VERIFICATION_REQUIRED_TOPIC="verification.required";
    private final String FRAUD_CHECK_CLEAN_RESULT_TOPIC="fraud.check.clean";
    private final KafkaTemplate<String,Object> kafkaTemplate;
    private final RedisTemplate<String,String> redisTemplate;
    @Value("${fraud.max-transaction-per-minute}")
    private  int MAX_TRANSACTION_PER_MINUTE;
    @Value("${fraud.suspicious_amount_multiplier}")
    private  BigDecimal SUSPICIOUS_AMOUNT_MULTIPLIER;
    @Value("${fraud.max_balance_percentage}")
    private BigDecimal MAX_BALANCE_PERCENTAGE;



    public void checkTransaction(TransactionInitiatedEvent transactionInitiatedEvent){
        String transactionId=transactionInitiatedEvent.transactionId();
        String senderAccountNumber=transactionInitiatedEvent.senderAccountNumber();
        BigDecimal amount=transactionInitiatedEvent.amount();

        //fetch real balance from account service
        BigDecimal senderBalance=accountServiceClient.getBalance(senderAccountNumber);

        log.info("Checking Transaction : {} account : {} amount : {} balance: {} ",
                transactionId,senderAccountNumber,amount,senderBalance);
        FraudCheckResult result=performFraudChecks(senderAccountNumber,amount,senderBalance);
        if(result.isFraud()){
            log.info("Suspicious Activity Detected -account : {} resaon: {} .Requesting OTP Verification",senderAccountNumber,result.getReason());

            VerificationRequiredEvent verificationRequiredEvent=new VerificationRequiredEvent(
                    transactionId,
                    senderAccountNumber,
                    amount,
                    result.getReason()
            );
            kafkaTemplate.send(VERIFICATION_REQUIRED_TOPIC,transactionId,verificationRequiredEvent);
        }
        else{
            //proceed with transaction
            log.info("Transaction Clean");

            kafkaTemplate.send(FRAUD_CHECK_CLEAN_RESULT_TOPIC,transactionId,transactionId);

        }
    }

    private FraudCheckResult performFraudChecks(String accountNumber,
                                                BigDecimal amount,
                                                BigDecimal senderBalance){
        //1.Velocity Check :Quick Transactions
        if(isVelocityExceeded(accountNumber)){
            return new FraudCheckResult(
                    true,"Too many transactions-limit excedded"
                    );
        }
        //2.Amount Limit Check:Huge Amount
        if(isAmountSuspicious(accountNumber,amount)){
            return new FraudCheckResult(true,"Unusual Transaction Amount");

        }
        //3.Balance Check
        if(senderBalance.compareTo(BigDecimal.ZERO)>0
        &&
        isBalanceCheckFailed(senderBalance,amount)
        ){
        return new FraudCheckResult(
                true,"Transaction Exceeds 90% of balance"
        );
        }
        return new FraudCheckResult(false,null);
    }
    private Boolean isVelocityExceeded(String accountNumber){
        String key="fraud:velocity"+accountNumber;
        Long count=redisTemplate.opsForValue().increment(key);
        if(count!=null && count==1){
            redisTemplate.expire(key,60, TimeUnit.SECONDS);
            return false;
        }
        log.info("Velocity Check -account :{} count:{} ",accountNumber,count);
        return count!=null && count>MAX_TRANSACTION_PER_MINUTE;
    }
    private Boolean isAmountSuspicious(String accountNumber,BigDecimal amount){
        String avgKey="fraud:avg_amount"+accountNumber;
        String avgAmountString=redisTemplate.opsForValue().get(avgKey);
        if(avgAmountString==null){
            redisTemplate.opsForValue().set(avgKey, amount.toString());
            return false;
        }
        BigDecimal avgAmount=new BigDecimal(avgAmountString);
        BigDecimal threshold=avgAmount.multiply(SUSPICIOUS_AMOUNT_MULTIPLIER);

        BigDecimal newAvgAmount=avgAmount.add(amount)
                .divide(BigDecimal.valueOf(2),2, RoundingMode.HALF_UP);

        redisTemplate.opsForValue().set(avgKey,newAvgAmount.toString());
        log.info("Amount Check- amount : {} threshold : {} suspicious:{}",amount,threshold
        ,amount.compareTo(threshold)>0);
        return amount.compareTo(threshold)>0;

    }
    private Boolean isBalanceCheckFailed(BigDecimal senderBalance,BigDecimal amount){

        BigDecimal maxAllowed =senderBalance.multiply(MAX_BALANCE_PERCENTAGE);
        log.info("Balance Check -amount : {} maxAllowed:{} suspicious:{}",amount,maxAllowed,amount.compareTo(maxAllowed)>0);
        return amount.compareTo(maxAllowed)>0;

    }

}

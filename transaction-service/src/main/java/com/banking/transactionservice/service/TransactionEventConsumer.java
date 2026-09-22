package com.banking.transactionservice.service;

import com.banking.events.OTPGeneratedEvent;
import com.banking.events.TransactionCleanEvent;
import com.banking.events.VerificationRequiredEvent;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
@Service
public class TransactionEventConsumer {
    private final TransactionRepository transactionRepository;
    private final RedisTemplate<String,String> redisTemplate;
    private static final long OTP_EXPIRY_MINUTES=5;
    private final KafkaTemplate<String,Object> kafkaTemplate;
    private final TransactionService transactionService;
    private static final String TRANSACTION_OTP_GENERATED_TOPIC="transaction.otp.generated";
    /*
        consume verification.required event created by fraud detection service
        generate otp
     */
    @KafkaListener(topics = "verification.required")
    public void consumeVerificationRequired(
            @Payload VerificationRequiredEvent verificationRequiredEvent
            ){
        try{
            String transactionId=verificationRequiredEvent.transactionId();
            String senderAccountNumber=verificationRequiredEvent.senderAccountNumber();
            String reason=verificationRequiredEvent.reason();
            BigDecimal amount=verificationRequiredEvent.amount();
            log.info("Verification Required - transaction: {} reason : {}",transactionId,reason);
            Transaction transaction=transactionRepository.findById(transactionId)
                    .orElseThrow(()->new RuntimeException("Transaction Not Found:"+transactionId));

            if(transaction.getTransactionStatus()!= TransactionStatus.PROCESSING){
                log.warn(" Transaction : {} not PROCESSING -skipping",transactionId);
                return ;
            }
            //generate six digit OTP
            String otp=String.format("%06d",(int)(Math.random()*900000)+100000);
            log.info("OTP Generated - transaction: {} otp : {}",transactionId,otp);
            //store OTP in redis
            //expires in 5 minutes
            String otpKey="verification:otp"+transactionId;
                    redisTemplate.opsForValue().set(otpKey,otp,OTP_EXPIRY_MINUTES, TimeUnit.MINUTES);


                    transaction.setTransactionStatus(TransactionStatus.PENDING_VERIFICATION);
                    transactionRepository.save(transaction);
                    log.info("OTP Generated for Transaction : {} expires in {} min",transactionId,OTP_EXPIRY_MINUTES);

                    //notify user

            OTPGeneratedEvent otpGeneratedEvent =new OTPGeneratedEvent(
                    transactionId,
                    senderAccountNumber,
                    reason,
                    otp,
                    amount
            );
            kafkaTemplate.send(TRANSACTION_OTP_GENERATED_TOPIC,transactionId,otpGeneratedEvent);


        }catch(Exception e){
            log.error("Error handling verification required event", e);
            throw e;
        }
    }



    @KafkaListener(topics = "fraud.check.clean")
    public void consumeFraudCheckClean(
            @Payload TransactionCleanEvent cleanEvent
    ){
        try{
            transactionService.processCleanResult(cleanEvent.transactionId());

        }catch(Exception e){
            log.error("Error processing fraud check clean result event", e);
            throw e;
        }
    }
}

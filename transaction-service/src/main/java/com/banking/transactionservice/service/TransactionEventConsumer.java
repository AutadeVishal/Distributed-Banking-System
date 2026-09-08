package com.banking.transactionservice.service;

import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class TransactionEventConsumer {
    private final TransactionRepository transactionRepository;
    private final RedisTemplate<String,String> redisTemplate;
    private static final long OTP_EXPIRY_MINUTES=5;
    private final KafkaTemplate<String,Object> kafkaTemplate;
    private static final String TRANSACTION_OTP_GENERATED_TOPIC="transaction.otp.generated";
    /*
        consume verification.required event created by fraud detection service
        generate otp
     */
    @KafkaListener(topics = "verification.required",groupId="transaction-service-group")
    public void consumeVerificationRequired(
            @Payload Map<String,Object> payload
    ){
        try{
            String transactionId=(String) payload.get("transactionId");
            String accountNumber=(String) payload.get("accountNumber");
            String reason=(String)payload.get("reason");
            String amount=(String) payload.get("amount");
            log.info("Verification Required - transaction: {} reason : {}",transactionId,reason);
            Transaction transaction=transactionRepository.findById(transactionId)
                    .orElseThrow(()->new RuntimeException("Transaction Not Found:"+transactionId));

            if(transaction.getTransactionStatus()!= TransactionStatus.PROCESSING){
                log.warn(" Transaction : {} not PROCESSING -skipping",transactionId);
                return ;
            }
            //generate six digit OTP
            String otp=String.format("%06d",(int)(Math.random()*900000)+100000);

            //store OTP in redix
            //expires in 5 minutes
            String otpKey="verification:otp"+transactionId;
                    redisTemplate.opsForValue().set(otpKey,otp,OTP_EXPIRY_MINUTES, TimeUnit.MINUTES);


                    transaction.setTransactionStatus(TransactionStatus.PENDING_VERIFICATION);
                    transactionRepository.save(transaction);
                    log.info("OTP Generated for Transaction : {} expires in {} min",transactionId,OTP_EXPIRY_MINUTES);

                    //notify user
            Map<String,Object> otpEvent=new HashMap<>();
            otpEvent.put("transactionId",transactionId);
            otpEvent.put("accountNumber",accountNumber);
            otpEvent.put("reason",reason);
            otpEvent.put("otp",otp);
            otpEvent.put("amount",amount);
            kafkaTemplate.send(TRANSACTION_OTP_GENERATED_TOPIC,transactionId,otpEvent);


        }catch(Exception e){
            log.error("Error handeling verification required :{}",e.getMessage());
        }
    }
}

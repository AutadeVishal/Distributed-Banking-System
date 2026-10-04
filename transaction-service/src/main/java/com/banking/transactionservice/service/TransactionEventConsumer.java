package com.banking.transactionservice.service;

import com.banking.events.OTPGeneratedEvent;
import com.banking.events.TransactionCleanEvent;
import com.banking.events.TransactionFailedEvent;
import com.banking.events.TransactionSettlementCompletedEvent;
import com.banking.events.VerificationRequiredEvent;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private final StringRedisTemplate redisTemplate;
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
            Long transactionId=verificationRequiredEvent.transactionId();
            String senderAccountNumber=verificationRequiredEvent.senderAccountNumber();
            String reason=verificationRequiredEvent.reason();
            BigDecimal amount=verificationRequiredEvent.amount();
            log.info(
                    "OTP FLOW - Verification required for transaction: {} amount: {} reason: {}",
                    transactionId,
                    amount,
                    reason
            );
            Transaction transaction=transactionRepository.findById(transactionId).get();

            if(transaction.getTransactionStatus() != TransactionStatus.PROCESSING
                    && transaction.getTransactionStatus() != TransactionStatus.PENDING_VERIFICATION){
                log.warn(
                        "OTP FLOW - Transaction {} is not awaiting verification; current status: {}",
                        transactionId,
                        transaction.getTransactionStatus()
                );
                return ;
            }
            //generate six digit OTP
            String otp=String.format("%06d",(int)(Math.random()*900000)+100000);
            log.info("OTP Generated - transaction: {} otp : {}",transactionId,otp);
            //store OTP in redis
            //expires in 5 minutes
            String otpKey="verification:otp"+transactionId;
                    redisTemplate.opsForValue().set(otpKey,otp,OTP_EXPIRY_MINUTES, TimeUnit.MINUTES);
                    redisTemplate.delete("verification:attempts:" + transactionId);


                    transaction.setTransactionStatus(TransactionStatus.PENDING_VERIFICATION);
                    transactionRepository.save(transaction);
                    log.info(
                            "OTP FLOW - OTP stored and transaction {} moved to PENDING_VERIFICATION; expires in {} min",
                            transactionId,
                            OTP_EXPIRY_MINUTES
                    );

                    //notify user

            OTPGeneratedEvent otpGeneratedEvent =new OTPGeneratedEvent(
                    transactionId,
                    senderAccountNumber,
                    reason,
                    otp,
                    amount
            );
            kafkaTemplate.send(TRANSACTION_OTP_GENERATED_TOPIC,transactionId.toString(),otpGeneratedEvent);
            log.info(
                    "OTP FLOW - OTP notification event published for transaction {}",
                    transactionId
            );


    }



    @KafkaListener(topics = "fraud.check.clean")
    public void consumeFraudCheckClean(
            @Payload TransactionCleanEvent cleanEvent
    ){
            log.info(
                    "SAGA - Fraud clean event received for transaction {}",
                    cleanEvent.transactionId()
            );
            transactionService.processCleanResult(cleanEvent.transactionId());
    }

    @KafkaListener(topics = "transaction.settlement.failed")
    public void consumeSettlementFailed(
            @Payload TransactionFailedEvent failedEvent
    ) {
        log.warn(
                "SAGA - Failure event received for transaction {} title: {} reason: {}",
                failedEvent.transactionId(),
                failedEvent.title(),
                failedEvent.reason()
        );
        transactionService.failTransaction(
                failedEvent.transactionId(),
                failedEvent.title(),
                failedEvent.reason(),
                true
        );
    }

    @KafkaListener(topics = "transaction.settlement.completed")
    public void consumeSettlementCompleted(
            @Payload TransactionSettlementCompletedEvent event
    ) {
        log.info(
                "SAGA - Settlement completed event received for transaction {}",
                event.transactionId()
        );
        transactionService.handleSettlementCompleted(event);
    }
}

package com.banking.transactionservice.service;

import com.banking.events.FraudDetectedEvent;
import com.banking.events.TransactionInitiatedEvent;
import com.banking.events.TransactionRefundedEvent;
import com.banking.transactionservice.client.AccountServiceClient;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;
import com.banking.events.TransactionCompletedEvent;
import com.banking.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;


@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionService {
    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private static final String TRANSACTION_INITIATED_TOPIC="transaction.initiated";
    private static final String TRANSACTION_COMPLETED_TOPIC="transaction.completed";
    private static final String TRANSACTION_REFUNDED_TOPIC="transaction.refunded";
    private static final String FRAUD_DETECTED_TOPIC="fraud.detected";
    private final KafkaTemplate<String,Object> kafkaTemplate;
    private final RedisTemplate<Object, Object> redisTemplate;

    /*
        Saga Step 1
        initiate transfer
        deduct from sender
        save transaction as processing
        publish event to kafka for fraud check
        returns
     */
    public TransactionResponse transfer(TransferRequest request){
        log.info("SAGA START - Transfer Account Number : {}->{} amount : {} ",request.getReceiverAccountNumber(),request.getSenderAccountNumber(),request.getAmount());

        //deduct from sender
        accountServiceClient.deductBalance(request.getSenderAccountNumber(),
                request.getAmount());

        //sve transaction as processing
        Transaction transaction=Transaction.builder()
                .senderAccountNumber(request.getSenderAccountNumber())
                .receiverAccountNumber(request.getReceiverAccountNumber())
                .amount(request.getAmount())
                .transactionType(TransactionType.TRANSFER)
                .transactionStatus(TransactionStatus.PROCESSING)
                .description(request.getDescription())
                .referenceNumber(UUID.randomUUID().toString())
                .build();
        Transaction savedTransaction=transactionRepository.save(transaction);
        log.info("Transaction saved as PROCESSING : {}",savedTransaction.getId());

        //publish event for fraud check
        //Saga Step 2:Publish For Fraud Check
        TransactionInitiatedEvent transactionInitiatedEvent=new TransactionInitiatedEvent(
          savedTransaction.getId(),
          savedTransaction.getSenderAccountNumber(),
          savedTransaction.getReceiverAccountNumber(),
          savedTransaction.getAmount(),
          savedTransaction.getDescription()
        );
        kafkaTemplate.send(TRANSACTION_INITIATED_TOPIC,savedTransaction.getId(),transactionInitiatedEvent);
        log.info("SAGA Step 2 :TransactionInitiatedEvent Published : {}",savedTransaction.getId());

        return mapToResponse(savedTransaction);

    }

    public TransactionResponse getTransaction(String transactionId){
        return mapToResponse(transactionRepository.findById(transactionId)
                .orElseThrow(
                        ()->new RuntimeException("Transaction Not Found")
                ));
    }

    public List<TransactionResponse> getTransactionHistory(String accountNumber){
        return transactionRepository.findBySenderAccountNumberOrderByCreatedAtDesc(accountNumber)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }
    private TransactionResponse mapToResponse(Transaction transaction){
        return TransactionResponse.builder()
                .id(transaction.getId())
                .senderAccountNumber(transaction.getSenderAccountNumber())
                .receiverAccountNumber(transaction.getReceiverAccountNumber())
                .amount(transaction.getAmount())
                .transactionType(transaction.getTransactionType())
                .transactionStatus(transaction.getTransactionStatus())
                .description(transaction.getDescription())
                .referenceNumber(transaction.getReferenceNumber())
                .failureReason(transaction.getFailureReason())
                .createdAt(transaction.getCreatedAt())
                .completedAt(transaction.getCompletedAt())
                .build();

    }
    public TransactionResponse verifyOTP(String transactionId,String otp){
        log.info("OTP Verification for the transaction:{}",transactionId);
        Transaction transaction=transactionRepository.findById(transactionId)
                .orElseThrow(()->new RuntimeException("Transaction : "+transactionId+"Not Found"));
        String otpKey="verification:otp"+transactionId;
        String storedOtp=(String)redisTemplate.opsForValue().get(otpKey);
        if(storedOtp==null){
            //OTP Expired
            log.warn("OTP Expired for Transaction : {}",transactionId);
            compensateTransaction(transaction,"OTP Expired - Transaction Cancelled and Amount Refunded");
            return mapToResponse(transaction);
        }
        if(!storedOtp.equals(otp)){
            log.warn("Wrong OTP - blocking account and refunding : {}",transactionId);
            redisTemplate.delete(otpKey);
            blockAccountAndCompensate(transaction,
                    "Wrong OTP entered- transaction cancelled" +
                            "account blocked for security");

        }
        //OTP Correct
        //complete the transaction
        log.info("OTP Verified- completing transaction : {}",transactionId);
        redisTemplate.delete(otpKey);
        completeTransaction(transaction);
        return mapToResponse(transaction);

    }

    private void compensateTransaction(Transaction transaction,String reason){
        log.warn("SAGA COMPENSATION-refunding:{} amount: {} ",transaction.getSenderAccountNumber(),transaction.getAmount());
        //credit money back to sender
        accountServiceClient.creditBalance(transaction.getSenderAccountNumber(),transaction.getAmount());
        transaction.setTransactionStatus(TransactionStatus.FLAGGED);
        transaction.setFailureReason(reason+
                "SAGA compensation executed ,amount refunded at "+
                LocalDateTime.now());
        transactionRepository.save(transaction);

        //publish refund event-Notification service
        TransactionRefundedEvent transactionRefundedEvent=new TransactionRefundedEvent(
                transaction.getId(),
                transaction.getSenderAccountNumber(),
                transaction.getAmount(),
                reason
        );
        kafkaTemplate.send(TRANSACTION_REFUNDED_TOPIC,transaction.getId(),transactionRefundedEvent);
        log.info("SAGA COMPENSATE COMPLETE-{} refunded to {}",
                transaction.getAmount(),transaction.getSenderAccountNumber());

    }

    private void blockAccountAndCompensate(Transaction transaction,String reason){
        //publish fraud.detected->account service will block account
        FraudDetectedEvent event=new FraudDetectedEvent(
                transaction.getId(),
                transaction.getSenderAccountNumber(),
                reason
        );
        kafkaTemplate.send(FRAUD_DETECTED_TOPIC,transaction.getSenderAccountNumber(),event);
        log.warn("fraud.detected published - account :{} will be blocked ",transaction.getSenderAccountNumber());

        //SAGA compensation
        compensateTransaction(transaction,reason);
    }

    private void completeTransaction(Transaction transaction){
        transaction.setTransactionStatus(TransactionStatus.COMPLETED);
        transaction.setCompletedAt(LocalDateTime.now());
        transactionRepository.save(transaction);

        TransactionCompletedEvent event=new TransactionCompletedEvent(
                transaction.getId(),
                transaction.getSenderAccountNumber(),
                transaction.getReceiverAccountNumber(),
                transaction.getAmount(),
                transaction.getDescription()
        );

        kafkaTemplate.send(TRANSACTION_COMPLETED_TOPIC,transaction.getId(),event);
        log.info("SAGA COMPLETE- transaction : {} completed ",transaction.getId());
    }
    public void processCleanResult(String transactionId){
        Transaction transaction=transactionRepository.findById(transactionId)
                .orElseThrow(()->new RuntimeException("Transaction : "+transactionId+"Not Found"));
        if(transaction.getTransactionStatus()!=TransactionStatus.PROCESSING){
            log.warn("Transaction {} not COMPLETED -skipping ",transactionId);
            return ;
        }
        completeTransaction(transaction);


    }

}

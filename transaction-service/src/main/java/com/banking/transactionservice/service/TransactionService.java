package com.banking.transactionservice.service;

import com.banking.events.FraudDetectedEvent;
import com.banking.events.TransactionInitiatedEvent;
import com.banking.events.TransactionRefundedEvent;
import com.banking.transactionservice.client.AccountServiceClient;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.IdempotencyRecord;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;
import com.banking.events.TransactionCompletedEvent;
import com.banking.transactionservice.repository.IdempotencyRepository;
import com.banking.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final IdempotencyRepository idempotencyRepository;

    @Transactional
    public TransactionResponse initiateTransaction(TransferRequest request, String idempotencyKey){
        log.info(
                "SAGA START - Transfer amount: {} from: {} to: {}",
                request.getAmount(),
                request.getSenderAccountNumber(),
                request.getReceiverAccountNumber()
        );

        Optional<IdempotencyRecord> existing =
                idempotencyRepository.findByIdempotencyKey(idempotencyKey);

        if (existing.isPresent()) {
            //if already exists then return existing
            //will not work for parallel or concurrent retries because both will skip this block after reading not existing
            Transaction transaction =
                    transactionRepository
                            .findById(existing.get().getTransactionId())
                            .orElseThrow(() ->
                                    new RuntimeException("Transaction not found"));

            log.info(
                    "Existing transaction found for idempotency key: {}",
                    idempotencyKey
            );

            return mapToResponse(transaction);
        }

        String referenceNumber = generateReferenceNumber();

        Transaction transaction = Transaction.builder()
                .referenceNumber(referenceNumber)
                .senderAccountNumber(request.getSenderAccountNumber())
                .receiverAccountNumber(request.getReceiverAccountNumber())
                .amount(request.getAmount())
                .description(request.getDescription())
                .transactionType(TransactionType.TRANSFER)
                .transactionStatus(TransactionStatus.PROCESSING)
                .build();

        //issues here:
        //completely different parallel transaction could generate same reference number so need to handle that using retry otherwise one need to fail
        //top of that negligible chance of two same transaction could have same reference number then one will fail here itself
        Transaction savedTransaction =
                transactionRepository.save(transaction);

        // issues here:
        //in two concurrent requests one transaction will be filled with idempotent record
        //other will fail(but it didn't actually fail) so need to handle that by returning existing transaction
        //don't know for now should i delete the duplicate transaction or not while returning existing one who got idempotent record
        IdempotencyRecord record = IdempotencyRecord.builder()
                .idempotencyKey(idempotencyKey)
                .transactionId(savedTransaction.getId())
                .build();

        idempotencyRepository.save(record);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {

                    @Override
                    public void afterCommit() {
                        startSaga(savedTransaction);
                    }
                }
        );
        log.info("Transaction saved as PROCESSING : {}",savedTransaction.getId());

        return mapToResponse(savedTransaction);

    }
    String generateReferenceNumber() {
        return  "TXN-" + UUID.randomUUID();
    }

    private void startSaga(Transaction transaction) {

        TransactionInitiatedEvent event =
                new TransactionInitiatedEvent(
                        transaction.getId(),
                        transaction.getSenderAccountNumber(),
                        transaction.getReceiverAccountNumber(),
                        transaction.getAmount(),
                        transaction.getDescription()
                );

        kafkaTemplate.send(
                TRANSACTION_INITIATED_TOPIC,
                transaction.getId().toString(),
                event
        );

        log.info(
                "SAGA - TransactionInitiatedEvent published: {}",
                transaction.getId()
        );
    }



    public List<TransactionResponse> getTransactionHistory(String accountNumber){
        return transactionRepository.findBySenderAccountNumberOrderByCreatedAtDesc(accountNumber)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }
    private TransactionResponse mapToResponse(Transaction transaction){
        return TransactionResponse.builder()
                .referenceNumber(transaction.getReferenceNumber())
                .senderAccountNumber(transaction.getSenderAccountNumber())
                .receiverAccountNumber(transaction.getReceiverAccountNumber())
                .amount(transaction.getAmount())
                .description(transaction.getDescription())
                .transactionType(transaction.getTransactionType())
                .transactionStatus(transaction.getTransactionStatus())
                .failureReason(transaction.getFailureReason())
                .createdAt(transaction.getCreatedAt())
                .completedAt(transaction.getCompletedAt())
                .build();

    }
    public TransactionResponse verifyOTP(String transactionId,String otp){
        log.info("OTP Verification for the transaction:{}",transactionId);
        Transaction transaction=transactionRepository.findById(Long.valueOf(transactionId))
                .orElseThrow(()->new RuntimeException("Transaction : "+transactionId+"Not Found"));
        String otpKey="verification:otp"+transactionId;
        String storedOtp=(String)redisTemplate.opsForValue().get(otpKey);
        if(storedOtp==null){
            //OTP Expired
            log.warn("OTP Expired for Transaction : {}",transactionId);
            transaction.setFailureReason("OTP Expired");
            transaction.setTransactionStatus(TransactionStatus.FAILED);
            transactionRepository.save(transaction);
            return mapToResponse(transaction);
        }
        if(!storedOtp.equals(otp)){
            log.warn("Wrong OTP for transaction : {}",transactionId);
            redisTemplate.delete(otpKey);
            transaction.setFailureReason("Wrong OTP");
            transaction.setTransactionStatus(TransactionStatus.FAILED);
            transactionRepository.save(transaction);
            return mapToResponse(transaction);
        }
        //OTP Correct
        //complete the transaction
        log.info("OTP Verified- completing transaction : {}",transactionId);
        redisTemplate.delete(otpKey);
        completeTransaction(transaction);
        return mapToResponse(transaction);

    }


    private void completeTransaction(Transaction transaction){
        //debit
       accountServiceClient.deductBalance(transaction.getSenderAccountNumber(),transaction.getAmount());

       //credit to receiver
        accountServiceClient.creditBalance(transaction.getReceiverAccountNumber(),transaction.getAmount());


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
        Transaction transaction=transactionRepository.findById(Long.valueOf(transactionId))
                .orElseThrow(()->new RuntimeException("Transaction : "+transactionId+"Not Found"));
        if(transaction.getTransactionStatus()!=TransactionStatus.PROCESSING){
            log.warn("Transaction {} not COMPLETED -skipping ",transactionId);
            return ;
        }
        completeTransaction(transaction);


    }

}

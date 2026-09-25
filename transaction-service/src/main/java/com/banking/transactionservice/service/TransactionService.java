package com.banking.transactionservice.service;

import com.banking.events.TransactionCompletedEvent;
import com.banking.events.TransactionInitiatedEvent;
import com.banking.transactionservice.client.AccountServiceClient;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.IdempotencyRecord;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<Object, Object> redisTemplate;
    private final IdempotencyRepository idempotencyRepository;

    private static final String TRANSACTION_INITIATED_TOPIC =
            "transaction.initiated";

    private static final String TRANSACTION_COMPLETED_TOPIC =
            "transaction.completed";

    private static final String FRAUD_DETECTED_TOPIC =
            "fraud.detected";

    @Transactional
    public TransactionResponse initiateTransaction(
            TransferRequest request,
            String idempotencyKey
    ) {

        log.info(
                "SAGA START - Transfer amount: {} from: {} to: {}",
                request.getAmount(),
                request.getSenderAccountNumber(),
                request.getReceiverAccountNumber()
        );

        IdempotencyRecord existingRecord =
                idempotencyRepository
                        .findByIdempotencyKey(idempotencyKey)
                        .orElse(null);

        if (existingRecord != null) {

            Transaction existingTransaction =
                    transactionRepository
                            .findById(existingRecord.getTransactionId())
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Transaction not found for idempotency key: "
                                                    + idempotencyKey
                                    )
                            );

            log.info(
                    "Returning existing transaction for idempotency key: {}",
                    idempotencyKey
            );

            return mapToResponse(existingTransaction);
        }


        Long referenceSequence =
                transactionRepository.getNextReferenceNumber();

        String referenceNumber =
                "TXN-" + String.format(
                        "%012d",
                        referenceSequence
                );


        Transaction transaction =
                Transaction.builder()
                        .referenceNumber(referenceNumber)
                        .senderAccountNumber(
                                request.getSenderAccountNumber()
                        )
                        .receiverAccountNumber(
                                request.getReceiverAccountNumber()
                        )
                        .amount(request.getAmount())
                        .description(request.getDescription())
                        .transactionType(TransactionType.TRANSFER)
                        .transactionStatus(
                                TransactionStatus.PROCESSING
                        )
                        .build();


        Transaction savedTransaction =
                transactionRepository.saveAndFlush(transaction);

        /*
          Atomic idempotency claim:
          1 -> this request won.
          0 -> another request with the same idempotency key won.
         */
        int claimed =
                idempotencyRepository.claim(
                        idempotencyKey,
                        savedTransaction.getId()
                );

        if (claimed == 0) {

            /*
              This transaction was created by the losing
              concurrent request, so remove it.
             */
            transactionRepository.delete(savedTransaction);

            IdempotencyRecord winningRecord =
                    idempotencyRepository
                            .findByIdempotencyKey(idempotencyKey)
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Idempotency record not found after claim conflict"
                                    )
                            );

            Transaction winningTransaction =
                    transactionRepository
                            .findById(
                                    winningRecord.getTransactionId()
                            )
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Winning transaction not found"
                                    )
                            );

            log.info(
                    "Concurrent duplicate request detected. " +
                            "Returning transaction: {}",
                    winningTransaction.getId()
            );

            return mapToResponse(winningTransaction);
        }

        /*
          Only the request that successfully claimed
          the idempotency key starts the Saga.
         */
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {

                    @Override
                    public void afterCommit() {
                        startSaga(savedTransaction);
                    }
                }
        );

        log.info(
                "Transaction saved as PROCESSING: {}",
                savedTransaction.getId()
        );

        return mapToResponse(savedTransaction);
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
                String.valueOf(transaction.getId()),
                event
        );

        log.info(
                "SAGA - TransactionInitiatedEvent published: {}",
                transaction.getId()
        );
    }

    public List<TransactionResponse> getTransactionHistory(
            String accountNumber
    ) {

        return transactionRepository
                .findBySenderAccountNumberOrderByCreatedAtDesc(
                        accountNumber
                )
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private TransactionResponse mapToResponse(
            Transaction transaction
    ) {

        return TransactionResponse.builder()
                .transactionId(
                        transaction.getId()
                )
                .referenceNumber(
                        transaction.getReferenceNumber()
                )
                .senderAccountNumber(
                        transaction.getSenderAccountNumber()
                )
                .receiverAccountNumber(
                        transaction.getReceiverAccountNumber()
                )
                .amount(transaction.getAmount())
                .description(transaction.getDescription())
                .transactionType(transaction.getTransactionType())
                .transactionStatus(transaction.getTransactionStatus())
                .failureReason(transaction.getFailureReason())
                .createdAt(transaction.getCreatedAt())
                .completedAt(transaction.getCompletedAt())
                .build();
    }

    public TransactionResponse verifyOTP(
            String transactionId,
            String otp
    ) {

        log.info(
                "OTP Verification for the transaction:{}",
                transactionId
        );

        Long id = Long.valueOf(transactionId);

        Transaction transaction =
                transactionRepository
                        .findById(id)
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Transaction : "
                                                + transactionId
                                                + " Not Found"
                                )
                        );

        String otpKey =
                "verification:otp" + transactionId;

        String storedOtp =
                (String) redisTemplate
                        .opsForValue()
                        .get(otpKey);

        if (storedOtp == null) {

            log.warn(
                    "OTP Expired for Transaction : {}",
                    transactionId
            );

            transaction.setFailureReason("OTP Expired");
            transaction.setTransactionStatus(
                    TransactionStatus.FAILED
            );

            transactionRepository.save(transaction);

            return mapToResponse(transaction);
        }

        if (!storedOtp.equals(otp)) {

            log.warn(
                    "Wrong OTP for transaction : {}",
                    transactionId
            );

            redisTemplate.delete(otpKey);

            transaction.setFailureReason("Wrong OTP");
            transaction.setTransactionStatus(
                    TransactionStatus.FAILED
            );

            transactionRepository.save(transaction);

            return mapToResponse(transaction);
        }

        log.info(
                "OTP Verified- completing transaction : {}",
                transactionId
        );

        redisTemplate.delete(otpKey);

        completeTransaction(transaction);

        return mapToResponse(transaction);
    }

    public void processCleanResult(Long transactionId) {

        Transaction transaction =
                transactionRepository
                        .findById(transactionId)
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Transaction : "
                                                + transactionId
                                                + " Not Found"
                                )
                        );

        completeTransaction(transaction);
    }

    private void completeTransaction(
            Transaction transaction
    ) {

        accountServiceClient.deductBalance(
                transaction.getSenderAccountNumber(),
                transaction.getAmount()
        );

        accountServiceClient.creditBalance(
                transaction.getReceiverAccountNumber(),
                transaction.getAmount()
        );

        transaction.setTransactionStatus(
                TransactionStatus.COMPLETED
        );

        transaction.setCompletedAt(
                LocalDateTime.now()
        );

        transactionRepository.save(transaction);

        TransactionCompletedEvent event =
                new TransactionCompletedEvent(
                        transaction.getId(),
                        transaction.getSenderAccountNumber(),
                        transaction.getReceiverAccountNumber(),
                        transaction.getAmount(),
                        transaction.getDescription()
                );

        kafkaTemplate.send(
                TRANSACTION_COMPLETED_TOPIC,
                String.valueOf(transaction.getId()),
                event
        );

        log.info(
                "SAGA COMPLETE - transaction : {} completed",
                transaction.getId()
        );
    }

    public TransactionResponse getTransaction(
            Long transactionId
    ) {


        Transaction transaction =
                transactionRepository
                        .findById(transactionId)
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Transaction : "
                                                + transactionId
                                                + " Not Found"
                                )
                        );

        return mapToResponse(transaction);
    }
}
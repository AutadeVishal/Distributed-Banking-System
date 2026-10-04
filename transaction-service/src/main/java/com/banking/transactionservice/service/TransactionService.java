package com.banking.transactionservice.service;

import com.banking.events.TransactionFailedEvent;
import com.banking.events.TransactionInitiatedEvent;
import com.banking.events.TransactionCompletedEvent;
import com.banking.events.TransactionSettlementCompletedEvent;
import com.banking.events.TransactionSettlementRequestedEvent;
import com.banking.events.VerificationRequiredEvent;
import com.banking.events.FraudDetectedEvent;
import com.banking.transactionservice.client.AccountServiceClient;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.IdempotencyRecord;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;
import com.banking.transactionservice.exception.TransactionNotFoundException;
import com.banking.transactionservice.exception.TransactionCreationException;
import com.banking.transactionservice.exception.TransactionStateException;
import com.banking.transactionservice.repository.IdempotencyRepository;
import com.banking.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import com.github.f4b6a3.uuid.UuidCreator;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private final IdempotencyRepository idempotencyRepository;
    private static final String TRANSACTION_INITIATED_TOPIC =
            "transaction.initiated";

    private static final String FRAUD_DETECTED_TOPIC =
            "fraud.detected";

    private static final String TRANSACTION_SETTLEMENT_REQUESTED_TOPIC =
            "transaction.settlement.requested";

    private static final String TRANSACTION_FAILED_TOPIC =
            "notification.transaction.failed";
    private static final String TRANSACTION_COMPLETED_TOPIC =
            "notification.transaction.completed";
    private static final int MAX_OTP_ATTEMPTS = 3;
    private static final long OTP_EXPIRY_MINUTES = 5;

    @Transactional
    public TransactionResponse initiateTransaction(
            TransferRequest request,
            String idempotencyKey
    ) {
        if (request.getSenderAccountNumber()
                .equals(request.getReceiverAccountNumber())) {
            throw new TransactionCreationException("Sender and receiver accounts must be different");
        }

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
                                    new TransactionCreationException(
                                            "No Transaction found for transaction Id: "
                                                    + existingRecord.getTransactionId()
                                    )
                            );

            log.info(
                    "Returning existing transaction for idempotency key: {}",
                    idempotencyKey
            );

            return mapToResponse(existingTransaction);
        }


        Transaction transaction =
                Transaction.builder()
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


        Transaction savedTransaction = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                transaction.setReferenceNumber(generateReferenceNumber());
                savedTransaction = transactionRepository.saveAndFlush(transaction);
                break;
            } catch (DataIntegrityViolationException e) {
                if (!isConstraintViolation(e, "uk_transaction_reference_number")
                        || attempt == 2) {
                    throw new TransactionCreationException(
                            "Unable to create a unique transaction reference"
                    );
                }
            }
        }

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
                            .orElseThrow(()->
                                    new TransactionCreationException("Idempotency Key  found Idempotency Key:"+idempotencyKey));

            Transaction winningTransaction =
                    transactionRepository
                            .findById(
                                    winningRecord.getTransactionId()
                            )
                            .orElseThrow(()->
                                  new  TransactionCreationException("Transaction not found for winning record having Idempotency Key:"+idempotencyKey));

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
        Transaction transactionForSaga = savedTransaction;
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {

                    @Override
                    public void afterCommit() {
                        startSaga(transactionForSaga);
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
        ).whenComplete((result, error) -> {
            if (error != null) {
                log.error("Could not publish transaction {} initiation; it remains PROCESSING",
                        transaction.getId(), error);
            }
        });

        log.info(
                "SAGA - TransactionInitiatedEvent queued: {}",
                transaction.getId()
        );
    }

    public List<TransactionResponse> getTransactionHistory(
            String accountNumber
    ) {

        return transactionRepository
                .findByEitherAccountOrderByCreatedAtDesc(
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

    @Transactional
    public TransactionResponse verifyOTP(
            Long transactionId,
            String otp
    ) {

        log.info(
                "OTP Verification for the transaction:{}",
                transactionId
        );

        Boolean lockAcquired = redisTemplate.opsForValue().setIfAbsent(
                "verification:lock:" + transactionId, "1", 30, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(lockAcquired)) {
            return mapToResponse(findTransaction(transactionId));
        }
        try {
        Transaction transaction =
                transactionRepository.findByIdForUpdate(transactionId).orElseThrow(
                        () -> new TransactionNotFoundException("Transaction not found"));

        if (transaction.getTransactionStatus() != TransactionStatus.PENDING_VERIFICATION) {
            log.info(
                    "Skipping stale OTP verification for transaction {} in status {}",
                    transactionId,
                    transaction.getTransactionStatus()
            );
            return mapToResponse(transaction);
        }

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

            return mapToResponse(transaction);
        }

        if (!storedOtp.equals(otp)) {

            log.warn(
                    "Wrong OTP for transaction : {}",
                    transactionId
            );

            String attemptsKey = "verification:attempts:" + transactionId;
            Long attempts = redisTemplate.opsForValue().increment(attemptsKey);
            redisTemplate.expire(
                    attemptsKey,
                    OTP_EXPIRY_MINUTES,
                    TimeUnit.MINUTES
            );

            if (attempts != null && attempts >= MAX_OTP_ATTEMPTS) {
                String reason = "Account locked after 3 incorrect OTP submissions";
                accountServiceClient.lockAccount(
                        transaction.getSenderAccountNumber()
                );

                redisTemplate.delete(otpKey);
                redisTemplate.delete(attemptsKey);
                publishFraudDetected(transaction, reason);
                failTransaction(
                        transaction.getId(),
                        "Transaction Rejected",
                        reason,
                        true
                );
            } else {
                log.info(
                        "Incorrect OTP attempt {} of {} for transaction {}",
                        attempts,
                        MAX_OTP_ATTEMPTS,
                        transactionId
                );
            }

            return mapToResponse(transaction);
        }

        log.info(
                "OTP Verified- completing transaction : {}",
                transactionId
        );

        redisTemplate.delete(otpKey);

        transaction.setTransactionStatus(TransactionStatus.PROCESSING);
        transactionRepository.save(transaction);
        publishSettlementRequested(transaction);

        return mapToResponse(transaction);
        } finally {
            redisTemplate.delete("verification:lock:" + transactionId);
        }
    }

    public TransactionResponse requestNewOtp(Long transactionId) {
        Transaction transaction = findTransaction(transactionId);

        if (transaction.getTransactionStatus() != TransactionStatus.PENDING_VERIFICATION) {
            throw new TransactionStateException(
                    "A new OTP can only be requested for a transaction pending verification"
            );
        }

        VerificationRequiredEvent event = new VerificationRequiredEvent(
                transaction.getId(),
                transaction.getSenderAccountNumber(),
                transaction.getAmount(),
                "OTP renewal requested"
        );
        kafkaTemplate.send(
                "verification.required",
                String.valueOf(transaction.getId()),
                event
        );
        log.info(
                "OTP FLOW - Renewal requested for transaction {}; reason: {}",
                transaction.getId(),
                event.reason()
        );
        return mapToResponse(transaction);
    }

    public void processCleanResult(Long transactionId) {

        Transaction transaction =
                transactionRepository
                        .findById(transactionId)
                        .orElseThrow(()->
                                new TransactionCreationException("Transaction not found :"+transactionId));

        log.info(
                "SAGA - Fraud check passed for transaction {}; requesting settlement",
                transactionId
        );

        if (transaction.getTransactionStatus() == TransactionStatus.COMPLETED
                || transaction.getTransactionStatus() == TransactionStatus.FAILED
                ) {
            log.info(
                    "Transaction {} already settled or failed; ignoring clean result",
                    transactionId
            );
            return;
        }

        publishSettlementRequested(transaction);
    }

    public void failTransaction(
            Long transactionId,
            String title,
            String reason,
            boolean publishNotification
    ) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(
                        "Transaction not found"
                ));
        if (transaction.getTransactionStatus() == TransactionStatus.COMPLETED
                || transaction.getTransactionStatus() == TransactionStatus.FAILED) {
            log.info(
                    "SAGA - Ignoring failure for transaction {} already in status {}",
                    transactionId,
                    transaction.getTransactionStatus()
            );
            return;
        }

        String failureReason = title + ": " + reason;
        transaction.setFailureReason(
                failureReason
        );
        transaction.setTransactionStatus(TransactionStatus.FAILED);
        transactionRepository.save(transaction);
        log.warn(
                "SAGA - Transaction {} failed; amount: {} from: {} to: {}; reason: {}",
                transaction.getId(),
                transaction.getAmount(),
                transaction.getSenderAccountNumber(),
                transaction.getReceiverAccountNumber(),
                failureReason
        );

        if (publishNotification) {
            publishFailureNotification(transaction, title, reason);
        }
    }

    private void publishFailureNotification(Transaction transaction, String title, String reason) {
        TransactionFailedEvent event = new TransactionFailedEvent(
                transaction.getId(),
                transaction.getSenderAccountNumber(),
                transaction.getReceiverAccountNumber(),
                transaction.getAmount(),
                title,
                reason
        );
        kafkaTemplate.send(
                TRANSACTION_FAILED_TOPIC,
                String.valueOf(transaction.getId()),
                event
        ).whenComplete((result, error) -> {
            if (error != null) {
                log.error("Could not publish failure notification for transaction {}",
                        transaction.getId(), error);
            }
        });
        log.warn(
                "SAGA - Failure event queued for transaction {} reason: {}",
                transaction.getId(),
                reason
        );
    }

    public void handleSettlementCompleted(
            TransactionSettlementCompletedEvent event
    ) {
        Transaction transaction = transactionRepository.findById(event.transactionId())
                .orElseThrow();

        log.info(
                "SAGA - Settlement completed event received for transaction {}",
                event.transactionId()
        );

        if (transaction.getTransactionStatus() == TransactionStatus.COMPLETED
                || transaction.getTransactionStatus() == TransactionStatus.FAILED) {
            log.info("Ignoring stale settlement result for transaction {}", event.transactionId());
            return;
        }

        transaction.setTransactionStatus(
                TransactionStatus.COMPLETED
        );

        transaction.setCompletedAt(
                LocalDateTime.now()
        );

        transactionRepository.save(transaction);

        kafkaTemplate.send(TRANSACTION_COMPLETED_TOPIC,
                String.valueOf(transaction.getId()),
                new TransactionCompletedEvent(
                        transaction.getId(),
                        transaction.getSenderAccountNumber(),
                        transaction.getReceiverAccountNumber(),
                        transaction.getAmount(),
                        transaction.getDescription()
                ));

        log.info(
                "SAGA COMPLETE - transaction : {} completed",
                transaction.getId()
        );
    }

    private void publishSettlementRequested(Transaction transaction) {
        TransactionSettlementRequestedEvent event =
                new TransactionSettlementRequestedEvent(
                        transaction.getId(),
                        transaction.getSenderAccountNumber(),
                        transaction.getReceiverAccountNumber(),
                        transaction.getAmount()
                );

        kafkaTemplate.send(
                TRANSACTION_SETTLEMENT_REQUESTED_TOPIC,
                String.valueOf(transaction.getId()),
                event
        ).whenComplete((result, error) -> {
            if (error != null) {
                log.error("Could not publish settlement request for transaction {}",
                        transaction.getId(), error);
            }
        });
        log.info(
                "SAGA - Settlement request queued for transaction {}; amount: {} from: {} to: {}",
                transaction.getId(),
                transaction.getAmount(),
                transaction.getSenderAccountNumber(),
                transaction.getReceiverAccountNumber()
        );
    }

    private void publishFraudDetected(Transaction transaction, String reason) {
        FraudDetectedEvent event = new FraudDetectedEvent(
                transaction.getId(),
                transaction.getSenderAccountNumber(),
                reason
        );
        kafkaTemplate.send(
                FRAUD_DETECTED_TOPIC,
                String.valueOf(transaction.getId()),
                event
        );
    }

    public TransactionResponse getTransaction(
            Long transactionId
    ) {
        Transaction transaction = findTransaction(transactionId);

        return mapToResponse(transaction);
    }

    private Transaction findTransaction(Long transactionId) {
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(
                        "Transaction not found"
                ));
    }

    private UUID generateReferenceNumber() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    private boolean isConstraintViolation(
            DataIntegrityViolationException exception,
            String constraintName
    ) {
        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof PSQLException psqlException) {
                ServerErrorMessage error = psqlException.getServerErrorMessage();
                return error != null && constraintName.equals(error.getConstraint());
            }
            cause = cause.getCause();
        }

        return false;
    }
}
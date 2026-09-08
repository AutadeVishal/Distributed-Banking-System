package com.banking.transactionservice.service;

import com.banking.transactionservice.client.AccountServiceClient;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;
import com.banking.transactionservice.event.TransactionInitiatedEvent;
import com.banking.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
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
    private final KafkaTemplate<String,Object> kafkaTemplate;

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
        TransactionInitiatedEvent event=new TransactionInitiatedEvent(
          savedTransaction.getId(),
          savedTransaction.getSenderAccountNumber(),
          savedTransaction.getReceiverAccountNumber(),
          savedTransaction.getAmount(),
          savedTransaction.getDescription()
        );
        kafkaTemplate.send(TRANSACTION_INITIATED_TOPIC,savedTransaction.getId(),event);
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

}

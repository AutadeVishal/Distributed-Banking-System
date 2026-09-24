package com.banking.transactionservice.repository;

import com.banking.transactionservice.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TransactionRepository
        extends JpaRepository<Transaction, Long> {

    List<Transaction> findBySenderAccountNumberOrderByCreatedAtDesc(
            String accountNumber
    );

    @Query(
            value = "SELECT nextval('transaction_reference_seq')",
            nativeQuery = true
    )
    Long getNextReferenceNumber();
}
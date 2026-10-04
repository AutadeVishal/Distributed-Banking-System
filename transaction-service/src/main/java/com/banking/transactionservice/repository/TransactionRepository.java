package com.banking.transactionservice.repository;

import com.banking.transactionservice.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TransactionRepository
        extends JpaRepository<Transaction, Long> {

    List<Transaction> findBySenderAccountNumberOrderByCreatedAtDesc(
            String accountNumber
    );

    @Query("select t from Transaction t where t.senderAccountNumber = :account or t.receiverAccountNumber = :account order by t.createdAt desc")
    List<Transaction> findByEitherAccountOrderByCreatedAtDesc(@Param("account") String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transaction t where t.id = :id")
    java.util.Optional<Transaction> findByIdForUpdate(@Param("id") Long id);

}
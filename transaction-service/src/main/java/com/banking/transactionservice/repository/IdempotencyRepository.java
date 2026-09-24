package com.banking.transactionservice.repository;

import com.banking.transactionservice.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface IdempotencyRepository
        extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKey(
            String idempotencyKey
    );

    @Modifying
    @Query(value = """
            INSERT INTO idempotency_records
                (idempotency_key, transaction_id, created_at)
            VALUES
                (:idempotencyKey, :transactionId, CURRENT_TIMESTAMP)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int claim(
            @Param("idempotencyKey") String idempotencyKey,
            @Param("transactionId") Long transactionId
    );
}
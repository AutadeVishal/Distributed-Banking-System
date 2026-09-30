package com.banking.accountservice.repository;

import com.banking.accountservice.entity.AccountIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountIdempotencyRepository extends JpaRepository<AccountIdempotencyRecord, Long> {

    Optional<AccountIdempotencyRecord> findByIdempotencyKey(
            String idempotencyKey
    );

    @Modifying
    @Query(value = """
            INSERT INTO account_idempotency
                (idempotency_key, account_id, created_at)
            VALUES
                (:idempotencyKey, :accountId, CURRENT_TIMESTAMP)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int claim(
            @Param("idempotencyKey") String idempotencyKey,
            @Param("accountId") Long accountId
    );
}

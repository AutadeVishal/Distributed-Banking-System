package com.banking.accountservice.repository;

import com.banking.accountservice.entity.SettlementRecord;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SettlementRecordRepository extends JpaRepository<SettlementRecord, Long> {
    @Modifying
    @Query(value = "insert into settlement_records(transaction_id, claimed_at) values (:transactionId, now()) on conflict (transaction_id) do nothing", nativeQuery = true)
    int claim(@Param("transactionId") Long transactionId);
}

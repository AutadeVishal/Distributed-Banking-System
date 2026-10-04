package com.banking.accountservice.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_records", uniqueConstraints = @UniqueConstraint(
        name = "uk_settlement_transaction", columnNames = "transaction_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SettlementRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private Long transactionId;
    @Column(nullable = false)
    private LocalDateTime claimedAt;
}

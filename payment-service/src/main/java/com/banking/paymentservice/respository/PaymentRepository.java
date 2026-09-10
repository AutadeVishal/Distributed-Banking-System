package com.banking.paymentservice.respository;

import com.banking.paymentservice.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment,String> {
    public Optional<Payment> findByRazorpayOrderId(String orderId);
}

package com.banking.transactionservice.controller;

import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transaction")
@Slf4j
@RequiredArgsConstructor
public class TransactionController {
    private final TransactionService transactionService;

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey
            )
    {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.initiateTransaction(request, idempotencyKey));
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionResponse> getTransaction(
            @PathVariable Long transactionId
    ){
      return ResponseEntity.ok(transactionService.getTransaction(transactionId));
    }

    @GetMapping("/account/history/{accountNumber}")
    public ResponseEntity<List<TransactionResponse>> getTransactionHistory(
            @PathVariable String accountNumber
    ){
        return ResponseEntity.ok(transactionService.getTransactionHistory(accountNumber));
    }

    @PostMapping("/{transactionId}/verify-otp")
    public ResponseEntity<TransactionResponse> verifyOTP(
            @PathVariable String transactionId,
            @RequestParam("otp") String otp
    )
    {
        log.info("OTP and Verification Request - Transaction Id : {} ",transactionId);
        return ResponseEntity.ok(transactionService.verifyOTP(transactionId,otp));
    }

    @PostMapping("/{transactionId}/request-otp")
    public ResponseEntity<TransactionResponse> requestOtp(
            @PathVariable Long transactionId
    ) {
        return ResponseEntity.ok(transactionService.requestNewOtp(transactionId));
    }

}

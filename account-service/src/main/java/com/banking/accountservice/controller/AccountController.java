package com.banking.accountservice.controller;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/account")
@Slf4j
@RequiredArgsConstructor
public class AccountController {
    private final AccountService accountService;

    @PostMapping()
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request){
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(request));
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponse> getAccount(
            @PathVariable String accountNumber
    ){
        return ResponseEntity.ok(accountService.getAccount(accountNumber));
    }

    @GetMapping("/{accountNumber}/balance")
    public ResponseEntity<BigDecimal> getBalance(
            @PathVariable String accountNumber
    ){
        BigDecimal balance=accountService.getBalance(accountNumber);
        return ResponseEntity.ok(balance);
    }

    @PutMapping("/{accountNumber}/lock")
    public ResponseEntity<String> lockAccount(
            @PathVariable String accountNumber
    ){
        accountService.lockAccount(accountNumber);
        return ResponseEntity.ok("Account Number locked Successfully");
    }

    @PutMapping("/{accountNumber}/unlock")
    public ResponseEntity<String> unLockAccount(
            @PathVariable String accountNumber
    ){
        accountService.unlockAccount(accountNumber);
        return ResponseEntity.ok("Account Number Unlocked Successfully");
    }

    @PutMapping("/transfer")
    public ResponseEntity<String> transfer(
            @RequestParam("senderAccountNumber") String senderAccountNumber,
            @RequestParam("receiverAccountNumber") String receiverAccountNumber,
            @RequestParam("amount") BigDecimal amount
    ) {
        accountService.transfer(senderAccountNumber, receiverAccountNumber, amount);
        return ResponseEntity.ok("Transfer completed successfully");
    }
}

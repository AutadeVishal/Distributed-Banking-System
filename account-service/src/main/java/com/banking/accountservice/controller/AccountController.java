package com.banking.accountservice.controller;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.repository.AccountRepository;
import com.banking.accountservice.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/accounts")
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

    @PutMapping("/{accountNumber}/block")
    public ResponseEntity<String> blockAccount(
            @PathVariable String accountNumber
    ){
        accountService.blockAccount(accountNumber);
        return ResponseEntity.ok("Account Number Blocked Successfully");
    }

    /*
        Saga step 1 :
        Transaction Service calling when transfer initiated
        deduct balance
     */
    public ResponseEntity<String> deductBalance(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount
    )
    {
        accountService.deductBalance(accountNumber,amount);
        return ResponseEntity.ok("Balance Deducted Succesfully");
    }

    /*
        Saga Step 4: compensating transaction endpoint
        Transaction Service Calling
        1.fraud detected->refund sender (undo step 1)
        2.transaction completed -> credit receiver
     */
    @PutMapping("/{accountNumber}/credit")
    public ResponseEntity<String> creditBalance(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount
    ){
        accountService.creditBalance(accountNumber,amount);
        return ResponseEntity.ok("Balance Credited Successfully");
    }
}

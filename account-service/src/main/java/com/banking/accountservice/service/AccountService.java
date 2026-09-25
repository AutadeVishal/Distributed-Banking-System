package com.banking.accountservice.service;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountService {
    private final AccountRepository accountRepository;

    public AccountResponse createAccount(CreateAccountRequest request){
        log.info("Creating Account for : {}",request.getEmail());
        if(accountRepository.existsAccountByEmail((request.getEmail()))){
            throw new RuntimeException("Account Already Exists for Email :"+request.getEmail());
        }
        String accountNumber=generateAccountNumber();
        Account account=Account.builder()
                .accountHolderName(request.getAccountHolderName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .accountType(request.getAccountType())
                .balance(request.getInitialDeposit())
                .accountStatus(AccountStatus.ACTIVE)
                .accountNumber(accountNumber)
                .dailyTransactionLimit(
                    request.getAccountType()== AccountType.SAVINGS
                    ? new BigDecimal(100000)
                    : new BigDecimal(500000)
                        )
                .build();
        Account savedAccount=accountRepository.save(account);
        log.info("Account Created {}",savedAccount.getAccountNumber());
        return mapToResponce(savedAccount);
    }

    public AccountResponse getAccount(String accountNumber){
        Account account=accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new RuntimeException("Account Not Found"));
        return mapToResponce(account);
    }

    public BigDecimal getBalance(String accountNumber){
        Account account=accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new RuntimeException("Account Not Found"));
        return account.getBalance();
    }
    /*
    called by fraud detection service in kafka
     */
    public void lockAccount(String accountNumber){
        log.info("Blocking Account {}",accountNumber);
        Account account=accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new RuntimeException("Account Not Found"));
        account.setAccountStatus(AccountStatus.BLOCKED);
        accountRepository.save(account);
        log.info("Account Blocked:{}",account.getAccountNumber());
    }
    public void unlockAccount(String accountNumber){
        log.info("Unlocking Account {}",accountNumber);
        Account account=accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new RuntimeException("Account Not Found"));
        account.setAccountStatus(AccountStatus.ACTIVE);
        accountRepository.save(account);
        log.info("Account unlocked:{}",account.getAccountNumber());
    }

    /*
    deduct balance from sender
    called by transaction service
     */
    @Transactional
    public void transfer(String senderAccountNumber,
                         String receiverAccountNumber,
                         BigDecimal amount) {
        if (senderAccountNumber.equals(receiverAccountNumber)) {
            throw new IllegalArgumentException("Sender and receiver accounts must be different");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }

        Account sender = accountRepository.findByAccountNumber(senderAccountNumber)
                .orElseThrow(() -> new RuntimeException("Account Not Found"));
        Account receiver = accountRepository.findByAccountNumber(receiverAccountNumber)
                .orElseThrow(() -> new RuntimeException("Account Not Found"));

        if (sender.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new RuntimeException("Sender account is not active");
        }
        if (receiver.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new RuntimeException("Receiver account is not active");
        }
        if (sender.getBalance().compareTo(amount) < 0) {
            throw new RuntimeException("Insufficient Balance");
        }

        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));
        accountRepository.save(sender);
        accountRepository.save(receiver);
        log.info("Transferred {} from {} to {}", amount, senderAccountNumber, receiverAccountNumber);
    }

    private AccountResponse mapToResponce(Account account){
      return AccountResponse.builder()
        .id(account.getId())
        .accountNumber(account.getAccountNumber())
        .accountHolderName(account.getAccountHolderName())
        .email(account.getEmail())
        .phone(account.getPhone())
        .accountType(account.getAccountType())
        .accountStatus(account.getAccountStatus())
        .balance(account.getBalance())
        .dailyTransactionLimit(account.getDailyTransactionLimit())
        .createdAt(account.getCreatedAt())
        .updatedAt(account.getUpdatedAt())
                .build();
    }

    // PostgreSQL sequence guarantees uniqueness under concurrent account creation.
    private String generateAccountNumber(){
        return String.format("%012d", accountRepository.getNextAccountNumber());
    }


}

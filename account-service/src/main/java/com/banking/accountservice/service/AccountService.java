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
import java.security.SecureRandom;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountService {
    private final AccountRepository accountRepository;
    private static final SecureRandom secureRandom=new SecureRandom();

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
    public  void deductBalance(String accountNumber,BigDecimal amount){
        log.info("Deducting Balance {} from Account {} ",amount,accountNumber);
        Account account=accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new RuntimeException("Account Not Found"));
        if(account.getAccountStatus()!=AccountStatus.ACTIVE){
            throw new RuntimeException("Account : "+ account.getAccountNumber() +" is not active to deduct amount");
        }
        if(account.getBalance().compareTo(amount)<0){
            throw new RuntimeException("Insufficient Balance");
        }
        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);
        log.info("Account Balance Updated to {}",account.getBalance());

    }

    /*
        called by transaction service via kafka
     */
    @Transactional
    public void creditBalance(String accountNumber,BigDecimal amount){
        log.info("Crediting  Balance {} to Account {} ",amount,accountNumber);
        Account account=accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new RuntimeException("Account Not Found"));
        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);
        log.info("Account Balance Updated to {}",account.getBalance());
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
                .build();
    }

    //generating uique 12 digit account number
    private String generateAccountNumber(){
        String accountNumber;
        do{
            Long number=secureRandom.nextLong(1_000_000_000_000L);
            accountNumber=String.format("%012d",number);
        }while(accountRepository.existsByAccountNumber(accountNumber));
        return accountNumber;
    }


}

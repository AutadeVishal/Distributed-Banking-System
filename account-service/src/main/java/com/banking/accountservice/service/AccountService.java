package com.banking.accountservice.service;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountIdempotencyRecord;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.exception.*;
import com.banking.accountservice.repository.AccountIdempotencyRepository;
import com.banking.accountservice.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountService {
    private final AccountRepository accountRepository;
    private final AccountIdempotencyRepository idempotencyRepository;

    @Transactional
    public AccountResponse createAccount(
            CreateAccountRequest request,
            String idempotencyKey
    ){
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }

        log.info("Creating account for: {}", request.getEmail());

        AccountIdempotencyRecord existingRecord =
                idempotencyRepository.findByIdempotencyKey(idempotencyKey)
                        .orElse(null);

        if (existingRecord != null) {
            Account existingAccount = accountRepository.findById(existingRecord.getAccountId())
                    .orElseThrow(() -> new AccountCreationException(
                            "Account not found for idempotency key: " + idempotencyKey
                    ));
            log.info("Returning existing account for idempotency key: {}", idempotencyKey);
            return mapToResponse(existingAccount);
        }

        if (accountRepository.existsAccountByEmail(request.getEmail())) {
            throw new AccountAlreadyExistsException(
                    "An account already exists for this email"
            );
        }

        for(int attempt=0;attempt<3;attempt++){
            try{
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
                Account saved = accountRepository.saveAndFlush(account);

                int claimed = idempotencyRepository.claim(idempotencyKey, saved.getId());
                if (claimed == 0) {
                    accountRepository.delete(saved);

                    AccountIdempotencyRecord winningRecord =
                            idempotencyRepository.findByIdempotencyKey(idempotencyKey)
                                    .orElseThrow(() -> new AccountCreationException(
                                            "Idempotency record not found after claim conflict"
                                    ));
                    Account winningAccount = accountRepository.findById(winningRecord.getAccountId())
                            .orElseThrow(() -> new AccountCreationException(
                                    "Winning account not found for idempotency key: "
                                            + idempotencyKey
                            ));
                    log.info("Concurrent duplicate request detected. Returning account: {}",
                            winningAccount.getAccountNumber());
                    return mapToResponse(winningAccount);
                }

                log.info("Account Created {}",saved.getAccountNumber());
                return mapToResponse(saved);

            }
            catch (DataIntegrityViolationException e) {
                if (isAccountNumberCollision(e)) {
                    log.warn(
                            "Account number collision. Retrying attempt {}/3",
                            attempt + 1
                    );
                    continue;
                }

                throw e;
            }
        }
        throw new AccountCreationException(
                "Unable to create account. Please try again."
        );
    }

    public AccountResponse getAccount(String accountNumber){
        Account account=accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() ->
                        new AccountNotFoundException("Account not found"));
        return mapToResponse(account);
    }

    public BigDecimal getBalance(String accountNumber){
        Account account=accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() ->
                        new AccountNotFoundException("Account not found"));
        return account.getBalance();
    }
    /*
    called by fraud detection service in kafka
     */
    public void lockAccount(String accountNumber){
        log.info("Blocking Account {}",accountNumber);
        Account account=accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new AccountNotFoundException("Account not found"));
        account.setAccountStatus(AccountStatus.BLOCKED);
        accountRepository.save(account);
        log.info("Account Blocked:{}",account.getAccountNumber());
    }
    public void unlockAccount(String accountNumber){
        log.info("Unlocking Account {}",accountNumber);
        Account account=accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(()->new AccountNotFoundException("Account not found"));
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
            throw new InvalidTransferException("Sender and receiver accounts must be different");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidTransferException("Transfer amount must be positive");
        }

        Account sender = accountRepository.findByAccountNumber(senderAccountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found"));
        Account receiver = accountRepository.findByAccountNumber(receiverAccountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found"));

        if (sender.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new AccountInactiveException("Sender account is not active");
        }
        if (receiver.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new AccountInactiveException("Receiver account is not active");
        }
        if (sender.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException("Insufficient balance");
        }

        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));
        accountRepository.save(sender);
        accountRepository.save(receiver);
        log.info("Transferred {} from {} to {}", amount, senderAccountNumber, receiverAccountNumber);
    }

    private AccountResponse mapToResponse(Account account){
      return AccountResponse.builder()
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

    private final SecureRandom random = new SecureRandom();

    private String generateAccountNumber() {
        return String.format(
                "%012d",
                random.nextLong(1_000_000_000_000L)
        );
    }

    private boolean isAccountNumberCollision(DataIntegrityViolationException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof PSQLException psql) {
                return "uk_account_number".equals(
                        psql.getServerErrorMessage().getConstraint()
                );
            }
            cause = cause.getCause();
        }

        return false;
    }

}

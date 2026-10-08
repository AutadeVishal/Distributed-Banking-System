package com.banking.accountservice.service;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountIdempotencyRecord;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.exception.AccountNotFoundException;
import com.banking.accountservice.exception.AccountAlreadyExistsException;
import com.banking.accountservice.exception.AccountBlockedException;
import com.banking.accountservice.exception.AccountCreationException;
import com.banking.accountservice.exception.InsufficientBalanceException;
import com.banking.accountservice.exception.AccountServiceException;
import com.banking.accountservice.repository.AccountIdempotencyRepository;
import com.banking.accountservice.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;

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
        log.info("Creating account for: {}", request.getEmail());

        AccountIdempotencyRecord existingRecord =
                idempotencyRepository.findByIdempotencyKey(idempotencyKey)
                        .orElse(null);

        if (existingRecord != null) {
            Account existingAccount = accountRepository.findById(existingRecord.getAccountId())
                    .orElseThrow(() -> new AccountCreationException(
                            "No Account Found for Existing Idempotency Key: "
                                    + existingRecord.getIdempotencyKey()
                    ));
            log.info("Returning existing account for idempotency key: {}", idempotencyKey);
            return mapToResponse(existingAccount);
        }

        if (
                accountRepository.existsAccountByEmail(request.getEmail())
        ) {
            throw new AccountAlreadyExistsException(
                    "An account with the same email  already exists"
            );
        }
        if (
                accountRepository.existsByPhone(request.getPhone())
        ) {
            throw new AccountAlreadyExistsException(
                    "An account with the same  phone already exists"
            );
        }



        Account account = Account.builder()
                .accountHolderName(request.getAccountHolderName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .accountType(request.getAccountType())
                .balance(request.getInitialDeposit())
                .accountStatus(AccountStatus.ACTIVE)
                .dailyTransactionLimit(
                        request.getAccountType()== AccountType.SAVINGS
                                ? new BigDecimal(100000)
                                : new BigDecimal(500000)
                )
                .dailyTransactionSpent(BigDecimal.ZERO)
                .dailyTransactionDate(LocalDate.now())
                .build();
        Account saved = null;
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    account.setAccountNumber(generateAccountNumber());
                    saved = accountRepository.saveAndFlush(account);
                    break;
                } catch (DataIntegrityViolationException e) {
                    if (!isConstraintViolation(e, "uk_account_number")
                            || attempt == 2) {
                        throw e;
                    }
                }
            }

            int claimed = idempotencyRepository.claim(idempotencyKey, saved.getId());
            if (claimed == 0) {
                accountRepository.delete(saved);

                AccountIdempotencyRecord winningRecord =
                        idempotencyRepository.findByIdempotencyKey(idempotencyKey)
                                .orElseThrow(()->new AccountCreationException(
                                        "Not able to find winning Idempotency Key :"+idempotencyKey
                                ));
                Account winningAccount = accountRepository.findById(winningRecord.getAccountId()).get();
                log.info("Concurrent duplicate request detected. Returning account: {}",
                        winningAccount.getAccountNumber());
                return mapToResponse(winningAccount);
            }

            log.info("Account Created {}", saved.getAccountNumber());
            return mapToResponse(saved);
        }
        catch(DataIntegrityViolationException e) {
            if (isConstraintViolation(e, "uk_account_number")) {
                throw new AccountCreationException(
                        "Unable to generate a unique account number for email"+request.getEmail()

                );
            }
            throw new AccountCreationException(
                    "Unable to create the account"

            );
        }

    }

    public AccountResponse getAccount(String accountNumber){
        Account account=accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(
                        "Account not found"
                ));
        return mapToResponse(account);
    }

    public BigDecimal getBalance(String accountNumber){
        Account account=accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(
                        "Account not found"
                ));
        return account.getBalance();
    }
    /*
    called by fraud detection service in kafka
     */
    public void lockAccount(String accountNumber){
        log.info("Blocking Account {}",accountNumber);
        Account account=accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(
                        "Account not found"
                ));
        account.setAccountStatus(AccountStatus.BLOCKED);
        accountRepository.save(account);
        log.info("Account Blocked:{}",account.getAccountNumber());
    }
    public void unlockAccount(String accountNumber){
        log.info("Unlocking Account {}",accountNumber);
        Account account=accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(
                        "Account not found"
                ));
        account.setAccountStatus(AccountStatus.ACTIVE);
        accountRepository.save(account);
        log.info("Account unlocked:{}",account.getAccountNumber());
    }

    /*
    deduct balance from sender
    called by transaction service
     */
    /*
    No Roll Back for account service Exceptoin class
     */
    @Transactional(
            timeout = 10,
            noRollbackFor = AccountServiceException.class
    )
    public void transfer(
            String senderAccountNumber,
            String receiverAccountNumber,
            BigDecimal amount
    ) {
        if (senderAccountNumber.equals(receiverAccountNumber)) {
            throw new AccountServiceException("Sender and receiver accounts must be different");
        }
        log.info(
                "Settlement validation started - amount: {} from: {} to: {}",
                amount,
                senderAccountNumber,
                receiverAccountNumber
        );
        // Lock accounts in a consistent order to prevent deadlocks
        String firstAccountNumber;
        String secondAccountNumber;

        if (senderAccountNumber.compareTo(receiverAccountNumber) < 0) {
            firstAccountNumber = senderAccountNumber;
            secondAccountNumber = receiverAccountNumber;
        } else {
            firstAccountNumber = receiverAccountNumber;
            secondAccountNumber = senderAccountNumber;
        }

        // Acquire lock one by one
        Account first = accountRepository
                .findByAccountNumberForUpdate(firstAccountNumber)
                .orElseThrow(()->new AccountNotFoundException("Account not found"));

        Account second = accountRepository
                .findByAccountNumberForUpdate(secondAccountNumber)
                .orElseThrow(() -> new AccountNotFoundException(
                        "Receiver account not found"
                ));

        // Restore sender/receiver roles
        Account sender;
        Account receiver;

        if (senderAccountNumber.equals(first.getAccountNumber())) {
            sender = first;
            receiver = second;
        } else {
            sender = second;
            receiver = first;
        }

        if (sender.getAccountStatus() != AccountStatus.ACTIVE) {
            log.warn("Settlement rejected - sender account {} is not active", senderAccountNumber);
            throw new AccountBlockedException("Sender account is blocked");
        }
        if (receiver.getAccountStatus() != AccountStatus.ACTIVE) {
            log.warn("Settlement rejected - receiver account {} is not active", receiverAccountNumber);
            throw new AccountBlockedException("Receiver account is blocked");
        }
        if (sender.getBalance().compareTo(amount) < 0) {
            log.warn(
                    "Settlement rejected - insufficient balance for sender {}; requested: {}, available: {}",
                    senderAccountNumber,
                    amount,
                    sender.getBalance()
            );
            throw new InsufficientBalanceException("Insufficient balance");
        }
        LocalDate today = LocalDate.now();
        if (!today.equals(sender.getDailyTransactionDate())) {
            sender.setDailyTransactionDate(today);
            sender.setDailyTransactionSpent(BigDecimal.ZERO);
        }
        if (sender.getDailyTransactionSpent().add(amount)
                .compareTo(sender.getDailyTransactionLimit()) > 0) {
            throw new AccountServiceException("Daily transaction limit exceeded");
        }

        // Both accounts are already locked so now only update them
        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));
        sender.setDailyTransactionSpent(sender.getDailyTransactionSpent().add(amount));


        log.info(
                "Transferred {} from {} to {}",
                amount,
                senderAccountNumber,
                receiverAccountNumber
        );
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
        StringBuilder accountNumber = new StringBuilder(20);
        accountNumber.append(1 + random.nextInt(9));
        for (int i = 0; i < 19; i++) {
            accountNumber.append(random.nextInt(10));
        }
        return accountNumber.toString();
    }

    private boolean isConstraintViolation(
            DataIntegrityViolationException exception,
            String constraintName
    ) {
        Throwable cause = exception;

        while (cause != null) {

            if (cause instanceof PSQLException psqlException) {

                ServerErrorMessage error =
                        psqlException.getServerErrorMessage();

                return error != null
                        && constraintName.equals(error.getConstraint());
            }

            cause = cause.getCause();
        }

        return false;
    }

}

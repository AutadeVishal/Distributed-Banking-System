package com.banking.transactionservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;

@FeignClient(name="account-service",url="${account.service.url}")
public interface AccountServiceClient {
    @PutMapping("/api/v1/account/{accountNumber}/lock")
    String lockAccount(@PathVariable String accountNumber);
}

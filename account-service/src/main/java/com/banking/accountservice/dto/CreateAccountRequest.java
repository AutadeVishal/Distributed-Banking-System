package com.banking.accountservice.dto;

import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.entity.AccountType;
import jakarta.persistence.Column;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateAccountRequest {
    @NotBlank(message = "Account Holder Name is Required")
    private String AccountHolderName;

    @NotBlank(message="Email is Required")
    @Email(message="Invalid Email Format")
    private String email;

    @NotBlank(message="Phone Number is Required")
    private String phone;

    @NotNull(message="Account Type is Required")
    private AccountType accountType;

    @NotNull(message="Initial Deposit is Required")
    @Positive(message="Initial Deposit Must be Positive")
    private BigDecimal initialDeposit;

}

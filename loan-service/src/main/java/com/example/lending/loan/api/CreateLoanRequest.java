package com.example.lending.loan.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreateLoanRequest(
        @NotBlank String customerId,
        @NotNull BigDecimal amount,
        @NotBlank String productType,
        Boolean simulateKycFail,
        Boolean simulateCreditReject,
        Boolean simulateDisbursementFail
) {}

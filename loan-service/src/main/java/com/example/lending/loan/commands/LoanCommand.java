package com.example.lending.loan.commands;

import java.math.BigDecimal;
import java.time.Instant;

public record LoanCommand(
        String commandId,
        String sagaId,
        String commandType,
        String aggregateId,
        String customerId,
        BigDecimal amount,
        String productType,
        Boolean simulateKycFail,
        Boolean simulateCreditReject,
        Boolean simulateDisbursementFail,
        Instant createdAt
) {}

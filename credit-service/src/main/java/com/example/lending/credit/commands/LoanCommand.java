package com.example.lending.credit.commands;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.math.BigDecimal;
import java.time.Instant;

public record LoanCommand(
        @JsonAlias("command_id") String commandId,
        @JsonAlias("saga_id") String sagaId,
        @JsonAlias("command_type") String commandType,
        @JsonAlias("aggregate_id") String aggregateId,
        @JsonAlias("customer_id") String customerId,
        BigDecimal amount,
        @JsonAlias("product_type") String productType,
        @JsonAlias("simulate_kyc_fail") Boolean simulateKycFail,
        @JsonAlias("simulate_credit_reject") Boolean simulateCreditReject,
        @JsonAlias("simulate_disbursement_fail") Boolean simulateDisbursementFail,
        @JsonAlias("created_at") Instant createdAt
) {}

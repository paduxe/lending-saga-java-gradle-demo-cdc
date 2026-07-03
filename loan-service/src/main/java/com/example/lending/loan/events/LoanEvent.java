package com.example.lending.loan.events;

import java.math.BigDecimal;
import java.time.Instant;

public record LoanEvent(
        String eventId,
        String sagaId,
        String eventType,
        String aggregateId,
        String customerId,
        BigDecimal amount,
        String productType,
        Boolean simulateKycFail,
        Boolean simulateCreditReject,
        Boolean simulateDisbursementFail,
        Instant createdAt
) {}

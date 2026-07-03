package com.example.lending.loan.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SagaResultEvent(
    String sagaId,
    String loanApplicationId,
    String finalStatus,
    String errorReason,
    Instant completedAt
) {}

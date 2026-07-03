package com.example.lending.orchestrator.events;

import java.time.Instant;

public record SagaResultEvent(
    String sagaId,
    String loanApplicationId,
    String finalStatus,
    String errorReason,
    Instant completedAt
) {}

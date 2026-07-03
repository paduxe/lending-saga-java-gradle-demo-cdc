package com.example.lending.orchestrator.messaging;

import java.time.Instant;

public record DlqEvent(
        String originalMessageId,
        String originalTopic,
        String payload,
        String errorMessage,
        Instant failedAt
) {}

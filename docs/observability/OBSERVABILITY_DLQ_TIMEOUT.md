# Observability, DLQ, and Saga Timeout

## 1. Why the analysis is correct
In a distributed saga, tracing a single `sagaId` across multiple services is difficult without:
- distributed tracing
- consistent correlation IDs
- structured logs
- metrics around saga progress and failures

Also:
- poison messages can block retries forever if there is no DLQ strategy
- a saga can remain stuck if one command is sent and no completion/failure event is returned

## 2. What was added in this version
- OpenTelemetry dependency placeholders and trace/correlation propagation skeleton
- DLQ consumer/producer skeleton using `saga-dlq`
- saga timeout checker in orchestrator
- extra saga fields for timeout tracking
- README notes and sample flow

## 3. Recommended operational metrics
- `saga_running_total`
- `saga_failed_total`
- `saga_compensating_total`
- `saga_timeout_total`
- `outbox_pending_total`
- `dlq_total`

## 4. Recommended log fields
- `traceId`
- `sagaId`
- `loanApplicationId`
- `eventType`
- `commandType`
- `serviceName`

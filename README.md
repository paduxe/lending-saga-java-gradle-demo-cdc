# Lending Saga Orchestrator Demo (Java + Spring Boot + Gradle)

Services:
- loan-service
- loan-orchestrator-service
- kyc-service
- credit-service
- disbursement-service

Run:
```bash
docker compose up --build
```

Create loan:
```bash
curl -X POST http://localhost:8081/api/loans   -H "Content-Type: application/json"   -d '{
    "customerId":"CUST-100",
    "amount":1000,
    "productType":"PERSONAL_LOAN",
    "simulateKycFail":false,
    "simulateCreditReject":false,
    "simulateDisbursementFail":false
  }'
```

Check saga:
```bash
curl http://localhost:8080/api/sagas/<sagaId>
```


## Ordering notes
This version adds:
- Kafka key = `aggregateId` on every publish
- outbox polling sorted by `createdAt, id`
- producer idempotence + `max.in.flight=1`
- orchestrator state machine validation
- optimistic locking on `loan_saga`

These changes improve ordering **per loanApplicationId** and prevent out-of-order business transitions.


## Compensation flow added
- When `DisbursementFailed` is received by the orchestrator, saga status moves to `COMPENSATING`.
- Orchestrator publishes `CancelCreditCommand`.
- `credit-service` handles the command, writes a `CANCELLED` credit record, and publishes `CreditCancelled`.
- Orchestrator receives `CreditCancelled` and marks the saga as `FAILED` with compensation completed.


## Added in this version

### Observability
- Added tracing/logging skeleton with `traceId` and `sagaId`
- Added docs for OpenTelemetry / tracing fields / metrics

### DLQ
- Added `saga-dlq` concept and `DlqPublisher` skeleton
- Intended for poison messages that repeatedly fail processing

### Saga Timeout
- Added timeout fields to `LoanSagaEntity`
- Added `SagaTimeoutScheduler` to mark stuck RUNNING sagas as FAILED/TIMEOUT

### Notes
This is still a demo skeleton. It shows where to plug in:
- OpenTelemetry + Jaeger/Zipkin
- DLQ publishing on permanent failures
- timeout handling / alerting


## CDC / Debezium Outbox mode
This version moves the demo toward **CDC-based outbox publishing**.

### Why
Polling the `outbox_event` table every few seconds is simple, but at larger scale it can create unnecessary DB load and delivery lag.

### What to use now
- Default mode: `lending.outbox.mode=cdc`
- Kafka Connect service: `kafka-connect`
- Connector configs: `debezium/*-outbox-connector.json`

### Start
```bash
docker compose up -d --build
bash scripts/register-debezium-connectors.sh
```

### Optional fallback
If you want to run the old polling publisher for local testing, change:
```yaml
lending:
  outbox:
    mode: polling
```
in the service `application.yml`.

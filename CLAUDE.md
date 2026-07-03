# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

### Build & Run

```bash
# Khởi chạy toàn bộ stack (Kafka, PostgreSQL, Redis, services, monitoring...)
docker compose up --build

# Chỉ build không chạy
./gradlew build

# Build một subproject cụ thể
./gradlew :loan-service:build
./gradlew :loan-orchestrator-service:build

# Đăng ký Debezium connectors sau khi stack đã up
bash scripts/register-debezium-connectors.sh
```

### Tests

```bash
# Chạy tất cả tests
./gradlew test

# Chạy tests của một subproject
./gradlew :loan-service:test

# Chạy một test class cụ thể
./gradlew :loan-service:test --tests "com.example.lending.loan.service.LoanQueryServiceTest"

# Chạy một test method cụ thể
./gradlew :loan-service:test --tests "com.example.lending.loan.service.LoanQueryServiceTest.testFindLoansByCustomerCached_Success"
```

### Smoke test

```bash
# Tạo đơn vay
curl -X POST http://localhost:8081/api/loans \
  -H "Content-Type: application/json" \
  -d '{"customerId":"CUST-100","amount":1000,"productType":"PERSONAL_LOAN","simulateKycFail":false,"simulateCreditReject":false,"simulateDisbursementFail":false}'

# Kiểm tra trạng thái saga
curl http://localhost:8080/api/sagas/<sagaId>
```

## Architecture

### Gradle Multi-module

Root project `lending-saga-demo` gồm 5 submodules: `loan-service`, `loan-orchestrator-service`, `kyc-service`, `credit-service`, `disbursement-service`. Tất cả dùng chung dependencies được khai báo trong `build.gradle` root (Spring Boot 3.3, Java 21).

### Kafka Topics

| Topic | Producer | Consumer |
|---|---|---|
| `saga-events` | loan-service (outbox) | loan-orchestrator-service |
| `saga-commands` | loan-orchestrator-service (outbox) | kyc-service, credit-service, disbursement-service |

Messages dùng **Apache Avro** (schemas tại `schemas/LoanEvent.avsc`, `schemas/LoanCommand.avsc`) với Confluent Schema Registry.

### Transactional Outbox + CDC

Mỗi service **không publish Kafka trực tiếp**. Thay vào đó:
1. Ghi sự kiện vào bảng `outbox_event` trong **cùng một DB transaction** với business data.
2. Debezium đọc PostgreSQL WAL (`wal_level=logical`) → đẩy lên Kafka thông qua EventRouter transform.

Config connector tại `debezium/*-outbox-connector.json`. Mode có thể chuyển sang `polling` qua `lending.outbox.mode=polling` trong `application.yml`.

### Saga State Machine (loan-orchestrator-service)

`SagaStateMachineConfig` định nghĩa state transitions:

```
INIT → KYC_REQUESTED → CREDIT_REQUESTED → DISBURSEMENT_REQUESTED → COMPLETED
              ↓               ↓                      ↓
            FAILED          FAILED           COMPENSATING_CREDIT → FAILED
```

`LoanSagaEventListener` là Kafka consumer chính, map event type → `SagaEvent` → gọi `StateMachine.sendEvent()` → lưu state mới → publish command tiếp theo vào outbox.

### Write-Through Cache (SagaCacheService)

Saga state được persist theo chiến lược **write-through**: ghi DB trước, sau đó ghi Redis (TTL 60 phút). Read ưu tiên Redis, fallback DB. Khi saga kết thúc (`COMPLETED`/`FAILED`), Redis key bị xóa (`flushToDb()`).

Idempotency của Kafka consumer dùng Redis SETNX (`idempotency:saga-events:{eventId}`, TTL 24h) thay vì query DB để tăng tốc.

### Database Sharding (loan-service)

`loan-service` route tới 2 PostgreSQL shards bằng `loanId.hashCode() % 2`. `ShardRoutingDataSource` extends `AbstractRoutingDataSource`, đọc shard từ `ShardContext` (ThreadLocal). Mỗi shard có read replica, `@ReadOnlyRoute` AOP tự redirect SELECT queries.

### gRPC Fast-fail

Trước khi tạo Saga, `loan-service` gọi **đồng bộ gRPC** tới `kyc-service` (port 9092) và `credit-service` (port 9093) để reject ngay các request rõ ràng không hợp lệ (blacklist, credit không đủ điều kiện) — tránh tốn tài nguyên tạo saga.

### Resilience Patterns

- **Anti-Fraud Engine** (EasyRules): chạy trước mọi DB write, reject ngay nếu fraud score ≥ 100.
- **Rate Limiter** (Resilience4j): 10,000 req/s trên endpoint `POST /api/loans`.
- **Circuit Breaker**: bao quanh Kafka consumer của orchestrator và gRPC clients.
- **Saga Timeout**: `SagaTimeoutScheduler` scan sagas quá hạn (`deadline_at`) theo `lending.saga.timeout-seconds` (default 60s).
- **Reconciliation Job**: `SagaReconciliationJob` chạy mỗi 30 phút, re-publish kết quả các saga đã xong để đảm bảo eventual consistency với loan-service.
- **ShedLock**: dùng cho cả 2 scheduled jobs trên để tránh chạy trùng khi có nhiều orchestrator instances.

### Security

- **OAuth2/JWT** qua Keycloak (`:8086`), tất cả services cấu hình là resource server.
- **`CryptoConverter`** (JPA AttributeConverter) mã hóa PII fields trong DB.
- **`AuditLogAspect`** (AOP) ghi audit log mọi mutation trên loan-service và orchestrator.
- **Istio mTLS** + AuthorizationPolicy được khai báo trong `k8s/istio/`.

### Kafka Listener Concurrency

Mỗi service cấu hình `listener.concurrency: 30` để xử lý song song 30 partitions. Kafka producer dùng idempotence + `max.in.flight=1`, Kafka key = `aggregateId` để đảm bảo ordering per loan.

### Observability Stack

| Thành phần | URL |
|---|---|
| Prometheus | `:9090` |
| Grafana | `:3000` (admin/admin) |
| Jaeger (traces) | `:16686` |
| Kibana | `:5601` |
| Kafka Connect API | `:8085` |
| Schema Registry | `:8081` |

Business metrics: `saga_started_total`, `saga_failed_total`, `loans_disbursed_total_count`, `loans_disbursed_amount_total`, `loans_fraud_blocked_total`.

### Camunda BPM (Skeleton)

`BpmSagaResultListener` và `KycExternalWorker` trong `loan-orchestrator-service` là skeleton chưa được tích hợp hoàn chỉnh — chúng tồn tại để minh họa cách thay thế Spring State Machine bằng BPMN engine nếu cần.

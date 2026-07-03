package com.example.lending.orchestrator.events;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.math.BigDecimal;
import java.time.Instant;

public record LoanEvent(
        @JsonAlias("event_id") String eventId, // ID duy nhất của mỗi sự kiện. Dùng để chống trùng lặp (Idempotency check)
        @JsonAlias("saga_id") String sagaId, // ID định danh toàn bộ quy trình vay (Correlation ID)
        @JsonAlias("event_type") String eventType, // Tên loại sự kiện (ví dụ: LoanApplicationCreated)
        @JsonAlias("aggregate_id") String aggregateId, // ID của thực thể Đơn vay (Business ID). Dùng làm Khóa phân vùng Kafka (Partition Key)
        @JsonAlias("customer_id") String customerId,
        BigDecimal amount,
        @JsonAlias("product_type") String productType,
        @JsonAlias("simulate_kyc_fail") Boolean simulateKycFail,
        @JsonAlias("simulate_credit_reject") Boolean simulateCreditReject,
        @JsonAlias("simulate_disbursement_fail") Boolean simulateDisbursementFail,
        @JsonAlias("created_at") Instant createdAt
) {}

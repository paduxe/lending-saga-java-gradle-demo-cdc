package com.example.lending.orchestrator.commands;

import java.math.BigDecimal;
import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonAlias;

public record LoanCommand(
        @JsonAlias("command_id") String commandId, // ID duy nhất của mỗi lệnh xử lý (Idempotency Key)
        @JsonAlias("saga_id") String sagaId, // ID định danh toàn bộ quy trình vay (Correlation ID)
        @JsonAlias("command_type") String commandType, // Tên loại lệnh (ví dụ: VerifyKycCommand)
        @JsonAlias("aggregate_id") String aggregateId, // ID của thực thể Đơn vay (Business ID)
        @JsonAlias("customer_id") String customerId,
        BigDecimal amount,
        @JsonAlias("product_type") String productType,
        Boolean simulateKycFail,
        Boolean simulateCreditReject,
        Boolean simulateDisbursementFail,
        Instant createdAt
) {}

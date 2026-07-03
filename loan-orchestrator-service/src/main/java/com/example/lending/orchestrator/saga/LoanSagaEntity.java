package com.example.lending.orchestrator.saga;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "loan_saga")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoanSagaEntity {
    @Id
    @jakarta.persistence.Column(name = "saga_id")
    private String sagaId; // ID định danh duy nhất của toàn bộ luồng nghiệp vụ (Saga Transaction ID)

    private String loanApplicationId; // ID của thực thể Đơn vay (Business Aggregate ID). Dùng để truy vấn thông tin gốc và làm khóa phân vùng (Kafka Partition Key)
    private String currentStep;
    private String sagaStatus;
    private String errorReason;
    private Instant updatedAt;

    private Instant lastCommandAt;
    private Instant deadlineAt;
    private Boolean timeoutAlerted;

    @Version
    private Long version;
}

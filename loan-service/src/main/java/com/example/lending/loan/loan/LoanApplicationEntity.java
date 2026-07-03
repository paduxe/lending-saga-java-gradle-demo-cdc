package com.example.lending.loan.loan;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "loan_application")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LoanApplicationEntity {
    @Id
    private String loanApplicationId; // ID định danh của Đơn vay (Business Aggregate ID). Trở thành aggregateId trong Kafka.
    private String sagaId; // ID duy nhất của toàn bộ quy trình vay (Transaction ID). Dùng để kết nối đơn vay này với Saga.
    @jakarta.persistence.Convert(converter = com.example.lending.loan.security.CryptoConverter.class)
    private String customerId;
    private BigDecimal amount;
    private String productType;
    private String status;
    private Boolean simulateKycFail;
    private Boolean simulateCreditReject;
    private Boolean simulateDisbursementFail;
    private Instant createdAt;
}

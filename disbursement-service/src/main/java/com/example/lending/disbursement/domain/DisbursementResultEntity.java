package com.example.lending.disbursement.domain;

import jakarta.persistence.*;
import lombok.*;
import com.example.lending.disbursement.security.CryptoConverter;

import java.time.Instant;

@Entity
@Table(name = "disbursement_result")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DisbursementResultEntity {
    @Id
    private String id; // ID lưu trữ nội bộ của bảng disbursement_result
    private String sagaId; // ID định danh toàn bộ quy trình vay (Correlation ID).
    private String loanApplicationId; // ID của Đơn vay (Business ID)
    
    @Convert(converter = CryptoConverter.class)
    private String customerId;
    private String status;
    private Instant processedAt;
}

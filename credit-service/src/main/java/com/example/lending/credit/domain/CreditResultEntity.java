package com.example.lending.credit.domain;

import jakarta.persistence.*;
import lombok.*;
import com.example.lending.credit.security.CryptoConverter;

import java.time.Instant;

@Entity
@Table(name = "credit_result")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CreditResultEntity {
    @Id
    private String id; // ID lưu trữ nội bộ của bảng credit_result
    private String sagaId; // ID định danh toàn bộ quy trình vay (Correlation ID).
    private String loanApplicationId; // ID của Đơn vay (Business ID)
    
    @Convert(converter = CryptoConverter.class)
    private String customerId;
    private String status;
    private Instant processedAt;
}

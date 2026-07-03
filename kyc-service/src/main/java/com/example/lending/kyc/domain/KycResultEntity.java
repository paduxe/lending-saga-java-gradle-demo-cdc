package com.example.lending.kyc.domain;

import jakarta.persistence.*;
import lombok.*;
import com.example.lending.kyc.security.CryptoConverter;

import java.time.Instant;

@Entity
@Table(name = "kyc_result")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class KycResultEntity {
    @Id
    private String id; // ID lưu trữ nội bộ của bảng kyc_result
    private String sagaId; // ID của toàn bộ quy trình vay (Correlation ID). Dùng để Orchestrator biết kết quả này thuộc về Saga nào.
    private String loanApplicationId; // ID của Đơn vay (Business ID)
    
    @Convert(converter = CryptoConverter.class)
    private String customerId;
    private String status;
    private Instant processedAt;
}

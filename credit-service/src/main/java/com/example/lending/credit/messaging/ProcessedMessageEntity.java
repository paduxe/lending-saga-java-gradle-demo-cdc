package com.example.lending.credit.messaging;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "processed_message")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProcessedMessageEntity {
    @Id
    private String messageId;
    private Instant processedAt;
}

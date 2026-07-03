package com.example.lending.disbursement.outbox;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "outbox_event")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OutboxEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String eventId;
    @Column(nullable = false)
    private String topicName;
    @Column(nullable = false)
    private String eventType;
    @Column(nullable = false)
    private String aggregateId;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;
    @Column(nullable = false)
    private String status;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant publishedAt;
}

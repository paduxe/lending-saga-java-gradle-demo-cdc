package com.example.lending.kyc.messaging;

import com.example.lending.kyc.commands.LoanCommand;
import com.example.lending.kyc.domain.KycResultEntity;
import com.example.lending.kyc.domain.KycResultRepository;
import com.example.lending.kyc.events.LoanEvent;
import com.example.lending.kyc.outbox.OutboxEventEntity;
import com.example.lending.kyc.outbox.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;

@Component
@RequiredArgsConstructor
@Slf4j
public class KycCommandListener {
    private final KycResultRepository repository;
    private final ProcessedMessageRepository processedRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @KafkaListener(topics = "saga-commands", groupId = "kyc-service", containerFactory = "loanCommandKafkaListenerContainerFactory")
    @Transactional
    public void onCommand(java.util.List<org.apache.avro.generic.GenericRecord> records) {
        for (org.apache.avro.generic.GenericRecord record : records) {
            try {
                LoanCommand command = mapAvroToCommand(record);
                if (command.commandId() == null) continue;
                if (!"VerifyKycCommand".equals(command.commandType())) continue;

                // GIẢI QUYẾT HẠN CHẾ #15: Idempotency bằng Redis SETNX (Siêu tốc, chống Double Processing)
                // FIX: Duplicate command → trả về kết quả đã xử lý trước đó (không chỉ skip)
                String redisKey = "idempotency:kyc:" + command.commandId();
                Boolean isNew = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, "PROCESSING", Duration.ofMinutes(10));
                if (Boolean.FALSE.equals(isNew) || processedRepository.existsById(command.commandId())) {
                    log.warn("⚠️ [IDEMPOTENCY] Command {} đã xử lý — replay kết quả cũ cho Orchestrator", command.commandId());
                    replayPreviousResult(command);
                    continue;
                }

                String kycStatus = Boolean.TRUE.equals(command.simulateKycFail()) ? "FAILED" : "SUCCESS";

                repository.save(KycResultEntity.builder()
                        .id(UUID.randomUUID().toString())
                        .sagaId(command.sagaId())
                        .loanApplicationId(command.aggregateId())
                        .customerId(command.customerId())
                        .status(kycStatus)
                        .processedAt(Instant.now())
                        .build());

                String resultType = "FAILED".equals(kycStatus) ? "KycFailed" : "KycPassed";
                publishOutboxEvent(command, resultType);

                processedRepository.save(ProcessedMessageEntity.builder()
                        .messageId(command.commandId())
                        .processedAt(Instant.now())
                        .build());
            } catch (Exception e) {
                throw new RuntimeException("Lỗi xảy ra trong mẻ Avro Batch!", e);
            }
        }
    }

    /**
     * Khi phát hiện duplicate command, tìm kết quả KYC đã xử lý trước đó
     * và re-publish event về Orchestrator — đảm bảo caller luôn nhận được response.
     */
    private void replayPreviousResult(LoanCommand command) {
        repository.findBySagaId(command.sagaId()).ifPresent(existingResult -> {
            try {
                String resultType = "FAILED".equals(existingResult.getStatus()) ? "KycFailed" : "KycPassed";
                publishOutboxEvent(command, resultType);
                log.info("✅ [IDEMPOTENCY] Replayed {} for saga {}", resultType, command.sagaId());
            } catch (Exception e) {
                log.error("Failed to replay result for saga {}", command.sagaId(), e);
            }
        });
    }

    private void publishOutboxEvent(LoanCommand command, String resultType) {
        try {
            LoanEvent event = new LoanEvent(
                    UUID.randomUUID().toString(), command.sagaId(), resultType, command.aggregateId(),
                    command.customerId(), command.amount(), command.productType(),
                    command.simulateKycFail(), command.simulateCreditReject(), command.simulateDisbursementFail(),
                    Instant.now()
            );

            outboxRepository.save(OutboxEventEntity.builder()
                    .eventId(event.eventId())
                    .topicName("saga-events")
                    .eventType(event.eventType())
                    .aggregateId(event.aggregateId())
                    .payload(objectMapper.writeValueAsString(event))
                    .status("NEW")
                    .createdAt(Instant.now())
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish outbox event", e);
        }
    }

    private LoanCommand mapAvroToCommand(org.apache.avro.generic.GenericRecord record) {
        return new LoanCommand(
            getRecordString(record, "commandId"),
            getRecordString(record, "sagaId"),
            getRecordString(record, "commandType"),
            getRecordString(record, "aggregateId"),
            getRecordString(record, "customerId"),
            record.get("amount") != null ? new java.math.BigDecimal(record.get("amount").toString()) : null,
            getRecordString(record, "productType"),
            record.get("simulateKycFail") != null && (boolean) record.get("simulateKycFail"),
            record.get("simulateCreditReject") != null && (boolean) record.get("simulateCreditReject"),
            record.get("simulateDisbursementFail") != null && (boolean) record.get("simulateDisbursementFail"),
            Instant.now()
        );
    }

    private String getRecordString(org.apache.avro.generic.GenericRecord record, String field) {
        Object val = record.get(field);
        return val != null ? val.toString() : null;
    }
}

package com.example.lending.disbursement.messaging;

import com.example.lending.disbursement.commands.LoanCommand;
import com.example.lending.disbursement.domain.DisbursementResultEntity;
import com.example.lending.disbursement.domain.DisbursementResultRepository;
import com.example.lending.disbursement.events.LoanEvent;
import com.example.lending.disbursement.outbox.OutboxEventEntity;
import com.example.lending.disbursement.outbox.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class DisbursementCommandListener {
    private final DisbursementResultRepository repository;
    private final ProcessedMessageRepository processedRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @KafkaListener(topics = "saga-commands", groupId = "disbursement-service", containerFactory = "loanCommandKafkaListenerContainerFactory")
    @Transactional
    public void onCommand(org.apache.avro.generic.GenericRecord record) {
        try {
            LoanCommand command = mapAvroToCommand(record);
            if (command.commandId() == null) return;
            if (!"DisburseLoanCommand".equals(command.commandType())) return;

            // GIẢI QUYẾT HẠN CHẾ #15: Idempotency bằng Redis SETNX (Siêu tốc, chống Double Processing)
            String redisKey = "idempotency:disbursement:" + command.commandId();
            Boolean isNew = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, "PROCESSING", Duration.ofMinutes(10));
            if (Boolean.FALSE.equals(isNew)) {
                log.warn("⚠️ [IDEMPOTENCY] Bỏ qua Command {} vì đã hoặc đang được xử lý!", command.commandId());
                return;
            }
            
            // Backup DB Check
            if (processedRepository.existsById(command.commandId())) return;

            repository.save(DisbursementResultEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .sagaId(command.sagaId())
                    .loanApplicationId(command.aggregateId())
                    .customerId(command.customerId())
                    .status(Boolean.TRUE.equals(command.simulateDisbursementFail()) ? "FAILED" : "SUCCESS")
                    .processedAt(Instant.now())
                    .build());

            String resultType = Boolean.TRUE.equals(command.simulateDisbursementFail()) ? "DisbursementFailed" : "DisbursementSucceeded";

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

            processedRepository.save(ProcessedMessageEntity.builder()
                    .messageId(command.commandId())
                    .processedAt(Instant.now())
                    .build());

        } catch (Exception e) {
            throw new RuntimeException("Failed to process Avro disbursement command: " + record, e);
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

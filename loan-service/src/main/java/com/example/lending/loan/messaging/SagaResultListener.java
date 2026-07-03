package com.example.lending.loan.messaging;

import com.example.lending.loan.events.SagaResultEvent;
import com.example.lending.loan.loan.LoanApplicationEntity;
import com.example.lending.loan.loan.LoanApplicationRepository;
import com.example.lending.loan.security.AuditLogAspect;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SagaResultListener {

    private final LoanApplicationRepository loanRepository;
    private final ProcessedMessageRepository processedRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "saga-results",
            groupId = "loan-service-sync",
            containerFactory = "loanResultKafkaListenerContainerFactory"
    )
    @Transactional
    public void onResult(org.apache.avro.generic.GenericRecord record) {
        log.info("Received Avro Saga Result: {}", record);
        try {
            SagaResultEvent result = mapAvroToResult(record);
            
            String idempotencyKey = "RESULT_" + result.sagaId();
            if (processedRepository.existsById(idempotencyKey)) {
                return;
            }

            loanRepository.findById(result.loanApplicationId()).ifPresent(loan -> {
                log.info("Updating loan {} status to {}", loan.getLoanApplicationId(), result.finalStatus());
                loan.setStatus(result.finalStatus());
                loanRepository.save(loan);
            });

            processedRepository.save(new ProcessedMessageEntity(idempotencyKey, Instant.now()));

        } catch (Exception e) {
            log.error("Failed to sync Avro saga result", e);
        }
    }

    private SagaResultEvent mapAvroToResult(org.apache.avro.generic.GenericRecord record) {
        return new SagaResultEvent(
            getRecordString(record, "sagaId"),
            getRecordString(record, "loanApplicationId"),
            getRecordString(record, "finalStatus"),
            getRecordString(record, "errorReason"),
            Instant.now()
        );
    }

    private String getRecordString(org.apache.avro.generic.GenericRecord record, String field) {
        Object val = record.get(field);
        return val != null ? val.toString() : null;
    }
}

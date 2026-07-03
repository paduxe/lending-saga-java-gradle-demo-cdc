package com.example.lending.orchestrator.saga;

import com.example.lending.orchestrator.commands.LoanCommand;
import com.example.lending.orchestrator.events.LoanEvent;
import com.example.lending.orchestrator.messaging.ProcessedMessageEntity;
import com.example.lending.orchestrator.messaging.ProcessedMessageRepository;
import com.example.lending.orchestrator.outbox.OutboxEventEntity;
import com.example.lending.orchestrator.outbox.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoanSagaEventListener {

    private static final String RUNNING = "RUNNING";
    private static final String FAILED = "FAILED";
    private static final String COMPLETED = "COMPLETED";
    private static final String COMPENSATING = "COMPENSATING";

    private static final String KYC_REQUESTED = "KYC_REQUESTED";
    private static final String CREDIT_REQUESTED = "CREDIT_REQUESTED";
    private static final String DISBURSEMENT_REQUESTED = "DISBURSEMENT_REQUESTED";
    private static final String CREDIT_CANCELLATION_REQUESTED = "CREDIT_CANCELLATION_REQUESTED";

    private final SagaCacheService sagaCacheService; // ĐƯỢC THAY THẾ CHO LOAN SAGA REPOSITORY (CACHE WRITE-BEHIND)
    // private final LoanSagaRepository sagaRepository; BỎ QUA VIỆC DÙNG TRỰC TIẾP DB
    private final ProcessedMessageRepository processedRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final SagaResultService sagaResultService;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    private final org.springframework.statemachine.config.StateMachineFactory<SagaState, SagaEvent> stateMachineFactory;

    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "orchestrator")
    @KafkaListener(
            topics = "saga-events",
            groupId = "loan-orchestrator",
            containerFactory = "loanEventKafkaListenerContainerFactory"
    )
    @Transactional
    public void onEvent(org.apache.avro.generic.GenericRecord record) {
        log.info("AVRO KAFKA MESSAGE RECEIVED: {}", record);
        
        try {
            // Chuyển đổi từ Avro GenericRecord sang POJO để code xử lý Business giữ nguyên tính thẩm mỹ
            LoanEvent event = mapAvroToEvent(record);

            if (event.eventId() == null) {
                log.error("EVENT ID IS NULL IN AVRO RECORD! Record: {}", record);
                return;
            }

            // GIẢI QUYẾT HẠN CHẾ #1: HIGH-PERFORMANCE IDEMPOTENCY VỚI REDIS (SETNX)
            // Thay vì Query Database chậm chạp, ta dùng Redis gài khóa 24h.
            // Nếu eventId này đã tồn tại trong Redis -> Lệnh trả về False -> Trùng lặp -> Bỏ qua ngay lập tức.
            String redisKey = "idempotency:saga-events:" + event.eventId();
            Boolean isFirstTime = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, "PROCESSED", java.time.Duration.ofHours(24));
            
            if (Boolean.FALSE.equals(isFirstTime)) {
                  log.info("Event {} already processed (Idempotency Locked), skipping", event.eventId());
                  return;
            }

            log.info("Processing Avro event: {} - type: {}", event.eventId(), event.eventType());

            SagaEvent sagaEvent = mapToSagaEvent(event.eventType());
            if (sagaEvent != null) {
                String stateStr = getSagaState(event.sagaId());
                if ("LoanApplicationCreated".equals(event.eventType())) {
                     onLoanCreated(event);
                } else {
                     handleStateTransition(event, sagaEvent, stateStr);
                }
            }
            markProcessed(event.eventId());

        } catch (Exception e) {
            log.error("Failed to process Avro Kafka message: " + record, e);
            throw new RuntimeException(e);
        }
    }

    private LoanEvent mapAvroToEvent(org.apache.avro.generic.GenericRecord record) {
        return new LoanEvent(
            getRecordString(record, "eventId"),
            getRecordString(record, "sagaId"),
            getRecordString(record, "eventType"),
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

    private String getSagaState(String sagaId) {
        return sagaCacheService.getSaga(sagaId).map(LoanSagaEntity::getCurrentStep).orElse(null);
    }

    private void handleStateTransition(LoanEvent event, SagaEvent sagaEvent, String currentStateStr) {
        if (currentStateStr == null) return;
        SagaState currentState = mapToSagaState(currentStateStr);
        if (currentState == null) return;

        org.springframework.statemachine.StateMachine<SagaState, SagaEvent> sm = stateMachineFactory.getStateMachine(event.sagaId());
        sm.getStateMachineAccessor().doWithAllRegions(accessor -> {
            accessor.resetStateMachine(new org.springframework.statemachine.support.DefaultStateMachineContext<>(currentState, null, null, null));
        });
        
        sm.start();
        boolean accepted = sm.sendEvent(sagaEvent);
        if (accepted) {
            SagaState newState = sm.getState().getId();
            processNewState(newState, event);
        }
        sm.stop();
    }

    private void processNewState(SagaState newState, LoanEvent event) {
        if (newState == SagaState.CREDIT_REQUESTED) {
             onKycPassed(event);
        } else if (newState == SagaState.FAILED && "KycFailed".equals(event.eventType())) {
             onKycFailed(event);
        } else if (newState == SagaState.DISBURSEMENT_REQUESTED) {
             onCreditApproved(event);
        } else if (newState == SagaState.FAILED && "CreditRejected".equals(event.eventType())) {
             onCreditRejected(event);
        } else if (newState == SagaState.COMPLETED) {
             onDisbursementSucceeded(event);
        } else if (newState == SagaState.COMPENSATING_CREDIT) {
             onDisbursementFailed(event);
        } else if (newState == SagaState.FAILED && "CreditCancelled".equals(event.eventType())) {
             onCreditCancelled(event);
        }
    }

    private SagaEvent mapToSagaEvent(String eventType) {
        return switch(eventType) {
            case "LoanApplicationCreated" -> SagaEvent.START_SAGA;
            case "KycPassed" -> SagaEvent.KYC_PASSED;
            case "KycFailed" -> SagaEvent.KYC_FAILED;
            case "CreditApproved" -> SagaEvent.CREDIT_APPROVED;
            case "CreditRejected" -> SagaEvent.CREDIT_REJECTED;
            case "DisbursementSucceeded" -> SagaEvent.DISBURSEMENT_SUCCEEDED;
            case "DisbursementFailed" -> SagaEvent.DISBURSEMENT_FAILED;
            case "CreditCancelled" -> SagaEvent.CREDIT_CANCELLED;
            default -> null;
        };
    }

    private SagaState mapToSagaState(String step) {
        return switch(step) {
            case KYC_REQUESTED -> SagaState.KYC_REQUESTED;
            case CREDIT_REQUESTED -> SagaState.CREDIT_REQUESTED;
            case DISBURSEMENT_REQUESTED -> SagaState.DISBURSEMENT_REQUESTED;
            case CREDIT_CANCELLATION_REQUESTED -> SagaState.COMPENSATING_CREDIT;
            default -> null;
        };
    }

    private void onLoanCreated(LoanEvent event) {
        if (sagaCacheService.existsInRAMOrDB(event.sagaId())) {
            return;
        }

        log.info("Initializing new Saga: {} for loan: {}", event.sagaId(), event.aggregateId());
        LoanSagaEntity saga = LoanSagaEntity.builder()
                .sagaId(event.sagaId())
                .loanApplicationId(event.aggregateId())
                .currentStep(KYC_REQUESTED)
                .sagaStatus(RUNNING)
                .updatedAt(Instant.now())
                .build();

        sagaCacheService.updateStateInMemory(saga); // LƯU VÀO RAM TIẾT KIỆM TÀI NGUYÊN
        meterRegistry.counter("saga_started_total").increment();
        sendCommand(event, "VerifyKycCommand");
    }

    private void onKycPassed(LoanEvent event) {
        findSagaInStatuses(event.sagaId(), RUNNING)
                .filter(saga -> KYC_REQUESTED.equals(saga.getCurrentStep()))
                .ifPresent(saga -> {
                    saga.setCurrentStep(CREDIT_REQUESTED);
                    saga.setUpdatedAt(Instant.now());
                    sagaCacheService.updateStateInMemory(saga); // LƯU VÀO RAM TIẾT KIỆM TÀI NGUYÊN
                    sendCommand(event, "CheckCreditCommand");
                });
    }

    private void onKycFailed(LoanEvent event) {
        findSagaInStatuses(event.sagaId(), RUNNING)
                .filter(saga -> KYC_REQUESTED.equals(saga.getCurrentStep()))
                .ifPresent(saga -> failSaga(saga, "KYC_FAILED"));
    }

    private void onCreditApproved(LoanEvent event) {
        findSagaInStatuses(event.sagaId(), RUNNING)
                .filter(saga -> CREDIT_REQUESTED.equals(saga.getCurrentStep()))
                .ifPresent(saga -> {
                    saga.setCurrentStep(DISBURSEMENT_REQUESTED);
                    saga.setUpdatedAt(Instant.now());
                    sagaCacheService.updateStateInMemory(saga); // LƯU VÀO RAM
                    sendCommand(event, "DisburseLoanCommand");
                });
    }

    private void onCreditRejected(LoanEvent event) {
        findSagaInStatuses(event.sagaId(), RUNNING)
                .filter(saga -> CREDIT_REQUESTED.equals(saga.getCurrentStep()))
                .ifPresent(saga -> failSaga(saga, "CREDIT_REJECTED"));
    }

    private void onDisbursementSucceeded(LoanEvent event) {
        findSagaInStatuses(event.sagaId(), RUNNING)
                .filter(saga -> DISBURSEMENT_REQUESTED.equals(saga.getCurrentStep()))
                .ifPresent(saga -> {
                    saga.setCurrentStep("DISBURSEMENT_SUCCEEDED");
                    saga.setSagaStatus(COMPLETED);
                    saga.setUpdatedAt(Instant.now());
                    sagaCacheService.flushToDb(saga); // TRẠNG THÁI CUỐI -> XẢ SẠCH LÊN ĐĨA POSTGRES
                    sagaResultService.sendResultToLoanService(saga, "APPROVED");

                    // ĐO LƯỜNG TIỀN GIẢI NGÂN THỰC TẾ TRÊN GRAFANA (Thước đo Business Metric tối thượng)
                    meterRegistry.counter("loans_disbursed_total_count").increment();
                    if (event.amount() != null) {
                        meterRegistry.summary("loans_disbursed_amount_total").record(event.amount().doubleValue());
                    }
                });
    }

    private void onDisbursementFailed(LoanEvent event) {
        findSagaInStatuses(event.sagaId(), RUNNING)
                .filter(saga -> DISBURSEMENT_REQUESTED.equals(saga.getCurrentStep()))
                .ifPresent(saga -> {
                    saga.setCurrentStep(CREDIT_CANCELLATION_REQUESTED);
                    saga.setSagaStatus(COMPENSATING);
                    saga.setErrorReason("DISBURSEMENT_FAILED");
                    saga.setUpdatedAt(Instant.now());
                    sagaCacheService.updateStateInMemory(saga); // LƯU TRẠNG THÁI BỒI THƯỜNG TRUNG GIAN TRÊN RAM
                    sendCommand(event, "CancelCreditCommand");
                });
    }

    private void onCreditCancelled(LoanEvent event) {
        findSagaInStatuses(event.sagaId(), COMPENSATING)
                .filter(saga -> CREDIT_CANCELLATION_REQUESTED.equals(saga.getCurrentStep()))
                .ifPresent(saga -> {
                    saga.setCurrentStep("CREDIT_CANCELLED");
                    saga.setSagaStatus(FAILED);
                    saga.setErrorReason("DISBURSEMENT_FAILED_CREDIT_COMPENSATED");
                    saga.setUpdatedAt(Instant.now());
                    sagaCacheService.flushToDb(saga); // QUY TRÌNH HỦY HOÀN TẤT -> XẢ XUỐNG DB VĨNH VIỄN
                });
    }

    private Optional<LoanSagaEntity> findSagaInStatuses(String sagaId, String expectedStatus) {
        return sagaCacheService.getSaga(sagaId)
                .filter(saga -> expectedStatus.equals(saga.getSagaStatus()));
    }

    private void failSaga(LoanSagaEntity saga, String reason) {
        saga.setCurrentStep(reason);
        saga.setSagaStatus(FAILED);
        saga.setErrorReason(reason);
        saga.setUpdatedAt(Instant.now());
        sagaCacheService.flushToDb(saga); // LỖI GẪY SAGA -> XẢ DATA XUỐNG Ổ ĐĨA
        
        // Châm ngòi nổ AlertManager: Saga Thất Bại!
        meterRegistry.counter("saga_failed_total", "reason", reason).increment();
        
        sagaResultService.sendResultToLoanService(saga, "REJECTED");
    }

    private void markProcessed(String eventId) {
        processedRepository.save(ProcessedMessageEntity.builder()
                .messageId(eventId)
                .processedAt(Instant.now())
                .build());
    }



    private void sendCommand(LoanEvent event, String commandType) {
        try {
            LoanCommand command = new LoanCommand(
                    UUID.randomUUID().toString(),
                    event.sagaId(),
                    commandType,
                    event.aggregateId(),
                    event.customerId(),
                    event.amount(),
                    event.productType(),
                    event.simulateKycFail(),
                    event.simulateCreditReject(),
                    event.simulateDisbursementFail(),
                    Instant.now()
            );

            outboxRepository.save(OutboxEventEntity.builder()
                    .eventId(command.commandId())
                    .topicName("saga-commands")
                    .eventType(command.commandType())
                    .aggregateId(command.aggregateId())
                    .payload(objectMapper.writeValueAsString(command))
                    .status("NEW")
                    .createdAt(Instant.now())
                    .build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

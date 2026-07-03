package com.example.lending.orchestrator.saga;

import com.example.lending.orchestrator.events.SagaResultEvent;
import com.example.lending.orchestrator.outbox.OutboxEventEntity;
import com.example.lending.orchestrator.outbox.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SagaResultService {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void sendResultToLoanService(LoanSagaEntity saga, String finalStatus) {
        try {
            SagaResultEvent result = new SagaResultEvent(
                    saga.getSagaId(),
                    saga.getLoanApplicationId(),
                    finalStatus,
                    saga.getErrorReason(),
                    Instant.now()
            );

            // GỬI KẾT QUẢ CUỐI CÙNG VỀ LOAN-SERVICE QUA OUTBOX (Sắc bén về tính nhất quán)
            outboxRepository.save(OutboxEventEntity.builder()
                    .eventId(UUID.randomUUID().toString())
                    .topicName("saga-results")
                    .eventType("SagaResult")
                    .aggregateId(saga.getLoanApplicationId())
                    .payload(objectMapper.writeValueAsString(result))
                    .status("NEW")
                    .createdAt(Instant.now())
                    .build());
            
            log.info("📢 [SYNC STATUS] Đã gửi thông báo Saga {}: {} về topic saga-results", 
                    saga.getSagaId(), finalStatus);
        } catch (Exception e) {
            log.error("❌ [SYNC ERROR] Không thể gửi kết quả Saga {}", saga.getSagaId(), e);
            throw new RuntimeException("Lỗi đồng bộ trạng thái Saga!", e);
        }
    }
}

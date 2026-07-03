package com.example.lending.orchestrator.bpm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Lớp này mô phỏng cách lắng nghe kết quả từ Kafka để hoàn thành Process trong Camunda.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BpmSagaResultListener {

    private final RuntimeService runtimeService;

    /**
     * Lắng nghe kết quả KYC từ topic "saga-events"
     */
    @KafkaListener(topics = "saga-events", groupId = "bpm-orchestrator-group")
    public void onKycResult(Map<String, Object> event) {
        String eventType = (String) event.get("eventType");
        String sagaId = (String) event.get("sagaId"); // Chính là ProcessInstanceId

        if ("KycPassed".equals(eventType)) {
            log.info("BPM Listener: Nhận được KycPassed cho Saga: {}. Tiếp tục workflow...", sagaId);
            
            // Tìm và báo cho Camunda tiếp tục (Core logic)
            // Trong thực tế, bạn sẽ dùng Message Correlation
            runtimeService.createMessageCorrelation("KycResponseReceived")
                    .processInstanceId(sagaId)
                    .setVariable("kycStatus", "SUCCESS")
                    .correlate();
        } else if ("KycFailed".equals(eventType)) {
            log.info("BPM Listener: Nhận được KycFailed. Cắt luồng...");
            
            runtimeService.createMessageCorrelation("KycResponseReceived")
                    .processInstanceId(sagaId)
                    .setVariable("kycStatus", "FAILED")
                    .correlate();
        }
    }
}

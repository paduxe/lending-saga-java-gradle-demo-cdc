package com.example.lending.orchestrator.messaging;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import lombok.RequiredArgsConstructor;

// GIẢI QUYẾT HẠN CHẾ #10: LƯU DLQ VÀO DATABASE ĐỂ ENGINEER REPLAY THAY VÌ CHỈ IN LOG
@Component
@Slf4j
@RequiredArgsConstructor
public class DlqListener {

    private final DlqMessageRepository dlqMessageRepository;

    @KafkaListener(topics = "saga-dlq", groupId = "dlq-alert-group")
    public void processDlqMessage(ConsumerRecord<String, String> record,
                                  @Header(value = KafkaHeaders.EXCEPTION_MESSAGE, required = false) byte[] exceptionMessage) {
        
        String originalTopic = record.headers().lastHeader(KafkaHeaders.ORIGINAL_TOPIC) != null 
            ? new String(record.headers().lastHeader(KafkaHeaders.ORIGINAL_TOPIC).value(), StandardCharsets.UTF_8)
            : "UNKNOWN_TOPIC";
            
        String errorMsg = exceptionMessage != null ? new String(exceptionMessage, StandardCharsets.UTF_8) : "N/A";

        log.error("🚨 [BÁO ĐỘNG ĐỎ - DLQ] Ghi nhận rác từ topic: {}. Lệnh sẽ được lưu vào CSDL để Kỹ sư xem xét.", originalTopic);
        
        DlqMessageEntity dlqEntity = DlqMessageEntity.builder()
                .originalTopic(originalTopic)
                .messageKey(record.key() != null ? record.key() : "NULL_KEY")
                .payload(record.value())
                .errorMessage(errorMsg)
                .failedAt(Instant.now())
                .status("NEW") // Đợi Admin bấm nút Replay từ UI
                .build();

        dlqMessageRepository.save(dlqEntity);
    }
}

package com.example.lending.orchestrator.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DlqPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(String key, DlqEvent event) {
        kafkaTemplate.send("saga-dlq", key, event);
    }
}

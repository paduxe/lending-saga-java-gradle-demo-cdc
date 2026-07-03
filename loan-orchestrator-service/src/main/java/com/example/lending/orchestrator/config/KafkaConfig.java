package com.example.lending.orchestrator.config;

import com.example.lending.orchestrator.commands.LoanCommand;
import com.example.lending.orchestrator.events.LoanEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;

import org.springframework.kafka.config.TopicBuilder;
import java.util.HashMap;
import java.util.Map;

    @Configuration
    public class KafkaConfig {
    
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, org.apache.avro.generic.GenericRecord> loanEventKafkaListenerContainerFactory(
            ConsumerFactory<String, org.apache.avro.generic.GenericRecord> cf, org.springframework.kafka.listener.DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, org.apache.avro.generic.GenericRecord> f = 
                new ConcurrentKafkaListenerContainerFactory<>();
        f.setConsumerFactory(cf);
        f.setCommonErrorHandler(errorHandler);
        f.setConcurrency(3);
        return f;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, org.apache.avro.generic.GenericRecord> loanCommandKafkaListenerContainerFactory(
            ConsumerFactory<String, org.apache.avro.generic.GenericRecord> cf, org.springframework.kafka.listener.DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, org.apache.avro.generic.GenericRecord> f = 
                new ConcurrentKafkaListenerContainerFactory<>();
        f.setConsumerFactory(cf);
        f.setCommonErrorHandler(errorHandler);
        f.setConcurrency(3);
        return f;
    }

    @Bean
    ProducerFactory<String, Object> dlqProducerFactory(@Value("${spring.kafka.bootstrap-servers}") String servers) {
        Map<String,Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, org.springframework.kafka.support.serializer.JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    KafkaTemplate<String, Object> dlqKafkaTemplate(ProducerFactory<String, Object> pf) {
        return new KafkaTemplate<>(pf);
    }

    @Bean
    public org.springframework.kafka.listener.DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> dlqKafkaTemplate) {
        org.springframework.util.backoff.FixedBackOff backOff = new org.springframework.util.backoff.FixedBackOff(1000L, 3);
        org.springframework.kafka.listener.DeadLetterPublishingRecoverer recoverer =
                new org.springframework.kafka.listener.DeadLetterPublishingRecoverer(dlqKafkaTemplate,
                        (r, e) -> new org.apache.kafka.common.TopicPartition("saga-dlq", r.partition()));
        return new org.springframework.kafka.listener.DefaultErrorHandler(recoverer, backOff);
    }

    // Removed explicit Factories to use Spring Boot auto-configuration based on application.yml

    @Bean NewTopic sagaEventsTopic() { return TopicBuilder.name("saga-events").partitions(30).replicas(3).build(); }
    @Bean NewTopic sagaCommandsTopic() { return TopicBuilder.name("saga-commands").partitions(30).replicas(3).build(); }
    @Bean NewTopic sagaDlqTopic() { return TopicBuilder.name("saga-dlq").partitions(30).replicas(3).build(); }
    @Bean NewTopic sagaResultsTopic() { return TopicBuilder.name("saga-results").partitions(30).replicas(3).build(); }
}

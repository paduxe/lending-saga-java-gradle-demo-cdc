package com.example.lending.loan.config;

import com.example.lending.loan.commands.LoanCommand;
import com.example.lending.loan.events.LoanEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
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
            ConsumerFactory<String, org.apache.avro.generic.GenericRecord> cf
    ) {
        ConcurrentKafkaListenerContainerFactory<String, org.apache.avro.generic.GenericRecord> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.setConcurrency(3);
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, org.apache.avro.generic.GenericRecord> loanCommandKafkaListenerContainerFactory(
            ConsumerFactory<String, org.apache.avro.generic.GenericRecord> cf
    ) {
        ConcurrentKafkaListenerContainerFactory<String, org.apache.avro.generic.GenericRecord> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.setConcurrency(3);
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, org.apache.avro.generic.GenericRecord> loanResultKafkaListenerContainerFactory(
            ConsumerFactory<String, org.apache.avro.generic.GenericRecord> cf
    ) {
        ConcurrentKafkaListenerContainerFactory<String, org.apache.avro.generic.GenericRecord> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.setConcurrency(3);
        return factory;
    }

    @Bean
    NewTopic sagaResultsTopic() {
        return TopicBuilder.name("saga-results").partitions(30).replicas(3).build();
    }

    @Bean
    NewTopic sagaEventsTopic() {
        return TopicBuilder.name("saga-events").partitions(30).replicas(3).build();
    }

    @Bean
    NewTopic sagaCommandsTopic() {
        return TopicBuilder.name("saga-commands").partitions(30).replicas(3).build();
    }
}

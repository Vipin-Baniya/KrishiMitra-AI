package com.krishimitra.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
class KafkaTopicConfig {

    @Bean public NewTopic priceUpdatesTopic() {
        return TopicBuilder.name("krishimitra.price-updates")
                .partitions(6).replicas(1)
                .config("retention.ms", "86400000")  // 24h
                .build();
    }

    @Bean public NewTopic sellAlertsTopic() {
        return TopicBuilder.name("krishimitra.sell-alerts")
                .partitions(3).replicas(1).build();
    }

    @Bean public NewTopic priceIngestedTopic() {
        return TopicBuilder.name("krishimitra.price-ingested")
                .partitions(3).replicas(1).build();
    }

    @Bean public NewTopic modelRetrainTopic() {
        return TopicBuilder.name("krishimitra.model-retrain")
                .partitions(1).replicas(1).build();
    }
}

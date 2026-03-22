package com.krishimitra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * KrishiMitra AI — Spring Boot Application Entry Point
 *
 * Farmer decision intelligence platform.
 * What crop to grow? When to sell? At what price?
 *
 * Run:   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
 * Build: ./mvnw package -DskipTests
 * Test:  ./mvnw test -Dspring.profiles.active=test
 */
@SpringBootApplication
@EnableJpaAuditing           // enables @CreatedDate / @LastModifiedDate on entities
@EnableCaching               // enables @Cacheable / @CacheEvict (Redis)
@EnableKafka                 // enables @KafkaListener consumers
@EnableScheduling            // enables @Scheduled (Agmarknet ingestion cron)
@EnableAsync                 // enables @Async methods
@EnableTransactionManagement // explicit — already implied, kept for clarity
public class KrishiMitraApplication {

    public static void main(String[] args) {
        SpringApplication.run(KrishiMitraApplication.class, args);
    }
}

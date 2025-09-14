package com.example.scanner.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

@Configuration
public class AppConfig {

  @Bean
  public Executor taskExecutor() {
    ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(4);
    ex.setMaxPoolSize(8);
    ex.setQueueCapacity(100);
    ex.setThreadNamePrefix("scan-");
    ex.initialize();
    return ex;
  }

  @Bean
  @Primary
  public ObjectMapper objectMapper() {
    ObjectMapper m = new ObjectMapper();
    m.registerModule(new JavaTimeModule());
    m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // Add duplicate key detection
    m.configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);

    // Optional: Other useful security/validation configurations
    m.configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);
    m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    return m;
  }
}
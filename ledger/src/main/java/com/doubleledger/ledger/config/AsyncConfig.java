package com.doubleledger.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean(name = "forensicLogExecutor")
    public Executor forensicLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);        // Always keep 5 threads ready
        executor.setMaxPoolSize(20);        // Max out at 20 threads under high load
        executor.setQueueCapacity(500);     // Queue up to 500 tasks before rejecting
        executor.setThreadNamePrefix("ForensicAudit-");
        executor.initialize();
        return executor;
    }
}
package com.hunt.otziv.p_products.next_order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class NextOrderAutomationAsyncConfig {

    @Bean(name = "nextOrderAutomationExecutor")
    public TaskExecutor nextOrderAutomationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1_000);
        executor.setThreadNamePrefix("next-order-");
        executor.initialize();
        return executor;
    }
}

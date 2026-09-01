package com.hunt.otziv.p_products.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class OrderPaymentPostCommitAsyncConfig {

    @Bean(name = "orderPaymentPostCommitExecutor")
    public TaskExecutor orderPaymentPostCommitExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1_000);
        executor.setThreadNamePrefix("order-paid-post-commit-");
        executor.setRejectedExecutionHandler(
                new DurablePostCommitRejectedExecutionHandler("orderPaymentPostCommitExecutor")
        );
        executor.initialize();
        return executor;
    }
}

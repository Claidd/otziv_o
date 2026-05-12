package com.hunt.otziv.reputationai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ReputationAiAsyncConfig {

    @Bean(name = "reputationDeepReportExecutor")
    public TaskExecutor reputationDeepReportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("ReputationDeepReport-");
        executor.initialize();
        return executor;
    }
}

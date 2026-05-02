package com.hunt.otziv.text_generator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration("asyncConfigTextGen") // для text_generator
@EnableAsync
public class AsyncConfig {

    @Bean(name = "reviewExecutor")
    public Executor reviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2); // максимум 2 генерации одновременно
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20); // очередь ожидания
        executor.setThreadNamePrefix("review-gen-");
        executor.initialize();
        return executor;
    }
}

package com.hunt.otziv.whatsapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Bounded acceleration of work already committed to the owners' durable queues. */
@Configuration
public class ClientNotificationAsyncConfig {
    @Bean(name = "clientNotificationExecutor")
    public ThreadPoolTaskExecutor clientNotificationExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(128);
        executor.setThreadNamePrefix("client-notification-");
        // Keep AbortPolicy: never run provider work in an HTTP/commit thread.
        // Rejected/lost wakeups remain discoverable in the persistent queues.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(45);
        return executor;
    }
}

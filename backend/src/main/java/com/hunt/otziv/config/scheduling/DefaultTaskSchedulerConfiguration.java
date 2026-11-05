package com.hunt.otziv.config.scheduling;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Default scheduler for every {@code @Scheduled} method without an explicit {@code scheduler}.
 *
 * <p>Several modules declare their own ThreadPoolTaskScheduler beans. That makes Spring Boot's
 * auto-configured scheduler back off, and with no bean named "taskScheduler" Spring falls back
 * to a single-threaded executor: since 11.09.2026 about 96 jobs (publication outbox, client
 * messages, bank reconciliation, contractor reconciliation, evening summaries) ran one after
 * another on one thread ("pool-N-thread-1"), so one slow external call delayed all of them.
 */
@Configuration
public class DefaultTaskSchedulerConfiguration {

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${spring.task.scheduling.pool.size:4}") int poolSize,
            @Value("${spring.task.scheduling.thread-name-prefix:scheduling-}") String threadNamePrefix
    ) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, poolSize));
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.setErrorHandler(failure -> org.slf4j.LoggerFactory.getLogger(DefaultTaskSchedulerConfiguration.class)
                .error("Scheduled task failed", failure));
        return scheduler;
    }
}

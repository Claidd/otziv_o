package com.hunt.otziv.common_billing.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Slow chat providers must not occupy the application's business scheduler. */
@Configuration
class CommonInvoiceQueueScheduling {
    @Bean
    ThreadPoolTaskScheduler commonInvoiceMessageScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("common-invoice-delivery-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}

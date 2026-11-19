package com.hunt.otziv.client_messages.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
class ClientContactHealthScheduling {
    @Bean ThreadPoolTaskScheduler clientContactHealthScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("client-contact-health-");
        return scheduler;
    }
}

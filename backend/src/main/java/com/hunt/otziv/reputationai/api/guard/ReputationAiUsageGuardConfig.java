package com.hunt.otziv.reputationai.api.guard;

import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ReputationAiUsageGuardConfig implements WebMvcConfigurer {

    private final ReputationAiUsageGuard guard;

    public ReputationAiUsageGuardConfig(
            @Value("${reputation-ai.guard.daily-operations-per-user:200}") int dailyOperationsPerUser,
            @Value("${reputation-ai.guard.max-concurrent-synchronous-analyses:6}") int maxConcurrentSynchronous,
            @Value("${outreach-bridge.zone:Asia/Irkutsk}") String zone
    ) {
        this.guard = new ReputationAiUsageGuard(dailyOperationsPerUser, maxConcurrentSynchronous, ZoneId.of(zone));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard).addPathPatterns("/api/ai/reputation/**");
    }
}

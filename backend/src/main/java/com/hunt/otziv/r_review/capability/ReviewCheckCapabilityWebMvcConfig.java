package com.hunt.otziv.r_review.capability;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class ReviewCheckCapabilityWebMvcConfig implements WebMvcConfigurer {

    private final ReviewCheckLegacyTelemetryInterceptor telemetryInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(telemetryInterceptor)
                .addPathPatterns(
                        "/api/review-check/**",
                        "/review/editReviews/**",
                        "/review/editReviewses/**"
                );
    }
}

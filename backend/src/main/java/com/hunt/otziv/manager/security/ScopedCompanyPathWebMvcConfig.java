package com.hunt.otziv.manager.security;

import com.hunt.otziv.manager.service.ManagerAccessService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ScopedCompanyPathWebMvcConfig implements WebMvcConfigurer {

    private final ManagerAccessService managerAccessService;

    public ScopedCompanyPathWebMvcConfig(ManagerAccessService managerAccessService) {
        this.managerAccessService = managerAccessService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ScopedCompanyPathInterceptor(managerAccessService))
                .addPathPatterns("/api/ai/reputation/companies/**");
    }
}

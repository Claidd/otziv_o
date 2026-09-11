package com.hunt.otziv.config.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CABINET_STATS = "cabinetStats";
    public static final String CABINET_PROFILE = "cabinetProfile";
    public static final String CABINET_USER_INFO = "cabinetUserInfo";
    public static final String CABINET_TEAM = "cabinetTeam";
    public static final String CABINET_SCORE = "cabinetScore";
    public static final String CABINET_ANALYTICS = "cabinetAnalytics";
    public static final String PROMO_TEXTS = "promoTexts";

    @Bean
    public CacheManager cacheManager(@Value("${otziv.cache.cabinet.ttl:PT1H}") Duration cabinetCacheTtl) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                CABINET_STATS,
                CABINET_PROFILE,
                CABINET_USER_INFO,
                CABINET_TEAM,
                CABINET_SCORE,
                CABINET_ANALYTICS,
                PROMO_TEXTS
        );
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(cabinetCacheTtl)
                .recordStats());
        // Frequently changing screen DTOs must not outlive their prepared snapshots.
        // Statistics with an explicit date keep their separately configured policy.
        for (String name : java.util.List.of(CABINET_PROFILE, CABINET_TEAM)) {
            cacheManager.registerCustomCache(name, Caffeine.newBuilder().maximumSize(2_000)
                    .expireAfterWrite(Duration.ofSeconds(30)).recordStats().build());
        }
        return cacheManager;
    }
}

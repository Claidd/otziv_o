package com.hunt.otziv.gamification.service;

import com.hunt.otziv.gamification.dto.GamificationRuleRequest;
import com.hunt.otziv.gamification.dto.GamificationRuleResponse;
import com.hunt.otziv.gamification.dto.GamificationRulesRequest;
import com.hunt.otziv.gamification.dto.GamificationRulesResponse;
import com.hunt.otziv.gamification.model.GamificationRule;
import com.hunt.otziv.gamification.repository.GamificationRuleRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GamificationRuleService {

    private static final int MAX_POINTS = 1000;
    private static final Map<String, Integer> DEFAULT_POINTS = defaults();

    private final GamificationRuleRepository repository;

    @Transactional(readOnly = true)
    public GamificationRulesResponse getRules() {
        return response(readRules());
    }

    @Transactional
    public GamificationRulesResponse updateRules(GamificationRulesRequest request) {
        Map<String, GamificationRule> current = readRules();
        if (request != null && request.rules() != null) {
            for (GamificationRuleRequest ruleRequest : request.rules()) {
                if (ruleRequest == null || !DEFAULT_POINTS.containsKey(ruleRequest.eventType())) {
                    continue;
                }
                GamificationRule rule = current.get(ruleRequest.eventType());
                if (rule == null) {
                    rule = defaultRule(ruleRequest.eventType());
                }
                rule.setEnabled(ruleRequest.enabled() == null || ruleRequest.enabled());
                rule.setPoints(points(ruleRequest.points(), ruleRequest.eventType()));
                repository.save(rule);
            }
        }
        return response(readRules());
    }

    @Transactional(readOnly = true)
    public Map<String, GamificationRule> readRules() {
        Map<String, GamificationRule> result = new LinkedHashMap<>();
        repository.findAllById(DEFAULT_POINTS.keySet()).forEach(rule -> result.put(rule.getEventType(), rule));
        for (String eventType : DEFAULT_POINTS.keySet()) {
            result.putIfAbsent(eventType, defaultRule(eventType));
        }
        return result;
    }

    private GamificationRulesResponse response(Map<String, GamificationRule> rules) {
        return new GamificationRulesResponse(rules.values().stream()
                .map(rule -> new GamificationRuleResponse(
                        rule.getEventType(),
                        rule.isEnabled(),
                        rule.getPoints(),
                        rule.getUpdatedAt()
                ))
                .toList());
    }

    private GamificationRule defaultRule(String eventType) {
        return GamificationRule.builder()
                .eventType(eventType)
                .enabled(true)
                .points(DEFAULT_POINTS.getOrDefault(eventType, 0))
                .build();
    }

    private int points(Integer points, String eventType) {
        int value = points == null ? DEFAULT_POINTS.getOrDefault(eventType, 0) : points;
        return Math.max(0, Math.min(value, MAX_POINTS));
    }

    private static Map<String, Integer> defaults() {
        Map<String, Integer> defaults = new LinkedHashMap<>();
        defaults.put(GamificationEventService.REVIEW_PUBLISHED, 10);
        defaults.put(GamificationEventService.ORDER_PAID, 25);
        defaults.put(GamificationEventService.BAD_REVIEW_TASK_DONE, 15);
        defaults.put(GamificationEventService.REVIEW_RECOVERY_TASK_DONE, 20);
        return Map.copyOf(defaults);
    }
}

package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.manager_daily_summary.dto.SiteActivityRequest;
import com.hunt.otziv.manager_daily_summary.model.ManagerSiteActivityEvent;
import com.hunt.otziv.manager_daily_summary.repository.ManagerSiteActivityEventRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.security.Principal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerSiteActivityService {

    private final UserRepository userRepository;
    private final ManagerRepository managerRepository;
    private final ManagerSiteActivityEventRepository activityRepository;

    @Transactional
    public void record(Principal principal, SiteActivityRequest request) {
        if (principal == null || principal.getName() == null || request == null) {
            return;
        }
        User user = userRepository.findByUsername(principal.getName()).orElse(null);
        if (user == null || !user.isActive()) {
            return;
        }
        Manager manager = managerRepository.findByUserId(user.getId()).orElse(null);
        if (manager == null) {
            return;
        }
        ManagerSiteActivityEvent event = new ManagerSiteActivityEvent();
        event.setUser(user);
        event.setManager(manager);
        event.setOccurredAt(LocalDateTime.now());
        event.setActivityType(limit(normalizedType(request.activityType()), 32));
        event.setRoute(limit(request.route(), 500));
        event.setSessionId(limit(request.sessionId(), 80));
        activityRepository.save(event);
    }

    private String normalizedType(String value) {
        String normalized = value == null ? "HEARTBEAT" : value.trim().toUpperCase();
        return normalized.matches("[A-Z_]{1,32}") ? normalized : "HEARTBEAT";
    }

    private String limit(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}

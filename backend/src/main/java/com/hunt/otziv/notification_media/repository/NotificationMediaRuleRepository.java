package com.hunt.otziv.notification_media.repository;

import com.hunt.otziv.notification_media.model.NotificationMediaRule;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationMediaRuleRepository extends JpaRepository<NotificationMediaRule, Long> {
    Optional<NotificationMediaRule> findByEventCode(String eventCode);
    List<NotificationMediaRule> findAllByOrderByEventCodeAsc();
}

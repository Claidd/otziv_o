package com.hunt.otziv.notification_media.repository;

import com.hunt.otziv.notification_media.model.NotificationMediaAsset;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationMediaAssetRepository extends JpaRepository<NotificationMediaAsset, Long> {
    List<NotificationMediaAsset> findByRuleIdOrderBySortOrderAscIdAsc(Long ruleId);
    List<NotificationMediaAsset> findByRuleIdAndActiveTrueOrderBySortOrderAscIdAsc(Long ruleId);
}

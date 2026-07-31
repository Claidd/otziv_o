package com.hunt.otziv.notification_media.service;

import com.hunt.otziv.notification_media.model.NotificationMediaAsset;
import com.hunt.otziv.notification_media.model.NotificationMediaDelivery;
import com.hunt.otziv.notification_media.model.NotificationMediaRule;
import com.hunt.otziv.notification_media.repository.NotificationMediaAssetRepository;
import com.hunt.otziv.notification_media.repository.NotificationMediaDeliveryRepository;
import com.hunt.otziv.notification_media.repository.NotificationMediaRuleRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationMediaSelector {

    private final NotificationMediaRuleRepository ruleRepository;
    private final NotificationMediaAssetRepository assetRepository;
    private final NotificationMediaDeliveryRepository deliveryRepository;
    private final NotificationMediaRandomizer randomizer;

    @Transactional(readOnly = true)
    public Optional<Selection> select(String eventCode, long chatId, LocalDateTime now) {
        NotificationMediaEventCatalog event = NotificationMediaEventCatalog.find(eventCode).orElse(null);
        if (event == null) {
            return Optional.empty();
        }
        NotificationMediaRule rule = ruleRepository.findByEventCode(event.code()).orElse(null);
        if (rule == null || !rule.isEnabled()
                || !randomizer.shouldAttach(rule.getImageProbabilityPercent())) {
            return Optional.empty();
        }

        Optional<NotificationMediaDelivery> lastDelivery =
                deliveryRepository.findFirstByEventCodeAndChatIdAndPhotoSentTrueOrderBySentAtDesc(
                        event.code(),
                        chatId
                );
        LocalDateTime checkedAt = now == null ? LocalDateTime.now() : now;
        if (rule.getCooldownMinutes() > 0
                && lastDelivery.map(NotificationMediaDelivery::getSentAt)
                .filter(sentAt -> sentAt.isAfter(checkedAt.minusMinutes(rule.getCooldownMinutes())))
                .isPresent()) {
            return Optional.empty();
        }

        List<NotificationMediaAsset> active =
                assetRepository.findByRuleIdAndActiveTrueOrderBySortOrderAscIdAsc(rule.getId());
        if (active.isEmpty()) {
            return Optional.empty();
        }
        List<NotificationMediaAsset> candidates = new ArrayList<>(active);
        if (candidates.size() > 1) {
            Long lastAssetId = lastDelivery.map(NotificationMediaDelivery::getAssetId).orElse(null);
            if (lastAssetId != null) {
                candidates.removeIf(asset -> lastAssetId.equals(asset.getId()));
            }
        }
        NotificationMediaAsset selected = candidates.get(randomizer.index(candidates.size()));
        return Optional.of(new Selection(
                rule.getId(),
                selected.getId(),
                event.code(),
                selected.getImageUrl(),
                selected.getStorageKey(),
                selected.getOriginalFilename(),
                selected.getContentType()
        ));
    }

    public record Selection(
            Long ruleId,
            Long assetId,
            String eventCode,
            String imageUrl,
            String storageKey,
            String originalFilename,
            String contentType
    ) {
    }
}

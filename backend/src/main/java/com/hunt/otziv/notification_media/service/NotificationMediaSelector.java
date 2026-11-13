package com.hunt.otziv.notification_media.service;

import com.hunt.otziv.config.settings.api.ContextualMediaSettings;
import com.hunt.otziv.notification_media.api.StaffPictureContext;
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
import java.util.Set;
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
    private final ContextPictureCadence cadence;
    private final ContextualMediaSettings settings;

    @Transactional(readOnly = true)
    public Optional<Selection> select(String eventCode, long chatId, LocalDateTime now) {
        return select(eventCode, chatId, now, Set.of());
    }

    @Transactional(readOnly = true)
    public Optional<Selection> select(String eventCode, long chatId, LocalDateTime now, Set<StaffPictureContext> facts) {
        return decide(eventCode, chatId, now, facts).picture();
    }

    public record Decision(Optional<Selection> picture, boolean textTurn) {
        static Decision unavailable() { return new Decision(Optional.empty(), false); }
    }

    @Transactional(readOnly = true)
    public Decision decide(String eventCode, long chatId, LocalDateTime now, Set<StaffPictureContext> facts) {
        NotificationMediaEventCatalog event = NotificationMediaEventCatalog.find(eventCode).orElse(null);
        if (event == null) {
            return Decision.unavailable();
        }
        NotificationMediaRule rule = ruleRepository.findByEventCode(event.code()).orElse(null);
        if (rule == null || !rule.isEnabled()
                || !randomizer.shouldAttach(rule.getImageProbabilityPercent())) {
            return Decision.unavailable();
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
            return Decision.unavailable();
        }

        List<NotificationMediaAsset> active =
                assetRepository.findByRuleIdAndActiveTrueOrderBySortOrderAscIdAsc(rule.getId());
        List<NotificationMediaAsset> ordinary = new ArrayList<>();
        List<NotificationMediaAsset> contextual = new ArrayList<>();
        for (NotificationMediaAsset asset : active) {
            if (asset.getContextCondition() == null) ordinary.add(asset);
            else if (facts.contains(asset.getContextCondition())) contextual.add(asset);
        }
        if (!contextual.isEmpty()) {
            if (!settings.enabled()) {
                contextual.clear();
            } else if (!cadence.takeSlot(chatId)) {
                return new Decision(Optional.empty(), true);
            }
        }
        if (ordinary.isEmpty() && contextual.isEmpty()) return Decision.unavailable();
        List<NotificationMediaAsset> candidates = new ArrayList<>(contextual);
        candidates.addAll(ordinary);
        if (candidates.size() > 1) {
            Long lastAssetId = lastDelivery.map(NotificationMediaDelivery::getAssetId).orElse(null);
            if (lastAssetId != null) {
                candidates.removeIf(asset -> lastAssetId.equals(asset.getId()));
            }
        }
        // Keep the original collection in rotation even if a rule has many older images.
        List<NotificationMediaAsset> matching = candidates.stream().filter(a -> a.getContextCondition() != null).toList();
        if (!matching.isEmpty() && matching.size() < candidates.size() && randomizer.shouldAttach(50)) {
            candidates = matching;
        }
        NotificationMediaAsset selected = candidates.get(randomizer.index(candidates.size()));
        return new Decision(Optional.of(new Selection(
                rule.getId(),
                selected.getId(),
                event.code(),
                selected.getImageUrl(),
                selected.getStorageKey(),
                selected.getOriginalFilename(),
                selected.getContentType()
        )), false);
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

package com.hunt.otziv.notification_media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.notification_media.model.NotificationMediaAsset;
import com.hunt.otziv.notification_media.model.NotificationMediaDelivery;
import com.hunt.otziv.notification_media.model.NotificationMediaRule;
import com.hunt.otziv.notification_media.model.NotificationRecipientType;
import com.hunt.otziv.notification_media.repository.NotificationMediaAssetRepository;
import com.hunt.otziv.notification_media.repository.NotificationMediaDeliveryRepository;
import com.hunt.otziv.notification_media.repository.NotificationMediaRuleRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationMediaSelectorTest {

    @Mock
    private NotificationMediaRuleRepository ruleRepository;
    @Mock
    private NotificationMediaAssetRepository assetRepository;
    @Mock
    private NotificationMediaDeliveryRepository deliveryRepository;
    @Mock
    private NotificationMediaRandomizer randomizer;

    private NotificationMediaSelector selector;

    @BeforeEach
    void setUp() {
        selector = new NotificationMediaSelector(
                ruleRepository,
                assetRepository,
                deliveryRepository,
                randomizer
        );
    }

    @Test
    void excludesLastSuccessfulAssetWhenSeveralVariantsExist() {
        String eventCode = NotificationMediaEventCatalog.WORKER_TASK_REPEAT.code();
        NotificationMediaRule rule = rule(eventCode, 0);
        NotificationMediaAsset first = asset(rule, 11L, "https://cdn/first.png");
        NotificationMediaAsset second = asset(rule, 12L, "https://cdn/second.png");
        NotificationMediaDelivery last = delivery(eventCode, 11L, LocalDateTime.of(2026, 7, 30, 10, 0));

        when(ruleRepository.findByEventCode(eventCode)).thenReturn(Optional.of(rule));
        when(randomizer.shouldAttach(100)).thenReturn(true);
        when(deliveryRepository.findFirstByEventCodeAndChatIdAndPhotoSentTrueOrderBySentAtDesc(eventCode, -100L))
                .thenReturn(Optional.of(last));
        when(assetRepository.findByRuleIdAndActiveTrueOrderBySortOrderAscIdAsc(7L))
                .thenReturn(List.of(first, second));
        when(randomizer.index(1)).thenReturn(0);

        Optional<NotificationMediaSelector.Selection> selected =
                selector.select(eventCode, -100L, LocalDateTime.of(2026, 7, 30, 12, 0));

        assertThat(selected).isPresent();
        assertThat(selected.orElseThrow().assetId()).isEqualTo(12L);
        assertThat(selected.orElseThrow().imageUrl()).isEqualTo("https://cdn/second.png");
        verify(randomizer).index(1);
    }

    @Test
    void respectsCooldownForTheSameEventAndChat() {
        String eventCode = NotificationMediaEventCatalog.MANAGER_REPORT_REMINDER.code();
        NotificationMediaRule rule = rule(eventCode, 60);
        NotificationMediaDelivery last = delivery(eventCode, 11L, LocalDateTime.of(2026, 7, 30, 11, 30));

        when(ruleRepository.findByEventCode(eventCode)).thenReturn(Optional.of(rule));
        when(randomizer.shouldAttach(100)).thenReturn(true);
        when(deliveryRepository.findFirstByEventCodeAndChatIdAndPhotoSentTrueOrderBySentAtDesc(eventCode, -200L))
                .thenReturn(Optional.of(last));

        Optional<NotificationMediaSelector.Selection> selected =
                selector.select(eventCode, -200L, LocalDateTime.of(2026, 7, 30, 12, 0));

        assertThat(selected).isEmpty();
    }

    @Test
    void disabledRuleKeepsTextOnlyDelivery() {
        String eventCode = NotificationMediaEventCatalog.WORKER_TASK_FIRST.code();
        NotificationMediaRule rule = rule(eventCode, 0);
        rule.setEnabled(false);
        when(ruleRepository.findByEventCode(eventCode)).thenReturn(Optional.of(rule));

        assertThat(selector.select(eventCode, -100L, LocalDateTime.now())).isEmpty();
    }

    private NotificationMediaRule rule(String eventCode, int cooldownMinutes) {
        NotificationMediaRule rule = new NotificationMediaRule();
        rule.setId(7L);
        rule.setEventCode(eventCode);
        rule.setRecipientType(NotificationRecipientType.WORKER);
        rule.setEnabled(true);
        rule.setImageProbabilityPercent(100);
        rule.setCooldownMinutes(cooldownMinutes);
        return rule;
    }

    private NotificationMediaAsset asset(NotificationMediaRule rule, Long id, String url) {
        NotificationMediaAsset asset = new NotificationMediaAsset();
        asset.setId(id);
        asset.setRule(rule);
        asset.setImageUrl(url);
        asset.setActive(true);
        return asset;
    }

    private NotificationMediaDelivery delivery(String eventCode, Long assetId, LocalDateTime sentAt) {
        NotificationMediaDelivery delivery = new NotificationMediaDelivery();
        delivery.setEventCode(eventCode);
        delivery.setAssetId(assetId);
        delivery.setChatId(-100L);
        delivery.setPhotoSent(true);
        delivery.setSentAt(sentAt);
        return delivery;
    }
}

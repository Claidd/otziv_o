package com.hunt.otziv.notification_media.repository;

import com.hunt.otziv.notification_media.model.NotificationMediaDelivery;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationMediaDeliveryRepository extends JpaRepository<NotificationMediaDelivery, Long> {
    Optional<NotificationMediaDelivery> findFirstByEventCodeAndChatIdAndPhotoSentTrueOrderBySentAtDesc(
            String eventCode,
            Long chatId
    );
}

package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.client_messages.api.DeliveryQueueHealth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Admin notices about unlinked WhatsApp groups that are due and not yet accepted by Telegram. Kept apart
 * from the notifier, so the queue metrics and monitoring never depend on the Telegram bot.
 */
@Component
@RequiredArgsConstructor
public class WhatsAppUnlinkedNoticeQueueHealth implements DeliveryQueueHealth {
    private final WhatsAppUnlinkedEventStore store;

    @Override
    public String queueName() {
        return "whatsapp_unlinked_notice";
    }

    @Override
    public List<Row> deliveryQueueHealth() {
        return store.noticeQueueHealth();
    }
}

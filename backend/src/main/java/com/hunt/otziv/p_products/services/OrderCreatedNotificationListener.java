package com.hunt.otziv.p_products.services;

import com.hunt.otziv.mobile_push.service.MobilePushBusinessNotificationService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
@RequiredArgsConstructor
public class OrderCreatedNotificationListener {

    private final OrderRepository orderRepository;
    private final TelegramService telegramService;
    private final MobilePushBusinessNotificationService mobilePushBusinessNotificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void notifyWorker(OrderCreatedEvent event) {
        if (event == null || event.orderId() == null) {
            return;
        }

        try {
            orderRepository.findByIdForOrderDto(event.orderId()).ifPresentOrElse(
                    this::notifyWorker,
                    () -> log.warn("Уведомление о новом заказе не отправлено: заказ {} не найден", event.orderId())
            );
        } catch (RuntimeException e) {
            log.warn("Уведомление о новом заказе {} не отправлено после коммита", event.orderId(), e);
        }
    }

    private void notifyWorker(Order order) {
        log.info("Отправляем уведомление работнику после коммита заказа {}...", order.getId());

        if (order.getWorker() != null && order.getWorker().getUser() != null) {
            Long chatId = order.getWorker().getUser().getTelegramChatId();
            if (chatId != null) {
                String msg = "У вас новый заказ для: " + companyTitle(order);
                try {
                    telegramService.sendMessage(chatId, msg);
                    log.info("Уведомление о новом заказе {} отправлено работнику (ChatID: {})", order.getId(), chatId);
                } catch (Exception e) {
                    log.error("Ошибка при отправке уведомления о новом заказе {} работнику: {}", order.getId(), e.getMessage());
                }
            } else {
                log.warn("У работника ID {} не указан chatId в Telegram", order.getWorker().getId());
            }
        } else {
            log.warn("У заказа {} нет работника или пользователя для уведомления", order.getId());
        }

        mobilePushBusinessNotificationService.notifyWorkerNewOrder(order);
    }

    private String companyTitle(Order order) {
        return order.getCompany() == null || order.getCompany().getTitle() == null
                ? "компании без названия"
                : order.getCompany().getTitle();
    }
}

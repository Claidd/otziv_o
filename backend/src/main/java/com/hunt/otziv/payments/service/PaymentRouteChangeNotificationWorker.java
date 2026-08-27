package com.hunt.otziv.payments.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.service.OrderStatusNotificationService;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.u_users.model.Manager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentRouteChangeNotificationWorker {

    private static final String ISSUE_SOURCE = "PAYMENT_ROUTE_CHANGE_DELIVERY";

    private final OrderRepository orderRepository;
    private final OrderStatusNotificationService notificationService;
    private final PaymentIssueReminderService paymentIssueReminderService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void send(Long orderId, Long paymentLinkId, ManagerPaymentLinkResponse payment) {
        try {
            Order order = orderRepository.findByIdForMutation(orderId).orElse(null);
            if (order == null || payment == null) {
                notifyFailure(orderId, paymentLinkId, "Заказ или новый счет не найден");
                return;
            }
            Company company = order.getCompany();
            Manager manager = order.getManager() != null
                    ? order.getManager()
                    : company == null ? null : company.getManager();
            String message = "По вашей просьбе способ оплаты изменен. Используйте новые реквизиты.\n\n"
                    + payment.copyText();
            boolean sent = notificationService.sendInformationalMessageToClientChat(
                    order,
                    manager == null ? null : manager.getClientId(),
                    company == null ? null : company.getGroupId(),
                    message,
                    "Новые реквизиты оплаты",
                    payment.telegramCopyTransferNumber()
            );
            if (!sent) {
                notifyFailure(orderId, paymentLinkId, "Активный клиентский чат недоступен");
            }
        } catch (RuntimeException exception) {
            log.error("Не удалось отправить новые реквизиты: orderId={}, paymentLinkId={}",
                    orderId, paymentLinkId, exception);
            notifyFailure(orderId, paymentLinkId, exception.getMessage());
        }
    }

    private void notifyFailure(Long orderId, Long paymentLinkId, String reason) {
        paymentIssueReminderService.notifyOrderIssue(
                orderId,
                ISSUE_SOURCE,
                paymentLinkId,
                "Новые реквизиты не отправлены: заказ №" + orderId,
                "Способ оплаты изменен, но сообщение с новыми реквизитами не доставлено клиенту. "
                        + "Откройте чат и отправьте реквизиты вручную. Причина: "
                        + (reason == null || reason.isBlank() ? "неизвестна" : reason)
        );
    }
}

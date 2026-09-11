package com.hunt.otziv.p_products.status.service;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.dto.TelegramTransferCopyButton;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderStatusService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.service.WhatsAppAuthAlertService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.hasDetails;

/** Owns order outcomes; channel dispatch and frozen transport envelopes belong to ClientChatMessageSender. */
@Service @Slf4j @RequiredArgsConstructor
public class OrderStatusNotificationService {
    private static final String STATUS_TO_CHECK="В проверку", STATUS_PUBLIC="Опубликовано";
    private final OrderRepository orderRepository;
    private final OrderStatusService orderStatusService;
    private final TelegramService telegramService;
    private final WhatsAppAuthAlertService whatsAppAuthAlertService;
    private final OrderNotificationOccurrences occurrences;
    private final ClientMessageDelivery sender;

    /** Scalar publication envelope captured inside the existing actor-checked business transaction. */
    public PreparedPublicationProgress preparePublicationProgress(Order order,String clientId,String groupId,
            String message,boolean controls,String occurrence) {
        String kind = "progress:" + occurrence;
        String operation = occurrences.reserveInCurrentTransaction(order.getId(), kind, 1);
        var company = order.getCompany();
        var target = company == null ? null : new ClientMessageDelivery.Target(company.getId(), company.getTitle(),
                company.getUrlChat(), company.getTelegramGroupChatId(), company.getMaxGroupChatId());
        return new PreparedPublicationProgress(order.getId(), kind, operation, target, clientId, groupId, message, controls,
                WhatsAppAuthAlertService.captureRecipients(order.getManager() == null ? List.of() : List.of(order.getManager())));
    }

    public ClientMessageSendResult dispatchPublicationProgress(PreparedPublicationProgress prepared) {
        if (prepared.publicationStarted()) {
            return sender.deliverWithOperationId(prepared.target(), prepared.clientId(), prepared.groupId(),
                    prepared.message(), null, prepared.operationId());
        }
        return sender.deliverPublicationProgressWithOperationId(prepared.target(), prepared.clientId(), prepared.groupId(),
                prepared.message(), prepared.includePreferenceControls(), prepared.operationId());
    }

    public void notifyPublicationProgressOutcome(PreparedPublicationProgress prepared, ClientMessageSendResult outcome) {
        if (outcome.sent() && "WhatsApp".equals(outcome.channel())) {
            whatsAppAuthAlertService.notifyRecoveredSnapshot(prepared.clientId(), "моментальная отправка клиенту",
                    LocalDateTime.now().withNano(0), prepared.recipients());
        } else if (!outcome.sent() && isWhatsAppAuthUnavailable(outcome.errorCode(), outcome.errorMessage())) {
            whatsAppAuthAlertService.notifyAuthIssueSnapshot(prepared.clientId(), prepared.target() == null ? null : prepared.target().title(),
                    "моментальная отправка клиенту", outcome.errorCode(), outcome.errorMessage(),
                    LocalDateTime.now().withNano(0), null, prepared.recipients());
        }
    }

    public record PreparedPublicationProgress(Long orderId, String kind, String operationId, ClientMessageDelivery.Target target,
            String clientId, String groupId, String message, boolean includePreferenceControls,
            List<WhatsAppAuthAlertService.Recipient> recipients) {
        public boolean publicationStarted() { return kind != null && kind.startsWith("progress:publication-start:"); }
    }

    /** Called under the order's mutation lock; transport data is frozen before commit. */
    public PreparedAction prepareAction(String title, Order order, String clientId, String groupId,
            String message, String successStatus, String copy) {
        String kind = "action:" + title + ":" + successStatus;
        String operation = occurrences.reserve(order.getId(), kind, order.getClientMessageGeneration());
        var company = order.getCompany();
        var target = company == null ? null : new ClientMessageDelivery.Target(company.getId(), company.getTitle(),
                company.getUrlChat(), company.getTelegramGroupChatId(), company.getMaxGroupChatId());
        String fallback = null;
        if (hasDetails(order) && company != null) {
            if (STATUS_TO_CHECK.equals(title)) fallback = companyTitle(order) + " готов - На проверку\nhttps://o-ogo.ru/orders/all_orders?status=В%20проверку";
            if (STATUS_PUBLIC.equals(title)) fallback = companyTitle(order) + " Опубликован\nhttps://o-ogo.ru/orders/all_orders?status=Опубликовано";
        }
        return new PreparedAction(order.getId(), title, successStatus, order.getClientMessageGeneration(), kind,
                operation, target, clientId, groupId, message, copy, managerTelegramChatId(order), fallback,
                WhatsAppAuthAlertService.captureRecipients(order.getManager() == null ? List.of() : List.of(order.getManager())));
    }

    public ClientMessageSendResult dispatchPreparedAction(PreparedAction prepared) {
        return sender.deliverWithOperationId(prepared.target(), prepared.clientId(), prepared.groupId(), prepared.message(),
                TelegramTransferCopyButton.fromFrozenTransferNumber(prepared.copy()).orElse(null), prepared.operationId());
    }

    /** Receipt bookkeeping and order mutation only; caller already holds the canonical order lock. */
    public boolean applyPreparedAction(Order order, PreparedAction prepared) {
        occurrences.confirm(prepared.orderId(), prepared.kind(), prepared.operationId());
        if (order == null || !Objects.equals(order.getId(), prepared.orderId())
                || order.getClientMessageGeneration() != prepared.generation()
                || order.getStatus() == null || !Objects.equals(order.getStatus().getTitle(), prepared.title())) return false;
        order.setStatus(orderStatusService.getOrderStatusByTitle(prepared.successStatus()));
        orderRepository.save(order);
        return true;
    }

    /** All manager/provider side effects run after the caller has released business locks. */
    public void notifyPreparedActionOutcome(PreparedAction prepared, ClientMessageSendResult outcome) {
        if (outcome.sent()) {
            if ("WhatsApp".equals(outcome.channel())) whatsAppAuthAlertService.notifyRecoveredSnapshot(prepared.clientId(),
                    "моментальная отправка клиенту", LocalDateTime.now().withNano(0), prepared.recipients());
        } else {
            if (isWhatsAppAuthUnavailable(outcome.errorCode(), outcome.errorMessage())) {
                whatsAppAuthAlertService.notifyAuthIssueSnapshot(prepared.clientId(), prepared.target() == null ? null : prepared.target().title(),
                        "моментальная отправка клиенту", outcome.errorCode(), outcome.errorMessage(), LocalDateTime.now().withNano(0), null, prepared.recipients());
            }
            if (prepared.fallbackChatId() != null && prepared.fallbackText() != null) {
                try { telegramService.sendMessage(prepared.fallbackChatId(), prepared.fallbackText()); }
                catch (RuntimeException failure) { log.warn("Order retry manager notification failed: orderId={}", prepared.orderId(), failure); }
            }
        }
    }

    public record PreparedAction(Long orderId, String title, String successStatus, long generation, String kind,
            String operationId, ClientMessageDelivery.Target target, String clientId, String groupId, String message,
            String copy, Long fallbackChatId, String fallbackText, List<WhatsAppAuthAlertService.Recipient> recipients) {}

    public boolean sendMessageToGroup(String title,Order order,String clientId,String groupId,String message,String successStatus) {
        return Objects.equals(sendMessageToClientChat(title,order,clientId,groupId,message,successStatus),successStatus);
    }
    public boolean sendMessageToGroup(String title,Order order,String clientId,String groupId,String message,String successStatus,String frozenTransferNumber) {
        return Objects.equals(sendMessageToClientChat(title,order,clientId,groupId,message,successStatus,frozenTransferNumber),successStatus);
    }
    public String sendMessageToClientChat(String title,Order order,String clientId,String groupId,String message,String successStatus) {
        return sendMessageToClientChat(title,order,clientId,groupId,message,successStatus,null);
    }
    public String sendMessageToClientChat(String title,Order order,String clientId,String groupId,String message,String successStatus,String frozenTransferNumber) {
        var outcome=dispatch(order,clientId,groupId,message,frozenTransferNumber,"action:"+title+":"+successStatus,
                order.getClientMessageGeneration(),false,false);
        String applied=outcome.sent()?successStatus:title;
        order.setStatus(orderStatusService.getOrderStatusByTitle(applied));
        if(!outcome.sent())notifyManagerAboutFallback(title,order);
        orderRepository.save(order);
        return applied;
    }
    public boolean sendInformationalMessageToClientChat(Order order,String clientId,String groupId,String message,String actionTitle) {
        return sendInformationalMessageToClientChat(order,clientId,groupId,message,actionTitle,null);
    }
    public boolean sendInformationalMessageToClientChat(Order order,String clientId,String groupId,String message,String actionTitle,String frozenTransferNumber) {
        return dispatch(order,clientId,groupId,message,frozenTransferNumber,"information:"+actionTitle,
                order.getClientMessageGeneration(),false,false).sent();
    }
    public boolean sendInformationalForOccurrence(Order order,String clientId,String groupId,String message,String actionTitle,String frozenTransferNumber,String occurrence) {
        if(occurrence==null||occurrence.isBlank())return false;
        return dispatch(order,clientId,groupId,message,frozenTransferNumber,"information:"+actionTitle+":"+occurrence,1,false,false).sent();
    }
    public boolean sendProgressMessageToClientChat(Order order,String clientId,String groupId,String message) {
        return sendProgressMessageToClientChat(order,clientId,groupId,message,true);
    }
    public boolean sendProgressMessageToClientChat(Order order,String clientId,String groupId,String message,boolean includePreferenceControls) {
        return dispatch(order,clientId,groupId,message,null,"progress:"+order.getCounter(),order.getClientMessageGeneration(),true,includePreferenceControls).sent();
    }
    public boolean sendPublicationProgressForOccurrence(Order order,String clientId,String groupId,String message,boolean includePreferenceControls,String occurrence) {
        if(occurrence==null||occurrence.isBlank())return false;
        return dispatch(order,clientId,groupId,message,null,"progress:"+occurrence,1,true,includePreferenceControls).sent();
    }

    private ClientMessageSendResult dispatch(Order order,String clientId,String groupId,String message,String copy,String kind,long generation,boolean progress,boolean controls) {
        if(order==null||order.getId()==null)return ClientMessageSendResult.failed("order_missing","Заказ не найден");
        try {
            String operation=occurrences.reserve(order.getId(),kind,generation);
            var company=order.getCompany();
            var target=company==null?null:new ClientMessageDelivery.Target(company.getId(),company.getTitle(),company.getUrlChat(),company.getTelegramGroupChatId(),company.getMaxGroupChatId());
            ClientMessageSendResult result=progress?sender.deliverPublicationProgressWithOperationId(target,clientId,groupId,message,controls,operation):
                    sender.deliverWithOperationId(target,clientId,groupId,message,TelegramTransferCopyButton.fromFrozenTransferNumber(copy).orElse(null),operation);
            if(result.sent()) {
                occurrences.confirm(order.getId(),kind,operation);
                if("WhatsApp".equals(result.channel()))whatsAppAuthAlertService.notifyRecovered(clientId,"моментальная отправка клиенту",
                        LocalDateTime.now().withNano(0),order.getManager()==null?List.of():List.of(order.getManager()));
            } else if(isWhatsAppAuthUnavailable(result.errorCode(),result.errorMessage())) {
                whatsAppAuthAlertService.notifyAuthIssue(clientId,companyTitle(order),"моментальная отправка клиенту",result.errorCode(),
                        result.errorMessage(),LocalDateTime.now().withNano(0),null,order.getManager()==null?List.of():List.of(order.getManager()));
            }
            return result;
        } catch(RuntimeException unconfirmed) {
            log.warn("Order notification result unconfirmed: orderId={}, kind={}, error={}",order.getId(),kind,unconfirmed.getClass().getSimpleName());
            return ClientMessageSendResult.failed("operation_unknown","Отправка не подтверждена; сохранённая операция удерживается до сверки");
        }
    }

    private boolean isWhatsAppAuthUnavailable(String code, String readable) {
        String normalized = ((code == null ? "" : code) + " " + (readable == null ? "" : readable))
                .toLowerCase(Locale.ROOT);
        return normalized.contains("authenticated=false")
                || normalized.contains("\"authenticated\":false")
                || normalized.contains("\"authenticated\": false")
                || normalized.contains("\"state\":\"qr\"")
                || normalized.contains("\"state\": \"qr\"")
                || normalized.contains("\"hasqr\":true")
                || normalized.contains("\"hasqr\": true")
                || normalized.contains("scan it")
                || normalized.contains("не авториз");
    }

    public boolean hasWorkerWithTelegram(Order order) {
        try {
            return order != null
                    && order.getWorker() != null
                    && order.getWorker().getUser() != null
                    && order.getWorker().getUser().getWorkerTelegramGroupChatId() != null
                    && hasDetails(order)
                    && order.getCompany() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void notifyManagerAboutFallback(String title, Order order) {
        try {
            Long managerChatId = managerTelegramChatId(order);
            if (managerChatId == null || !hasDetails(order) || order == null || order.getCompany() == null) {
                return;
            }

            if (STATUS_TO_CHECK.equals(title)) {
                String url = "https://o-ogo.ru/orders/all_orders?status=В%20проверку";
                String text = companyTitle(order) + " готов - На проверку\n" + url;
                telegramService.sendMessage(managerChatId, text);
                log.info("📬 Уведомление менеджеру отправлено в Telegram: {} → В проверку", managerChatId);
            }

            if (STATUS_PUBLIC.equals(title)) {
                String url = "https://o-ogo.ru/orders/all_orders?status=Опубликовано";
                String text = companyTitle(order) + " Опубликован\n" + url;
                telegramService.sendMessage(managerChatId, text);
                log.info("📬 Уведомление менеджеру отправлено в Telegram: {} → Опубликовано", managerChatId);
            }
        } catch (Exception e) {
            log.warn("Fallback-уведомление менеджеру не отправлено. Статус заказа продолжит меняться, orderId={}",
                    order != null ? order.getId() : null, e);
        }
    }

    private Long managerTelegramChatId(Order order) {
        try {
            return order != null && order.getManager() != null && order.getManager().getUser() != null
                    ? order.getManager().getUser().getTelegramChatId()
                    : null;
        } catch (Exception e) {
            log.warn("Не удалось прочитать Telegram chatId менеджера для fallback-уведомления, orderId={}",
                    order != null ? order.getId() : null, e);
            return null;
        }
    }

    private String companyTitle(Order order) {
        return order.getCompany() != null ? order.getCompany().getTitle() : "Компания";
    }

}

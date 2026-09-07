package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.common_billing.service.CommonInvoicePublicationBlockerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * Runs one invoice repair outside the daily-control transaction. Billing operations retain their
 * own transactions/locks; only a confirmed outcome returns to the caller that resolves the card.
 */
@Service
@RequiredArgsConstructor
public class ManagerControlInvoiceRepairWorkflow {
    private final CommonInvoiceRepository commonInvoiceRepository;
    private final CommonInvoiceOrderRepository commonInvoiceOrderRepository;
    private final CommonInvoicePublicationBlockerService commonInvoicePublicationBlockerService;
    private final CommonBillingService commonBillingService;
    private final ManagerControlInvoiceDiagnostics invoiceDiagnostics;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Outcome repair(Long invoiceId) {
        CommonInvoice invoice = commonInvoiceRepository.findByIdWithAccount(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        if (invoice.getStatus() == CommonInvoiceStatus.COLLECTING
                && commonInvoicePublicationBlockerService.hasOverdueBlockers(
                commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId),
                LocalDateTime.now()
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Это не техническая ошибка счета: один или несколько заказов отстают от публикации более 48 часов. "
                            + "Откройте карточки блокеров и проверьте клиента/автоответчик. Состав общего счета автоматически не меняется."
            );
        }
        if (invoice.getStatus() == CommonInvoiceStatus.COLLECTING
                || invoice.getStatus() == CommonInvoiceStatus.READY) {
            CommonInvoiceDetailsResponse details = commonBillingService.invoice(invoiceId);
            boolean sent = false;
            if (details != null
                    && details.summary() != null
                    && CommonInvoiceStatus.COLLECTING.name().equals(details.summary().status())
                    && details.orders() != null
                    && details.orders().stream().anyMatch(order ->
                    !order.ready() && Set.of("В проверку", "На проверке").contains(safe(order.orderStatus())))) {
                details = commonBillingService.approveReviewOrders(invoiceId);
                details = commonBillingService.invoice(invoiceId);
            }
            if (details != null
                    && details.summary() != null
                    && CommonInvoiceStatus.READY.name().equals(details.summary().status())) {
                details = commonBillingService.sendInvoice(invoiceId, true);
                sent = true;
            }
            String status = details == null || details.summary() == null
                    ? ""
                    : safe(details.summary().status());
            String lastError = details == null || details.summary() == null
                    ? ""
                    : safe(details.summary().lastError());
            if (CommonInvoiceStatus.COLLECTING.name().equals(status)) {
                int ready = details.summary().readyOrders();
                int total = details.summary().totalOrders();
                if (total == 0) {
                    CommonInvoiceDetailsResponse disabled = commonBillingService.disableEmptyInvoice(invoiceId);
                    String disabledStatus = disabled == null || disabled.summary() == null
                            ? ""
                            : safe(disabled.summary().status());
                    if (!CommonInvoiceStatus.DISABLED.name().equals(disabledStatus)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Пустой общий счет не перешел в безопасное отключенное состояние"
                        );
                    }
                    return new Outcome(
                            "Пустой технический счет отключен: заказов и платежных признаков нет",
                            "Отключен пустой технический хвост общего счета"
                    );
                }
                return new Outcome(
                        "Счет исправен и остается в сборе: " + Math.max(0, total - ready)
                                + " из " + total + " заказов еще в работе. Карточка убрана из замечаний.",
                        "Исключен исправный общий счет с незавершенными заказами"
                );
            }
            if (!lastError.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Счет обработан, но автоматическая отправка не прошла: " + limit(lastError, 220)
                );
            }
            if (!sent) {
                if (Set.of(
                        CommonInvoiceStatus.INVOICED.name(),
                        CommonInvoiceStatus.REMINDER.name(),
                        CommonInvoiceStatus.PARTIALLY_PAID.name()
                ).contains(status)) {
                    return new Outcome(
                            "Общий счет уже был отправлен клиенту; карточка контроля перепроверена",
                            "Перепроверен уже отправленный общий счет"
                    );
                }
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Счет пересчитан, но не перешел в состояние, из которого его можно отправить"
                );
            }
            return new Outcome(
                    "Позиции общего счета пересчитаны, готовые заказы одобрены, счет отправлен клиенту",
                    "Пересчитан и отправлен зависший общий счет"
            );
        }
        if (invoice.getStatus() == CommonInvoiceStatus.INVOICED
                || invoice.getStatus() == CommonInvoiceStatus.REMINDER
                || invoice.getStatus() == CommonInvoiceStatus.PARTIALLY_PAID) {
            CommonInvoiceDetailsResponse details = commonBillingService.sendManualReminder(invoiceId);
            String lastError = details == null || details.summary() == null ? "" : safe(details.summary().lastError());
            if (!lastError.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Напоминание по общему счету не отправлено: " + limit(lastError, 220)
                );
            }
            return new Outcome(
                    "Клиенту отправлено напоминание по зависшему общему счету",
                    "Повторно отправлено напоминание по общему счету"
            );
        }
        if (invoiceDiagnostics.commonInvoiceStandaloneRouteRepairable(invoice)) {
            CommonInvoiceDetailsResponse details = commonBillingService.repairStandalonePaymentRouteConflict(invoiceId);
            String lastError = details == null || details.summary() == null ? "" : safe(details.summary().lastError());
            if (lastError.toLowerCase(Locale.ROOT).startsWith("standalone_payment_route_conflict")) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Автопочинка остановлена: отдельный платеж начат или его состояние неоднозначно. "
                                + "Проверьте T-Bank и ручные поступления."
                );
            }
            return new Outcome(
                    "Одиночные платежи сверены, неинициализированные ссылки закрыты, общий счет пересчитан и отправлен заново",
                    "Восстановлен единый платежный маршрут общего счета"
            );
        }
        if (invoiceDiagnostics.commonInvoiceUnsentTlsInitRepairable(invoice)) {
            CommonInvoiceDetailsResponse details = commonBillingService.recoverUnsentPaymentInitTlsFailure(invoiceId);
            String recoveredStatus = details == null || details.summary() == null
                    ? ""
                    : safe(details.summary().status());
            String recoveredError = details == null || details.summary() == null
                    ? ""
                    : safe(details.summary().lastError());
            if (!recoveredError.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "TLS-сбой снят, но счет остался с ошибкой: " + limit(recoveredError, 180)
                );
            }
            if (CommonInvoiceStatus.COLLECTING.name().equals(recoveredStatus)) {
                return new Outcome(
                        "TLS-сбой T-Bank снят; общий счет безопасно возвращен в сбор",
                        "Безопасно снят TLS-сбой создания платежной ссылки"
                );
            }
            boolean shouldSend = CommonInvoiceStatus.READY.name().equals(recoveredStatus)
                    || CommonInvoiceStatus.PARTIALLY_PAID.name().equals(recoveredStatus);
            if (shouldSend) {
                details = commonBillingService.sendInvoice(invoiceId, true);
            }
            String status = details == null || details.summary() == null ? "" : safe(details.summary().status());
            String lastError = details == null || details.summary() == null ? "" : safe(details.summary().lastError());
            if (!lastError.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "TLS-сбой снят, но повторная отправка счета не прошла: " + limit(lastError, 180)
                );
            }
            if (!shouldSend) {
                if (Set.of(
                        CommonInvoiceStatus.PAID.name(),
                        CommonInvoiceStatus.ARCHIVED.name(),
                        CommonInvoiceStatus.DISABLED.name()
                ).contains(recoveredStatus)) {
                    return new Outcome(
                            "TLS-сбой T-Bank снят; повторная отправка не требуется, счет перешел в статус «"
                                    + invoiceDiagnostics.commonInvoiceStatusLabel(CommonInvoiceStatus.valueOf(recoveredStatus)) + "»",
                            "Безопасно снят TLS-сбой без повторной отправки закрытого счета"
                    );
                }
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "TLS-сбой снят, но счет не перешел в состояние для безопасной повторной отправки"
                );
            }
            if (details == null || details.summary() == null) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "TLS-сбой снят, но результат повторной отправки счета не подтвержден"
                );
            }
            return new Outcome(
                    "TLS-сбой T-Bank снят; новая платежная ссылка создана и счет повторно отправлен клиенту",
                    "Безопасно повторено создание платежной ссылки после сбоя TLS до отправки запроса"
            );
        }
        if (invoiceDiagnostics.commonInvoiceMessageSendRepairable(invoice)) {
            CommonInvoiceDetailsResponse details = commonBillingService.sendInvoice(invoiceId, true);
            String lastError = details == null || details.summary() == null ? "" : safe(details.summary().lastError());
            if (!lastError.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Повторная отправка общего счета не прошла: " + limit(lastError, 180)
                );
            }
            return new Outcome(
                    "Общий счет повторно отправлен клиенту",
                    "Повторно отправлен общий счет после ошибки клиентского чата"
            );
        }
        if (invoiceDiagnostics.commonInvoicePaymentNotificationRepairable(invoice)) {
            commonBillingService.resolvePaymentSuccessNotification(invoiceId);
            return new Outcome(
                    "Ошибка уведомления об оплате закрыта",
                    "Закрыта ошибка уведомления об оплате общего счета"
            );
        }
        if (invoiceDiagnostics.commonInvoiceReviewApprovalRepairable(invoice)) {
            CommonInvoiceDetailsResponse details = commonBillingService.retryAttention(invoiceId);
            String lastError = details == null || details.summary() == null ? "" : safe(details.summary().lastError());
            if (!lastError.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Повторное одобрение не прошло: " + limit(lastError, 180)
                );
            }
            return new Outcome(
                    "Даты назначены, заказы общего счета переведены в публикацию",
                    "Повторно выполнено массовое одобрение с назначением дат"
            );
        }
        if (invoiceDiagnostics.commonInvoiceNextOrderRepairable(invoice)) {
            CommonInvoiceDetailsResponse details = commonBillingService.retryAttention(invoiceId);
            String lastError = details == null || details.summary() == null ? "" : safe(details.summary().lastError());
            if (!lastError.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Повторное создание следующих заказов не прошло: " + limit(lastError, 180)
                );
            }
            return new Outcome(
                    "Повторное создание следующих заказов запущено",
                    "Повторно обработан общий счет после ошибки создания следующих заказов"
            );
        }
        if (invoiceDiagnostics.commonInvoiceWhatsappGroupTailRepairable(invoice)) {
            commonBillingService.resolveWhatsappGroupTail(invoiceId);
            return new Outcome(
                    "Старый хвост WhatsApp groupId скрыт из контроля",
                    "Закрыта устаревшая ошибка WhatsApp groupId общего счета"
            );
        }
        if (invoiceDiagnostics.commonInvoiceTechnicalTailRepairable(invoice)) {
            commonBillingService.resolveTechnicalTail(invoiceId);
            return new Outcome(
                    "Технический хвост общего счета скрыт из контроля",
                    "Закрыт технический хвост общего счета"
            );
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Эту ошибку общего счета нельзя исправить автоматически. Откройте счет и проверьте позиции вручную."
        );
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    public record Outcome(String comment, String eventDescription) {
    }
}

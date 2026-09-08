package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.common_billing.service.CommonPaymentInitFailureClassifier;
import com.hunt.otziv.common_billing.service.CommonInvoicePublicationBlockerService;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.StandaloneBankPaymentPolicy;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Reads invoice control problems and explains the permitted recovery actions. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerControlInvoiceDiagnostics {
    private final CommonInvoiceRepository commonInvoiceRepository;
    private final CommonInvoiceOrderRepository commonInvoiceOrderRepository;
    private final CommonInvoicePublicationBlockerService commonInvoicePublicationBlockerService;
    private final PaymentLinkRepository paymentLinkRepository;
    private final AppSettingService appSettingService;

    private static final int COMMON_INVOICE_STALE_DAYS = 3;

    private static final int PAPER_INVOICE_DELIVERY_SLA_HOURS = 24;

    private static final int COMMON_INVOICE_PUBLICATION_BLOCKER_HOURS =
            CommonInvoicePublicationBlockerService.ATTENTION_AFTER_HOURS;

    private static final Set<CommonInvoiceStatus> PAPER_INVOICE_DELIVERY_OPEN_STATUSES = Set.of(
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID
    );

    private static final Set<CommonInvoiceStatus> COMMON_INVOICE_CRITICAL_STATUSES = Set.of(
            CommonInvoiceStatus.NEEDS_ATTENTION,
            CommonInvoiceStatus.UNPAID,
            CommonInvoiceStatus.BAN
    );

    private static final Set<CommonInvoiceStatus> COMMON_INVOICE_STALE_STATUSES = Set.of(
            CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID
    );

    String specialistName(Long invoiceId) {
        if (invoiceId == null) {
            return "";
        }
        try {
            List<String> names = commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId).stream()
                    .map(CommonInvoiceOrder::getOrder)
                    .map(order -> order == null || order.getWorker() == null ? null : order.getWorker().getUser())
                    .map(this::userDisplayName)
                    .filter(name -> !name.isBlank())
                    .distinct()
                    .toList();
            if (names.size() == 1) {
                return names.getFirst();
            }
            if (names.size() > 1) {
                return names.size() + " специалистов";
            }
            return "";
        } catch (RuntimeException exception) {
            log.warn("Не удалось получить специалистов общего счета invoiceId={}: {}", invoiceId, exception.getMessage());
            return "";
        }
    }

    long countActions(Manager manager, Set<Long> excludedInvoiceIds) {
        if (excludedInvoiceIds == null || excludedInvoiceIds.isEmpty()) {
            return countActions(manager);
        }
        return findActionInvoices(manager).stream()
                .filter(invoice -> !excludedInvoiceIds.contains(invoice.getId()))
                .count();
    }

    long countActions(Manager manager) {
        return commonInvoiceRepository.countManagerControlInvoices(
                manager,
                COMMON_INVOICE_CRITICAL_STATUSES,
                effectiveCommonInvoiceStaleStatuses(),
                CommonInvoiceStatus.PARTIALLY_PAID,
                CommonInvoiceStatus.COLLECTING,
                LocalDateTime.now().minusDays(COMMON_INVOICE_STALE_DAYS),
                LocalDateTime.now().minusHours(COMMON_INVOICE_PUBLICATION_BLOCKER_HOURS),
                InvoicePaymentMode.OWNER_PAPER_INVOICE,
                PAPER_INVOICE_DELIVERY_OPEN_STATUSES,
                LocalDateTime.now().minusHours(PAPER_INVOICE_DELIVERY_SLA_HOURS)
        );
    }

    private List<CommonInvoice> findActionInvoices(Manager manager) {
        return commonInvoiceRepository.findManagerControlInvoices(
                manager,
                COMMON_INVOICE_CRITICAL_STATUSES,
                effectiveCommonInvoiceStaleStatuses(),
                CommonInvoiceStatus.PARTIALLY_PAID,
                CommonInvoiceStatus.COLLECTING,
                LocalDateTime.now().minusDays(COMMON_INVOICE_STALE_DAYS),
                LocalDateTime.now().minusHours(COMMON_INVOICE_PUBLICATION_BLOCKER_HOURS),
                InvoicePaymentMode.OWNER_PAPER_INVOICE,
                PAPER_INVOICE_DELIVERY_OPEN_STATUSES,
                LocalDateTime.now().minusHours(PAPER_INVOICE_DELIVERY_SLA_HOURS),
                PageRequest.of(0, 10_000)
        );
    }

    void requireResolved(Long invoiceId) {
        if (invoiceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У карточки общего счета нет ID счета");
        }
        CommonInvoice invoice = commonInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        if (!isCommonInvoiceManagerControlProblem(invoice, LocalDateTime.now())) {
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Общий счет все еще требует внимания: статус «"
                        + commonInvoiceStatusLabel(invoice.getStatus())
                        + "». Обновите счет в правой панели или используйте «Починить», если кнопка доступна."
        );
    }

    private boolean isCommonInvoiceManagerControlProblem(CommonInvoice invoice, LocalDateTime now) {
        if (invoice == null) {
            return false;
        }
        if (hasText(invoice.getLastError()) || hasText(invoice.getPaymentSuccessNotificationError())) {
            return true;
        }
        CommonInvoiceStatus status = invoice.getStatus();
        if (COMMON_INVOICE_CRITICAL_STATUSES.contains(status)) {
            return true;
        }
        if (status == CommonInvoiceStatus.PARTIALLY_PAID
                && (invoice.getSentAt() == null || invoice.getNextReminderAt() == null)) {
            return true;
        }
        if (isPaperInvoiceDeliveryOverdue(invoice, now)) {
            return true;
        }
        List<CommonInvoiceOrder> invoiceItems = commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        if (status == CommonInvoiceStatus.COLLECTING
                && commonInvoicePublicationBlockerService.hasOverdueBlockers(invoiceItems, now)) {
            return true;
        }
        LocalDateTime updatedAt = invoice.getUpdatedAt();
        LocalDateTime staleBefore = (now == null ? LocalDateTime.now() : now).minusDays(COMMON_INVOICE_STALE_DAYS);
        if (!effectiveCommonInvoiceStaleStatuses().contains(status)
                || updatedAt == null
                || updatedAt.isAfter(staleBefore)) {
            return false;
        }
        if (status != CommonInvoiceStatus.PARTIALLY_PAID
                && status != CommonInvoiceStatus.COLLECTING) {
            return true;
        }
        return invoiceItems.stream()
                .noneMatch(item -> item != null && !item.isReady());
    }

    private boolean isPaperInvoiceDeliveryOverdue(CommonInvoice invoice, LocalDateTime now) {
        if (invoice == null
                || invoice.getInvoicePaymentMode() != InvoicePaymentMode.OWNER_PAPER_INVOICE
                || invoice.getSentAt() == null
                || invoice.getPaperInvoiceIssuedAt() != null
                || !PAPER_INVOICE_DELIVERY_OPEN_STATUSES.contains(invoice.getStatus())) {
            return false;
        }
        LocalDateTime deliveryDeadline = invoice.getSentAt().plusHours(PAPER_INVOICE_DELIVERY_SLA_HOURS);
        LocalDateTime checkTime = now == null ? LocalDateTime.now() : now;
        return !deliveryDeadline.isAfter(checkTime);
    }

    List<ManagerControlConcreteItemResponse> examples(Manager manager, LocalDate today, int limit, Set<Long> excludedInvoiceIds) {
        return findActionInvoices(manager).stream()
                .filter(invoice -> !excludedInvoiceIds.contains(invoice.getId()))
                .limit(limit)
                .map(invoice -> commonInvoiceExample(invoice, today))
                .toList();
    }

    private Set<CommonInvoiceStatus> effectiveCommonInvoiceStaleStatuses() {
        if (appSettingService.getBoolean(AppSettingService.MANAGER_CONTROL_COLLECTING_STALE_ENABLED, true)) {
            return COMMON_INVOICE_STALE_STATUSES;
        }
        return Set.of(
                CommonInvoiceStatus.READY,
                CommonInvoiceStatus.INVOICED,
                CommonInvoiceStatus.REMINDER,
                CommonInvoiceStatus.PARTIALLY_PAID
        );
    }

    private ManagerControlConcreteItemResponse commonInvoiceExample(CommonInvoice invoice, LocalDate today) {
        String accountName = invoice.getAccount() == null ? "" : safe(invoice.getAccount().getName());
        long remainingKopecks = Math.max(0, invoice.getAmountKopecks() - invoice.getPaidKopecks());
        List<CommonInvoiceOrder> items = commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        List<CommonInvoiceOrder> publicationBlockers = commonInvoicePublicationBlockerService.overdueBlockers(
                items,
                LocalDateTime.now()
        );
        LocalDateTime attentionStartedAt = publicationBlockers.stream()
                .map(CommonInvoiceOrder::getPublicationBlockerSince)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(invoice.getUpdatedAt());
        if (isPaperInvoiceDeliveryOverdue(invoice, LocalDateTime.now())) {
            attentionStartedAt = invoice.getSentAt();
        }
        return new ManagerControlConcreteItemResponse(
                null,
                "COMMON_INVOICE",
                invoice.getId(),
                safe(invoice.getTitle()).isBlank() ? "Общий счет #" + invoice.getId() : invoice.getTitle(),
                commonInvoiceSubtitle(accountName, invoice.getAmountKopecks(), remainingKopecks),
                isPaperInvoiceDeliveryOverdue(invoice, LocalDateTime.now())
                        ? "Нужно отправить счёт"
                        : publicationBlockers.isEmpty()
                        ? commonInvoiceStatusLabel(invoice.getStatus())
                        : "Требует внимания · блокеров " + publicationBlockers.size(),
                attentionStartedAt == null ? null : daysSince(attentionStartedAt.toLocalDate(), today),
                commonInvoiceReason(invoice, today, items, publicationBlockers),
                "/admin/common-billing?invoiceId=" + invoice.getId(),
                null,
                null,
                null,
                null,
                ManagerDailyControlItemStatus.OPEN.name(),
                null,
                null,
                null,
                null,
                null
        ).withSla(attentionStartedAt, null, null, null);
    }

    private String commonInvoiceSubtitle(String accountName, long amountKopecks, long remainingKopecks) {
        List<String> parts = new ArrayList<>();
        if (!accountName.isBlank()) {
            parts.add(accountName);
        }
        parts.add("сумма " + rubles(amountKopecks));
        if (remainingKopecks > 0) {
            parts.add("остаток " + rubles(remainingKopecks));
        }
        return String.join(" · ", parts);
    }

    private String commonInvoiceReason(
            CommonInvoice invoice,
            LocalDate today,
            List<CommonInvoiceOrder> items,
            List<CommonInvoiceOrder> publicationBlockers
    ) {
        String lastError = safe(invoice.getLastError());
        if (!lastError.isBlank()) {
            return commonInvoiceLastErrorReason(invoice, lastError, items);
        }
        String notificationError = safe(invoice.getPaymentSuccessNotificationError());
        if (!notificationError.isBlank()) {
            return commonInvoicePaymentNotificationReason(invoice, notificationError);
        }
        CommonInvoiceStatus status = invoice.getStatus();
        if (status == CommonInvoiceStatus.NEEDS_ATTENTION) {
            return "Счет требует ручного разбора. Рекомендация: откройте «Счет», проверьте позиции и выберите подходящее действие в правой панели.";
        }
        if (status == CommonInvoiceStatus.UNPAID) {
            return "Счет переведен в «Не оплачено». Рекомендация: проверьте, нужно ли вернуть позиции в работу или закрыть карточку контроля.";
        }
        if (status == CommonInvoiceStatus.BAN) {
            return "Счет в бане. Рекомендация: проверьте причину блокировки в карточке счета.";
        }
        if (isPaperInvoiceDeliveryOverdue(invoice, LocalDateTime.now())) {
            return "Почему в замечаниях: клиент уведомлён о завершении работ, но отправка бумажного счёта "
                    + "не подтверждена более 24 часов. Отправьте документ в клиентский чат, затем откройте "
                    + "«Детали» и нажмите «Счёт отправлен клиенту». До подтверждения клиентские напоминания "
                    + "и отметка оплаты заблокированы.";
        }
        if (publicationBlockers != null && !publicationBlockers.isEmpty()) {
            String blockers = publicationBlockers.stream()
                    .limit(5)
                    .map(item -> {
                        Order order = item.getOrder();
                        String orderId = order == null || order.getId() == null ? "?" : String.valueOf(order.getId());
                        long hours = item.getPublicationBlockerSince() == null
                                ? 0
                                : Math.max(0, Duration.between(item.getPublicationBlockerSince(), LocalDateTime.now()).toHours());
                        return "#" + orderId + " «" + orderStatusTitle(order) + "» (" + hours + " ч.)";
                    })
                    .collect(Collectors.joining(", "));
            long publicationOrLater = (items == null ? List.<CommonInvoiceOrder>of() : items).stream()
                    .map(CommonInvoiceOrder::getOrder)
                    .filter(commonInvoicePublicationBlockerService::isPublicationOrLater)
                    .count();
            return "Почему в замечаниях: в общем счете уже есть " + publicationOrLater
                    + " заказ(а) в «Публикации» или выше, но допубликационные позиции блокируют сбор более 48 часов. "
                    + "Блокеры: " + blockers
                    + ". Проверьте доставку напоминаний и состояние заказов. Состав счета автоматически не меняется.";
        }
        long ageDays = invoice.getUpdatedAt() == null ? 0 : daysSince(invoice.getUpdatedAt().toLocalDate(), today);
        if (status == CommonInvoiceStatus.COLLECTING) {
            long ready = items.stream().filter(CommonInvoiceOrder::isReady).count();
            String waitingStatuses = items.stream()
                    .filter(item -> !item.isReady())
                    .map(CommonInvoiceOrder::getOrder)
                    .filter(Objects::nonNull)
                    .map(this::orderStatusTitle)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .limit(4)
                    .collect(Collectors.joining(", "));
            return "Почему в замечаниях: общий счет уже " + ageDays
                    + " дн. находится в «Сборе» и еще не выставлен клиенту. Готово заказов: "
                    + ready + "/" + items.size()
                    + (waitingStatuses.isBlank() ? "" : "; не готовы статусы: " + waitingStatuses)
                    + ". Нажмите «Починить»: система пересчитает позиции и отправит счет, если все заказы действительно готовы.";
        }
        return "Счет завис в статусе «" + commonInvoiceStatusLabel(status) + "» " + ageDays
                + " дн. Нажмите «Починить»: система проверит текущий шаг и выполнит безопасное продолжение.";
    }

    private String commonInvoiceLastErrorReason(
            CommonInvoice invoice,
            String rawError,
            List<CommonInvoiceOrder> items
    ) {
        String error = safe(rawError).toLowerCase(Locale.ROOT);
        if (error.startsWith("manual_fix:") && error.contains("moved_to_invoice_")) {
            String targetInvoice = valueAfter(error, "moved_to_invoice_");
            return "Заказ уже перенесен в другой общий счет"
                    + (targetInvoice.isBlank() ? "" : " #" + targetInvoice)
                    + ". Это технический хвост старого счета. Рекомендация: нажмите «Починить», чтобы скрыть старую карточку из контроля.";
        }
        if (error.startsWith("merged_into:")) {
            String targetInvoice = valueAfter(error, "common_invoice_");
            return "Этот общий счет объединен с другим счетом"
                    + (targetInvoice.isBlank() ? "" : " #" + targetInvoice)
                    + ". Рекомендация: нажмите «Починить», чтобы скрыть старую карточку из контроля.";
        }
        if (error.startsWith("empty:")) {
            return "В общем счете больше нет заказов. Рекомендация: нажмите «Починить», чтобы убрать пустой счет из контроля.";
        }
        if (error.startsWith("disabled:")) {
            return "Общий счет отключен. Рекомендация: нажмите «Починить», если в нем не осталось неоплаченных позиций.";
        }
        if (error.startsWith("whatsapp_group_missing") || error.contains("whatsapp-групп")) {
            return commonInvoiceWhatsappGroupMissingReason(invoice, false);
        }
        if (error.startsWith("auto_send_disabled")) {
            return "Автоматическая отправка клиентских сообщений выключена. Рекомендация: включите моментальные сообщения или обработайте счет вручную.";
        }
        if (commonInvoiceMessageSendRepairable(invoice)) {
            return "Сообщение общего счета не отправлено в клиентский чат: " + limit(rawError, 160)
                    + ". Рекомендация: проверьте привязку чата и нажмите «Починить», чтобы повторить отправку.";
        }
        if (error.startsWith("message_send_stale") || error.startsWith("message_send_in_progress")) {
            return "Отправка сообщения по счету зависла. Рекомендация: откройте «Счет» и повторите отправку вручную.";
        }
        if (error.startsWith("payment_init")) {
            if (commonInvoiceUnsentTlsInitRepairable(invoice)) {
                if (commonInvoiceHasCompetingStandalonePayment(items)) {
                    return "Создание платежной ссылки остановилось на проверке сертификата, но у одного из заказов "
                            + "есть отдельный незавершенный платеж. Откройте «Счет» и сначала сверьте этот платеж вручную.";
                }
                return "Создание платежной ссылки остановилось на проверке сертификата до отправки запроса в T-Bank. "
                        + "Сертификат уже доступен текущему backend. Рекомендация: нажмите «Починить», "
                        + "чтобы безопасно удалить незавершенную попытку и повторно отправить счет.";
            }
            return "Проблема при создании платежной ссылки T-Bank. Рекомендация: откройте «Счет» и сверьте состояние платежа в банке.";
        }
        if (error.startsWith("standalone_payment_route_conflict")) {
            return "У заказа внутри общего счета осталась отдельная платежная ссылка. Нажмите «Починить»: "
                    + "система сверит начатые платежи, зачтет подтвержденные оплаты и закроет только ссылки без "
                    + "банковских или ручных признаков оплаты. При неоднозначном состоянии автоматическая починка остановится.";
        }
        if (error.startsWith("close_failed")) {
            return "Оплата получена, но часть заказов не закрылась. Рекомендация: исправьте заказы и повторите действие в карточке счета.";
        }
        if (error.startsWith("next_order_failed")) {
            return "Платеж закрыт, но следующие заказы не создались. Рекомендация: нажмите «Починить», чтобы повторить создание следующих заказов.";
        }
        if (error.startsWith("review_approval_failed:")) {
            String problem = errorField(rawError, "problem");
            String solution = errorField(rawError, "solution");
            return "Массовое одобрение остановлено без частичных изменений. Проблема: "
                    + (problem.isBlank() ? "не удалось назначить даты публикации" : problem)
                    + ". Решение: "
                    + (solution.isBlank() ? "проверьте заказы общего счета" : solution)
                    + ". После исправления нажмите «Починить», чтобы безопасно повторить одобрение.";
        }
        if (commonInvoiceTechnicalTailRepairable(invoice)) {
            return "У общего счета остался технический хвост. Рекомендация: нажмите «Починить», чтобы скрыть старую карточку из контроля.";
        }
        return "Ошибка общего счета: " + limit(rawError, 160)
                + ". Рекомендация: откройте «Счет» и проверьте причину вручную.";
    }

    private String commonInvoicePaymentNotificationReason(CommonInvoice invoice, String rawError) {
        String error = safe(rawError).toLowerCase(Locale.ROOT);
        if (error.startsWith("immediate_messages_disabled")) {
            return "Уведомление об оплате не отправлено: моментальные клиентские сообщения выключены. Рекомендация: включите отправку или нажмите «Починить», чтобы закрыть эту ошибку.";
        }
        if (error.startsWith("whatsapp_group_missing") || error.contains("groupid")) {
            return commonInvoiceWhatsappGroupMissingReason(invoice, true);
        }
        return "Ошибка уведомления об оплате: " + limit(rawError, 160)
                + ". Рекомендация: проверьте сообщение клиенту или нажмите «Починить», чтобы закрыть ошибку уведомления.";
    }

    private String commonInvoiceWhatsappGroupMissingReason(CommonInvoice invoice, boolean paymentNotification) {
        CommonInvoiceChatBinding binding = commonInvoiceChatBinding(invoice);
        Company primaryCompany = binding.primaryCompany();
        Company linkedCompanyWithGroup = binding.linkedCompanyWithGroup();
        String primaryName = safe(primaryCompany == null ? null : primaryCompany.getTitle());
        String prefix = paymentNotification
                ? "Уведомление об оплате не отправлено"
                : "Сообщение общего счета не отправлено";

        if (hasText(primaryCompany == null ? null : primaryCompany.getGroupId())) {
            return prefix + ": у компании"
                    + (primaryName.isBlank() ? "" : " «" + primaryName + "»")
                    + " сейчас уже есть groupId, но в общем счете осталась старая ошибка WhatsApp. "
                    + "Рекомендация: повторите отправку из «Счета» или нажмите «Починить», если сообщение уже отправлено вручную или больше не нужно.";
        }

        if (linkedCompanyWithGroup != null) {
            String linkedName = safe(linkedCompanyWithGroup.getTitle());
            return prefix + ": у главной компании общего счета"
                    + (primaryName.isBlank() ? "" : " «" + primaryName + "»")
                    + " нет groupId. У связанной компании"
                    + (linkedName.isBlank() ? "" : " «" + linkedName + "»")
                    + " groupId есть, но общий счет отправляется через главную компанию. "
                    + "Рекомендация: привяжите WhatsApp-группу главной компании к боту или смените главную компанию счета"
                    + (paymentNotification ? ", либо нажмите «Починить», если уведомление уже не нужно." : ".");
        }

        return prefix + ": у WhatsApp-группы главной компании общего счета не задан groupId. "
                + "Рекомендация: откройте «Счет», затем заказ/компанию и привяжите WhatsApp-группу к боту"
                + (paymentNotification ? ", либо нажмите «Починить», если уведомление уже не нужно." : ".");
    }

    private CommonInvoiceChatBinding commonInvoiceChatBinding(CommonInvoice invoice) {
        List<CommonInvoiceOrder> items = invoice == null || invoice.getId() == null
                ? List.of()
                : commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        Company primaryCompany = commonInvoicePrimaryChatCompany(invoice, items);
        Long primaryCompanyId = primaryCompany == null ? null : primaryCompany.getId();
        Company linkedCompanyWithGroup = items.stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(Objects::nonNull)
                .map(Order::getCompany)
                .filter(Objects::nonNull)
                .filter(company -> company.getId() != null && !Objects.equals(company.getId(), primaryCompanyId))
                .filter(company -> hasText(company.getGroupId()))
                .findFirst()
                .orElse(null);
        return new CommonInvoiceChatBinding(primaryCompany, linkedCompanyWithGroup);
    }

    private Company commonInvoicePrimaryChatCompany(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null) {
            return null;
        }
        if (invoice.getAccount() != null && invoice.getAccount().getInvoiceCompany() != null) {
            return invoice.getAccount().getInvoiceCompany();
        }
        return (items == null ? List.<CommonInvoiceOrder>of() : items).stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(Objects::nonNull)
                .map(Order::getCompany)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    boolean commonInvoiceTechnicalTailRepairable(CommonInvoice invoice) {
        String error = safe(invoice == null ? null : invoice.getLastError()).toLowerCase(Locale.ROOT);
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.DISABLED
                && (error.startsWith("disabled:")
                || error.startsWith("empty:")
                || error.startsWith("merged_into:")
                || error.startsWith("manual_fix:"));
    }

    boolean commonInvoiceWhatsappGroupTailRepairable(CommonInvoice invoice) {
        String error = safe(invoice == null ? null : invoice.getLastError()).toLowerCase(Locale.ROOT);
        if (invoice == null || !(error.startsWith("whatsapp_group_missing") || error.contains("whatsapp-групп"))) {
            return false;
        }
        CommonInvoiceChatBinding binding = commonInvoiceChatBinding(invoice);
        return hasText(binding.primaryCompany() == null
                ? null
                : binding.primaryCompany().getGroupId());
    }

    boolean commonInvoiceMessageSendRepairable(CommonInvoice invoice) {
        String error = safe(invoice == null ? null : invoice.getLastError()).toLowerCase(Locale.ROOT);
        if (invoice == null || error.isBlank()) {
            return false;
        }
        return error.startsWith("telegram_not_sent")
                || error.startsWith("telegram_exception")
                || error.startsWith("telegram_group_missing")
                || error.startsWith("max_not_sent")
                || error.startsWith("max_exception")
                || error.startsWith("max_group_missing");
    }

    boolean commonInvoiceUnsentTlsInitRepairable(CommonInvoice invoice) {
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && safe(invoice.getTbankOrderId()).isBlank()
                && safe(invoice.getTbankPaymentId()).isBlank()
                && safe(invoice.getTbankTerminalKey()).isBlank()
                && invoice.getTbankPaymentAmountKopecks() == null
                && invoice.getTbankPaymentCreatedAt() == null
                && safe(invoice.getPaymentUrl()).isBlank()
                && CommonPaymentInitFailureClassifier.isPersistedTlsBeforeHttpFailure(invoice.getLastError());
    }

    private boolean commonInvoiceHasCompetingStandalonePayment(List<CommonInvoiceOrder> items) {
        List<Long> orderIds = (items == null ? List.<CommonInvoiceOrder>of() : items).stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(Objects::nonNull)
                .map(Order::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return !orderIds.isEmpty()
                && paymentLinkRepository.findByOrderIdInForRead(orderIds).stream()
                .anyMatch(StandaloneBankPaymentPolicy::blocksCommonInvoiceTlsRecovery);
    }

    boolean commonInvoiceStandaloneRouteRepairable(CommonInvoice invoice) {
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && safe(invoice.getLastError()).toLowerCase(Locale.ROOT)
                .startsWith("standalone_payment_route_conflict");
    }

    boolean commonInvoicePaymentNotificationRepairable(CommonInvoice invoice) {
        return !safe(invoice == null ? null : invoice.getPaymentSuccessNotificationError()).isBlank();
    }

    boolean commonInvoiceNextOrderRepairable(CommonInvoice invoice) {
        String error = safe(invoice == null ? null : invoice.getLastError()).toLowerCase(Locale.ROOT);
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && error.startsWith("next_order_failed");
    }

    boolean commonInvoiceReviewApprovalRepairable(CommonInvoice invoice) {
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && safe(invoice.getLastError()).toLowerCase(Locale.ROOT).startsWith("review_approval_failed:");
    }

    private String errorField(String rawError, String field) {
        String source = safe(rawError);
        String marker = field + "=";
        int start = source.toLowerCase(Locale.ROOT).indexOf(marker.toLowerCase(Locale.ROOT));
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = source.indexOf(';', start);
        return safe(end < 0 ? source.substring(start) : source.substring(start, end));
    }

    private String valueAfter(String value, String marker) {
        int index = safe(value).indexOf(marker);
        if (index < 0) {
            return "";
        }
        String suffix = value.substring(index + marker.length()).trim();
        int end = 0;
        while (end < suffix.length() && Character.isDigit(suffix.charAt(end))) {
            end++;
        }
        return end == 0 ? "" : suffix.substring(0, end);
    }

    String commonInvoiceStatusLabel(CommonInvoiceStatus status) {
        if (status == null) {
            return "Без статуса";
        }
        return switch (status) {
            case COLLECTING -> "Сбор";
            case READY -> "Готов к счету";
            case INVOICED -> "Выставлен счет";
            case REMINDER -> "Напоминание";
            case PARTIALLY_PAID -> "Частично оплачен";
            case NEEDS_ATTENTION -> "Требует внимания";
            case PAID -> "Оплачен";
            case UNPAID -> "Не оплачен";
            case BAN -> "Бан";
            case ARCHIVED -> "Архив";
            case DISABLED -> "Отключен";
        };
    }

    private String rubles(long kopecks) {
        return (kopecks / 100) + " руб.";
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

    private boolean hasText(String value) {
        return !safe(value).isBlank();
    }

    private long daysSince(LocalDate date, LocalDate today) {
        return date == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(date, today));
    }

    private String orderStatusTitle(Order order) {
        return order == null || order.getStatus() == null ? "" : safe(order.getStatus().getTitle());
    }

    private String userDisplayName(User user) {
        String fio = safe(user == null ? null : user.getFio());
        if (!fio.isBlank()) {
            return fio;
        }
        String username = safe(user == null ? null : user.getUsername());
        return username.isBlank() ? "Специалист #" + (user == null ? "-" : user.getId()) : username;
    }

    private record CommonInvoiceChatBinding(Company primaryCompany, Company linkedCompanyWithGroup) {
    }
}

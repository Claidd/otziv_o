package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.dto.ManualCardPaymentContextResponse;
import com.hunt.otziv.contractor_payments.dto.ManualCardPaymentRecipientResponse;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRuntimeSwitch;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Selection, historical cutoff and replay rules for a manually reported transfer.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ManualCardRoutePolicy {

    private final PaymentLinkLifecycleService lifecycleService;

    private final ManualPaymentConfirmationWorkflow manualConfirmationWorkflow;

    private final PaymentLinkPresenter paymentPresenter;

    static final String MANUAL_CARD_PAYMENT_AUDIT_PREFIX = "Оплачено переводом на карту после отмены T-Bank";

    static final String MANUAL_CARD_PAYMENT_EVIDENCE_PREFIX = "Проверен перевод на карту; ожидается закрытие T-Bank";

    static final String MANAGER_REPORTED_CARD_PAYMENT_AUDIT_PREFIX = "Заявлено менеджером: оплата переводом после отмены T-Bank";

    static final String MANAGER_REPORTED_CARD_PAYMENT_EVIDENCE_PREFIX = "Заявлено менеджером: ожидается закрытие T-Bank";

    static final String MANUAL_CARD_PAYMENT_PENDING_PREFIX = "manual_card_payment_pending:";

    static final String MANUAL_CARD_PAYMENT_COMPLETED_PREFIX = "manual_card_payment_completed:";

    static final String HISTORICAL_PRE_CUTOVER_MANUAL_CARD_RECIPIENT_KEY = "LEGACY_PRE_CUTOVER_MANUAL_CARD";

    private final ContractorPaymentRuntimeSwitch contractorPaymentRuntimeSwitch;

    private final ContractorActualPaymentAttributionService actualPaymentAttributionService;

    private boolean isPaperInvoice(PaymentLink link) {
        return paymentPresenter.isPaperInvoice(link);
    }

    private boolean hasBankInitReservation(PaymentLink link) {
        return lifecycleService.hasBankInitReservation(link);
    }

    private boolean isTochkaPaymentLink(PaymentLink link) {
        return paymentPresenter.isTochkaPaymentLink(link);
    }

    ManualCardPaymentContextResponse manualCardPaymentContextForRoute(Order order, PaymentLink link) {
        if (isPaperInvoice(link)) {
            ManualCardPaymentRecipientResponse recipient = new ManualCardPaymentRecipientResponse(ContractorRecipientType.OWNER, null, null, "Владелец — оплата по бумажному счёту", link == null ? 0L : link.getAmountKopecks(), 0L, true);
            return new ManualCardPaymentContextResponse(order == null ? null : order.getId(), link == null ? 0L : link.getAmountKopecks(), recipient, List.of(recipient), "Оплата будет учтена владельцу как поступление по бумажному счёту. Резервы сотрудников не изменятся.", true, recipient, "Оплата по бумажному счёту", null, "TASK_RECIPIENT_V1", "OWNER_PAPER_INVOICE:" + (link == null ? "" : link.getId()));
        }
        if (isHistoricalPreCutoverManualCardRoute(link)) {
            ManualCardPaymentRecipientResponse recipient = historicalPreCutoverManualCardRecipient(link);
            return new ManualCardPaymentContextResponse(order == null ? null : order.getId(), link == null ? 0L : link.getAmountKopecks(), recipient, List.of(recipient), "Старая оплата до запуска новой системы будет закрыта без нового учёта выплат.", false, null, null, null, "TASK_RECIPIENT_V1", HISTORICAL_PRE_CUTOVER_MANUAL_CARD_RECIPIENT_KEY + ":" + (link == null ? "" : link.getId()));
        }
        return actualPaymentAttributionService.manualCardPaymentContext(order, link);
    }

    private ManualCardPaymentRecipientResponse historicalPreCutoverManualCardRecipient(PaymentLink link) {
        long amountKopecks = link == null ? 0L : link.getAmountKopecks();
        String bankRecipient = link == null ? "" : normalize(link.getManualRecipientName());
        String displayName = bankRecipient.isBlank() ? "Историческая оплата до запуска" : bankRecipient;
        return new ManualCardPaymentRecipientResponse(null, null, null, displayName, amountKopecks, 0L, true, HISTORICAL_PRE_CUTOVER_MANUAL_CARD_RECIPIENT_KEY, null, null, null, null, null, "Без нового учёта выплат", "Заказ будет отмечен оплаченным; новые выплаты, лимиты и резервы сотрудников не изменятся. " + "Если клиенту были выданы реквизиты платёжного задания, используйте маршрут задания — он будет засчитан отдельно.");
    }

    private boolean isHistoricalPreCutoverManualCardRecipientKey(String recipientKey) {
        return HISTORICAL_PRE_CUTOVER_MANUAL_CARD_RECIPIENT_KEY.equals(normalize(recipientKey));
    }

    boolean isHistoricalPreCutoverManualCardSettlement(PaymentLink link, String recipientKey) {
        return isHistoricalPreCutoverManualCardRecipientKey(recipientKey) && isHistoricalPreCutoverManualCardRoute(link);
    }

    void requireHistoricalPreCutoverManualCardSelectionIfNeeded(PaymentLink link, String recipientKey) {
        boolean historicalRoute = isHistoricalPreCutoverManualCardRoute(link);
        boolean historicalSelection = isHistoricalPreCutoverManualCardRecipientKey(recipientKey);
        if (!historicalRoute && !historicalSelection) {
            return;
        }
        if (historicalRoute && historicalSelection) {
            return;
        }
        if (historicalSelection) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Историческое подтверждение доступно только для ссылок, созданных до запуска новой системы.");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Эта ссылка создана до запуска новой системы. Выберите историческую оплату до запуска; " + "новый учёт выплат для неё не применяется.");
    }

    private boolean isHistoricalPreCutoverManualCardRoute(PaymentLink link) {
        if (link == null || link.getCreatedAt() == null || link.getManualActualRecipientFrozenAt() != null || (!isSafeHistoricalBankRoute(link) && !isRecoverableExpiredManualRoute(link) && !isCompletedHistoricalDirectManualRoute(link))) {
            return false;
        }
        Optional<LocalDateTime> activatedAt = safeCompletionAccountingActivatedAt();
        if (activatedAt.isPresent()) {
            return link.getCreatedAt().isBefore(activatedAt.get());
        }
        Optional<LocalDate> startDate = safeCompletionAttributionStartDate();
        return startDate.isPresent() && link.getCreatedAt().toLocalDate().isBefore(startDate.get());
    }

    private boolean isCompletedHistoricalDirectManualRoute(PaymentLink link) {
        return link != null && isManualPayment(link) && link.getStatus() == PaymentLinkStatus.CONFIRMED && link.getManualActualRecipientFrozenAt() == null && link.getManualConfirmedAt() != null && link.getConfirmedAmountKopecks() != null && link.getConfirmedAmountKopecks() == link.getAmountKopecks();
    }

    private Optional<LocalDateTime> safeCompletionAccountingActivatedAt() {
        try {
            Optional<LocalDateTime> value = contractorPaymentRuntimeSwitch.completionAccountingActivatedAt();
            return value == null ? Optional.empty() : value;
        } catch (RuntimeException exception) {
            log.warn("Не удалось прочитать точное время запуска новой системы оплат: failure={}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<LocalDate> safeCompletionAttributionStartDate() {
        try {
            Optional<LocalDate> value = contractorPaymentRuntimeSwitch.completionAttributionStartDate();
            return value == null ? Optional.empty() : value;
        } catch (RuntimeException exception) {
            log.warn("Не удалось прочитать дату запуска новой системы оплат: failure={}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    void requireCompletedManualCardPaymentReplay(PaymentLink link, long requestedAmountKopecks, String requestedRecipientKey, ContractorRecipientType requestedType, Long requestedProfileId, String reason, String receiptUrl) {
        if (isHistoricalPreCutoverManualCardSettlement(link, requestedRecipientKey)) {
            return;
        }
        actualPaymentAttributionService.requireCompletedPaymentReplay(link, requestedAmountKopecks, requestedRecipientKey, requestedType, requestedProfileId, reason, receiptUrl);
    }

    boolean isPendingManualCardPayment(PaymentLink link) {
        return isBankPaymentRoute(link) && (isSafeTerminalBeforeManualCardPayment(link.getStatus()) || isProvablyUnissuedFailedBankRoute(link)) && link.getManualConfirmedAt() == null && link.getConfirmedAmountKopecks() == null && (normalize(link.getLastError()).startsWith(MANUAL_CARD_PAYMENT_PENDING_PREFIX) || normalize(link.getManualComment()).startsWith(MANUAL_CARD_PAYMENT_EVIDENCE_PREFIX) || normalize(link.getManualComment()).startsWith(MANAGER_REPORTED_CARD_PAYMENT_EVIDENCE_PREFIX));
    }

    boolean isCompletedManualCardPayment(PaymentLink link) {
        if (!isBankPaymentRoute(link) || (!isSafeTerminalBeforeManualCardPayment(link.getStatus()) && !isProvablyUnissuedFailedBankRoute(link)) || !normalize(link.getLastError()).startsWith(MANUAL_CARD_PAYMENT_COMPLETED_PREFIX) || (!normalize(link.getManualComment()).startsWith(MANUAL_CARD_PAYMENT_AUDIT_PREFIX) && !normalize(link.getManualComment()).startsWith(MANAGER_REPORTED_CARD_PAYMENT_AUDIT_PREFIX))) {
            return false;
        }
        // The order transition and these immutable audit fields are committed
        // in one transaction. The audit marker therefore makes retries
        // idempotent without dereferencing a possibly detached/lazy order.
        return true;
    }

    PaymentLink selectManualCardPaymentRoute(List<PaymentLink> orderLinks) {
        List<PaymentLink> links = orderLinks == null ? List.of() : orderLinks;
        List<PaymentLink> marked = links.stream().filter(link -> isCompletedManualCardPayment(link) || isPendingManualCardPayment(link)).toList();
        if (marked.size() > 1) {
            throw ambiguousManualCardPaymentRoute();
        }
        List<PaymentLink> terminalToVerify = links.stream().filter(this::isSelectableTerminalBankRouteForVerification).filter(link -> !isSafeHistoricalBankRoute(link)).toList();
        if (terminalToVerify.size() > 1) {
            throw ambiguousManualCardPaymentRoute();
        }
        boolean unsafeBankState = links.stream().anyMatch(this::isUnsafeManualCardPaymentBankRoute);
        if (unsafeBankState) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У заказа есть оплаченная, возвратная или неоднозначная T-Bank ссылка. " + "Оплата переводом не зачислена; нужна ручная сверка.");
        }
        List<PaymentLink> directManual = links.stream().filter(this::isDirectManualAttributionRoute).toList();
        if (directManual.size() > 1)
            throw ambiguousManualCardPaymentRoute();
        // A live manual route is the current client instruction. Older manual
        // reminders with the same amount can become "recoverable" again after
        // the payable amount cycles back, but they are history rather than a
        // second active payment route. Recover an expired row only when there
        // is no live manual route to settle.
        PaymentLink recoverableExpiredManual = directManual.isEmpty() ? links.stream().filter(this::isRecoverableExpiredManualRoute).max(Comparator.comparing(PaymentLink::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(PaymentLink::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(PaymentLink::getId, Comparator.nullsFirst(Comparator.naturalOrder()))).orElse(null) : null;
        List<PaymentLink> active = links.stream().filter(this::isActivePublicBankRouteForManualCardPayment).toList();
        int selectedRouteKinds = (marked.isEmpty() ? 0 : 1) + (active.isEmpty() ? 0 : 1) + (terminalToVerify.isEmpty() ? 0 : 1) + (directManual.isEmpty() ? 0 : 1) + (recoverableExpiredManual == null ? 0 : 1);
        if (active.size() > 1 || selectedRouteKinds > 1) {
            throw ambiguousManualCardPaymentRoute();
        }
        if (!directManual.isEmpty()) {
            return directManual.getFirst();
        }
        if (recoverableExpiredManual != null) {
            return recoverableExpiredManual;
        }
        if (!marked.isEmpty()) {
            return marked.getFirst();
        }
        if (!active.isEmpty()) {
            return active.getFirst();
        }
        if (!terminalToVerify.isEmpty()) {
            return terminalToVerify.getFirst();
        }
        return links.stream().filter(this::isSafeHistoricalBankRoute).max(Comparator.comparing(PaymentLink::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(PaymentLink::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(PaymentLink::getId, Comparator.nullsFirst(Comparator.naturalOrder()))).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "У заказа нет активной или проверяемой завершенной T-Bank ссылки. " + "Оплата переводом не зачислена; откройте журнал платежей для сверки."));
    }

    boolean isDirectManualAttributionRoute(PaymentLink link) {
        return link != null && isManualPayment(link) && (link.getStatus() == PaymentLinkStatus.WAITING_MANUAL_PAYMENT || link.getStatus() == PaymentLinkStatus.MANUAL_REPORTED || (link.getStatus() == PaymentLinkStatus.CONFIRMED && link.getManualActualRecipientFrozenAt() != null));
    }

    /**
     * A client can report a transfer after an issued manual instruction was
     * retired by TTL or by a later payable correction. Reusing such a row is
     * safe only when it has no payment evidence and its frozen amount is once
     * again exactly the current payable amount. The manager still has to pick
     * the actual recipient before the order is credited.
     */
    private boolean isRecoverableExpiredManualRoute(PaymentLink link) {
        return manualConfirmationWorkflow.isRecoverableExpiredManualRoute(link);
    }

    ResponseStatusException ambiguousManualCardPaymentRoute() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "У заказа найдено несколько активных или неоднозначных T-Bank ссылок. " + "Оплата переводом не зачислена; нужна ручная сверка.");
    }

    private boolean isActivePublicBankRouteForManualCardPayment(PaymentLink link) {
        return isBankPaymentRoute(link) && !hasBankInitReservation(link) && !hasBankCancelReservation(link) && link.getBankCancelOriginStatus() == null && (link.getStatus() == PaymentLinkStatus.CREATED || link.getStatus() == PaymentLinkStatus.INITIATED);
    }

    boolean isSelectableTerminalBankRouteForVerification(PaymentLink link) {
        return isBankPaymentRoute(link) && isSafeTerminalBeforeManualCardPayment(link.getStatus()) && !normalize(link.getTbankPaymentId()).isBlank() && !hasBankInitReservation(link) && !hasBankCancelReservation(link) && link.getBankCancelOriginStatus() == null;
    }

    boolean isSafeHistoricalBankRoute(PaymentLink link) {
        if (isProvablyUnissuedFailedBankRoute(link)) {
            return true;
        }
        if (!isBankPaymentRoute(link) || !isSafeTerminalBeforeManualCardPayment(link.getStatus()) || hasBankInitReservation(link) || hasBankCancelReservation(link) || link.getBankCancelOriginStatus() != null) {
            return false;
        }
        if (isPendingManualCardPayment(link) || isCompletedManualCardPayment(link)) {
            return true;
        }
        if (normalize(link.getTbankPaymentId()).isBlank()) {
            return true;
        }
        return hasAuthoritativeProviderTerminalStatus(link);
    }

    /**
     * A routing failure before T-Bank Init cannot represent a bank payment.
     */
    private boolean isProvablyUnissuedFailedBankRoute(PaymentLink link) {
        return isBankPaymentRoute(link) && link.getStatus() == PaymentLinkStatus.FAILED && normalize(link.getTbankPaymentId()).isBlank() && normalize(link.getTbankOrderId()).isBlank() && normalize(link.getPaymentUrl()).isBlank() && normalize(link.getSbpQrPayload()).isBlank() && normalize(link.getProviderTerminalStatus()).isBlank() && link.getInitiatedAt() == null && link.getPaidAt() == null && link.getManualConfirmedAt() == null && (link.getConfirmedAmountKopecks() == null || link.getConfirmedAmountKopecks() <= 0) && !hasBankInitReservation(link) && !hasBankCancelReservation(link) && link.getBankCancelOriginStatus() == null;
    }

    boolean hasAuthoritativeProviderTerminalStatus(PaymentLink link) {
        if (link == null) {
            return false;
        }
        String providerStatus = normalize(link.getProviderTerminalStatus());
        return switch(link.getStatus()) {
            case CANCELED ->
                "CANCELED".equalsIgnoreCase(providerStatus);
            case REJECTED ->
                "REJECTED".equalsIgnoreCase(providerStatus);
            case EXPIRED ->
                isTochkaPaymentLink(link) ? "EXPIRED".equals(providerStatus) : "DEADLINE_EXPIRED".equalsIgnoreCase(providerStatus);
            default ->
                false;
        };
    }

    private boolean isUnsafeManualCardPaymentBankRoute(PaymentLink link) {
        if (!isBankPaymentRoute(link)) {
            return false;
        }
        if (hasBankInitReservation(link) || hasBankCancelReservation(link) || link.getBankCancelOriginStatus() != null) {
            return true;
        }
        if (isCompletedManualCardPayment(link) || isPendingManualCardPayment(link) || isActivePublicBankRouteForManualCardPayment(link) || isSelectableTerminalBankRouteForVerification(link) || isSafeHistoricalBankRoute(link)) {
            return false;
        }
        // Every remaining bank status is paid, refund/reversal-related,
        // reconciliation-only, failed without authoritative proof, or an
        // otherwise unexpected state. All of them stay fail-closed.
        return true;
    }

    boolean isCompetingManualCardPaymentRoute(PaymentLink link) {
        if (link == null || isSafeHistoricalBankRoute(link)) {
            return false;
        }
        if (isBankPaymentRoute(link)) {
            return true;
        }
        return switch(link.getStatus()) {
            case CANCELED, REJECTED, EXPIRED, FAILED ->
                false;
            default ->
                true;
        };
    }

    private boolean isBankPaymentRoute(PaymentLink link) {
        return link != null && (link.getPaymentMethod() == PaymentMethod.BANK_FORM || link.getPaymentMethod() == PaymentMethod.SBP_QR);
    }

    /**
     * Owner receipt is never self-proving: the customer can transfer to an
     * old owner account while the current client instruction points either to
     * T-Bank or to a contractor. In both cases the manager only reports the
     * receipt; an owner/admin must confirm it before the order is settled.
     */
    boolean isOwnerApprovalEligibleRoute(PaymentLink link) {
        return isBankPaymentRoute(link) || isDirectManualAttributionRoute(link) || isRecoverableExpiredManualRoute(link);
    }

    boolean isUnstartedCreatedBankRoute(PaymentLink link) {
        return link != null && (link.getPaymentMethod() == PaymentMethod.BANK_FORM || link.getPaymentMethod() == PaymentMethod.SBP_QR) && link.getStatus() == PaymentLinkStatus.CREATED && normalize(link.getTbankPaymentId()).isBlank() && !hasBankInitReservation(link) && !hasBankCancelReservation(link) && link.getBankCancelOriginStatus() == null;
    }

    boolean isActiveOrAmbiguousTochkaRouteForManualCardPayment(PaymentLink link) {
        return isTochkaPaymentLink(link) && !isUnstartedCreatedBankRoute(link) && !isSafeHistoricalBankRoute(link) && (hasBankInitReservation(link) || hasBankCancelReservation(link) || link.getBankCancelOriginStatus() != null || !normalize(link.getTbankPaymentId()).isBlank() || link.getStatus() == PaymentLinkStatus.INITIATED || link.getStatus() == PaymentLinkStatus.AUTHORIZED || link.getStatus() == PaymentLinkStatus.NEEDS_RECONCILIATION);
    }

    boolean isSafeTerminalBeforeManualCardPayment(PaymentLinkStatus status) {
        return status == PaymentLinkStatus.CANCELED || status == PaymentLinkStatus.REJECTED || status == PaymentLinkStatus.EXPIRED;
    }

    private boolean hasBankCancelReservation(PaymentLink link) {
        return paymentPresenter.hasBankCancelReservation(link);
    }

    private boolean isManualPayment(PaymentLink link) {
        return paymentPresenter.isManualPayment(link);
    }

    private String normalize(String value) {
        return paymentPresenter.normalize(value);
    }
}

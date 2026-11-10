package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.*;
import com.hunt.otziv.common_billing.repository.*;
import com.hunt.otziv.payments.api.BadReviewBankRepricing;
import com.hunt.otziv.payments.api.UnpaidBankObservation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CommonInvoiceBadReviewBankRepricing implements BadReviewBankRepricing {
    private final CommonInvoiceRepository invoices;
    private final CommonInvoiceOrderRepository positions;
    private final CommonInvoicePaymentRefRepository refs;
    private final CommonInvoicePaymentRouteWorkflow routes;
    private final CommonInvoiceSettlementService settlement;
    private final ObjectProvider<CommonBillingService> billing;
    private final ObjectProvider<CommonInvoiceMembershipWorkflow> membership;
    private static final String WAIT = "Ожидаем закрытия прежней банковской ссылки. Система перевыставит счёт автоматически.";

    public List<Long> due() { return invoices.dueBadReviewBankChecks(LocalDateTime.now()); }

    boolean arm(CommonInvoice invoice, List<CommonInvoiceOrder> items, long orderId, long taskId) {
        if (!(Set.of(CommonInvoiceStatus.UNPAID, CommonInvoiceStatus.INVOICED, CommonInvoiceStatus.REMINDER).contains(invoice.getStatus())
                || invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION && invoice.getBadReviewBankCheckAt() != null)
                || items.stream().noneMatch(i -> i.isActiveMembership() && Objects.equals(i.orderId(), orderId))) return false;
        var ref = eligibleRef(invoice, items);
        if (ref == null) return false;
        if (invoice.getBadReviewBankRefId() == null && settlement.isPaperModeSwitchSafeTerminalStatus(ref.getStatus())
                && invoice.getTbankPaymentId() == null && invoice.getTbankOrderId() == null && invoice.getPaymentUrl() == null)
            return false;
        invoice.setBadReviewBankRefId(ref.getId());
        invoice.setBadReviewBankOrderId(orderId);
        invoice.setBadReviewBankTaskId(taskId);
        invoice.setBadReviewBankCheckAt(LocalDateTime.now());
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setBadReviewNoticePending(false);
        invoice.setNextReminderAt(null);
        updateWaitingAmount(invoice, items);
        return true;
    }

    private CommonInvoicePaymentRef eligibleRef(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (items.isEmpty() || items.stream().anyMatch(i -> !i.isActiveMembership() || membership.getObject().hasBadReviewSuccessorPaymentEvidence(i))
                || invoices.pendingRecipientChanges(invoice.getId()) != 0) return null;
        var ref = routes.boundUnpaidBankRef(invoice, refs.findByInvoiceIdForUpdate(invoice.getId()));
        if (ref == null || ref.getPaymentProfileId() == null || ref.getAmountKopecks() == null
                || invoice.getBadReviewBankRefId() != null && !invoice.getBadReviewBankRefId().equals(ref.getId())) return null;
        return ref;
    }

    private void updateWaitingAmount(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        var amounts = items.stream().map(i -> membership.getObject().payableKopecksOrMarkAttention(invoice, i.getOrder())).toList();
        if (amounts.stream().anyMatch(a -> a == null || a <= 0)) {
            invoice.setBadReviewBankCheckAt(null);
            invoice.setLastError("Не удалось пересчитать позиции общего счёта после плохой задачи");
            return;
        }
        for (int n = 0; n < items.size(); n++) items.get(n).setAmountKopecks(amounts.get(n));
        invoice.setAmountKopecks(amounts.stream().reduce(0L, Math::addExact));
        invoice.setLastError(WAIT);
    }

    private Source source(CommonInvoice invoice, CommonInvoicePaymentRef ref) {
        return new Source("INVOICE", invoice.getId(), ref.getId(), ref.getPaymentProfileId(), routes.normalizedPaymentProvider(ref),
                routes.providerPaymentId(ref), routes.providerOrderId(ref), routes.providerMerchantId(ref), ref.getAmountKopecks(),
                ref.getProviderPaymentMode(), ref.getProviderTestMode(), Objects.toString(invoice.getPaymentRouteSelectedAt(), ""));
    }

    public Source claim(long id) {
        var invoice = settlement.lockedInvoice(id).orElse(null);
        if (invoice == null || invoice.getBadReviewBankCheckAt() == null || invoice.getBadReviewBankCheckAt().isAfter(LocalDateTime.now())) return null;
        var items = positions.findByInvoiceIdWithOrders(id);
        var ref = eligibleRef(invoice, items);
        if (invoice.getStatus() != CommonInvoiceStatus.NEEDS_ATTENTION || ref == null) {
            invoice.setBadReviewBankCheckAt(null);
            if (WAIT.equals(invoice.getLastError()))
                invoice.setLastError("По прежней ссылке изменились банковские или платёжные данные. Требуется сверка.");
            return null;
        }
        invoice.setBadReviewBankCheckAt(LocalDateTime.now().plusMinutes(1));
        // Also refresh a cycle recovered from a previous release, before the first bank response.
        if (!WAIT.equals(invoice.getLastError())) updateWaitingAmount(invoice, items);
        return invoice.getBadReviewBankCheckAt() == null ? null : source(invoice, ref);
    }

    public void finish(Source original, Result result) {
        var invoice = settlement.lockedInvoice(original.targetId()).orElse(null);
        if (invoice == null || invoice.getStatus() != CommonInvoiceStatus.NEEDS_ATTENTION || invoice.getBadReviewBankCheckAt() == null) return;
        var items = positions.findByInvoiceIdWithOrders(invoice.getId());
        var ref = eligibleRef(invoice, items);
        if (ref == null || !original.equals(source(invoice, ref))) return;
        if (result.outcome() == Outcome.FINANCIAL) {
            settlement.applyRecipientChangeReceipt(original, result, invoice, ref);
            if (!Set.of("CONFIRMED", "APPROVED").contains(result.status())) {
                ref.setProviderStatus(result.status());
                ref.setStatus("INIT_CONFLICT");
                ref.setReason("bad_review_bank_financial_observation");
            }
            invoice.setLastError("Банк сообщил о движении по прежней сумме общего счёта: " + result.status() + ". Требуется сверка.");
            invoice.setBadReviewBankCheckAt(null);
            return;
        }
        if (result.outcome() != Outcome.CLOSED) return;
        Long taskId = invoice.getBadReviewBankTaskId();
        Long orderId = invoice.getBadReviewBankOrderId();
        if (items.stream().noneMatch(i -> Objects.equals(i.orderId(), orderId))) {
            invoice.setBadReviewBankCheckAt(null);
            invoice.setLastError("Состав общего счёта изменился во время пересчёта. Требуется сверка.");
            return;
        }
        ref.setStatus("DEADLINE_EXPIRED".equals(result.status()) ? "EXPIRED" : result.status());
        ref.setProviderStatus(result.status());
        ref.setReason("bad_review_bank_closed");
        settlement.clearCurrentPaymentRef(invoice);
        invoice.setBadReviewBankCheckAt(null);
        invoice.setBadReviewBankRefId(null);
        invoice.setBadReviewBankOrderId(null);
        invoice.setBadReviewBankTaskId(null);
        invoice.setLastError(null);
        invoice.setBadReviewPayable(false);
        invoice.setStatus(items.stream().allMatch(CommonInvoiceOrder::isUnpaid) ? CommonInvoiceStatus.UNPAID : CommonInvoiceStatus.INVOICED);
        billing.getObject().repriceBadReviewInvoice(orderId, taskId);
    }
}

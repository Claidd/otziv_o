package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Composition changes retire only unused reservations, in the caller's canonical invoice transaction. */
@Service
@RequiredArgsConstructor
public class CommonInvoiceCompositionService {
    private static final Set<String> CLOSED_REFS = Set.of("ARCHIVED", "CANCELED", "REJECTED", "EXPIRED", "REFUNDED", "REVERSED");
    private final CommonInvoiceInitializationService initialization;
    private final CommonInvoicePaymentRefRepository paymentRefs;

    static boolean isPublicationAttention(CommonInvoice invoice) {
        return invoice != null && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && text(invoice.getLastError()).startsWith(CommonBillingPublicationApprovalFailureMarker.ERROR_PREFIX);
    }

    static boolean canChangeStatus(CommonInvoice invoice) {
        return invoice != null && (invoice.getStatus() == CommonInvoiceStatus.COLLECTING
                || invoice.getStatus() == CommonInvoiceStatus.READY || isPublicationAttention(invoice));
    }

    String previewBlockReason(CommonInvoice invoice) {
        String reason = invoiceBlockReason(invoice);
        if (reason != null) return reason;
        return evidenceBlockReason(invoice, paymentRefs.findByInvoiceIdOrderByCreatedAtAsc(invoice.getId()), true);
    }

    static String itemBlockReason(CommonInvoiceOrder item) {
        if (item == null || !item.isActiveMembership()) return "Заказ уже исключён из текущего общего счёта";
        if (item.isPaid() || item.isUnpaid() || item.getPaidAt() != null || !text(item.getPaymentMethod()).isEmpty()
                || !text(item.getManualPaidBy()).isEmpty() || !text(item.getManualPaymentComment()).isEmpty()
                || !text(item.getManualPaymentReceiptUrl()).isEmpty()) {
            return "У заказа есть платёжные данные. Сначала выполните сверку оплаты";
        }
        return null;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void prepareChange(CommonInvoice invoice, CommonInvoiceOrder item) {
        initialization.ensureNoOperationInProgress(invoice);
        String reason = invoiceBlockReason(invoice);
        if (reason == null) reason = itemBlockReason(item);
        if (reason == null) reason = evidenceBlockReason(invoice, paymentRefs.findByInvoiceIdForUpdate(invoice.getId()), false);
        if (reason != null) throw new ResponseStatusException(HttpStatus.CONFLICT, reason);

        initialization.releaseUnusedCompositionReservation(invoice);
        CommonInvoiceRouteState.clearPaymentRoute(invoice);
    }

    private String invoiceBlockReason(CommonInvoice invoice) {
        if (!canChangeStatus(invoice)) return "Общий счёт закрыт или содержит финансовую ошибку. Сначала выполните сверку счёта";
        if ((invoice.getPaymentMessageOperationId() != null && !invoice.isPaymentMessageConfirmed())
                || initialization.hasUnresolvedLegacyMessage(invoice)) {
            return "Результат отправки прежнего счёта не подтверждён. Сначала выполните сверку отправки";
        }
        if (invoice.getSentAt() != null || invoice.getLastReminderAt() != null || invoice.isPaymentMessageConfirmed()
                || invoice.getPaperInvoiceIssuedAt() != null || CommonInvoiceRouteState.isOwnerPaperInvoice(invoice)) {
            return "Счёт уже передан клиенту. Для изменения суммы сначала сверяйте выставленный счёт и его оплату";
        }
        if (invoice.getPaidKopecks() != 0 || invoice.getPaidAt() != null || invoice.getClientReportedAt() != null
                || invoice.getManualConfirmedAt() != null || !text(invoice.getManualPaidBy()).isEmpty()
                || !text(invoice.getManualPaymentComment()).isEmpty() || !text(invoice.getManualPaymentReceiptUrl()).isEmpty()) {
            return "По общему счёту есть признаки оплаты. Сначала выполните сверку платежей";
        }
        if (!text(invoice.getTbankOrderId()).isEmpty() || !text(invoice.getTbankPaymentId()).isEmpty()
                || !text(invoice.getTbankTerminalKey()).isEmpty() || invoice.getTbankPaymentAmountKopecks() != null
                || invoice.getTbankPaymentCreatedAt() != null || !text(invoice.getPaymentUrl()).isEmpty()) {
            return "У счёта есть банковская платёжная ссылка. Сначала проверьте и отмените её через сверку оплаты";
        }
        if (!CommonInvoiceRouteState.hasFrozenCommonPaymentRoute(invoice)
                && (invoice.getContractorAllocationId() != null || invoice.getPaymentRouteManualTaskId() != null)) {
            return "Резерв счёта не согласован с платёжным маршрутом. Требуется сверка резерва";
        }
        return null;
    }

    private String evidenceBlockReason(CommonInvoice invoice, List<CommonInvoicePaymentRef> refs, boolean preview) {
        if (refs.stream().anyMatch(ref -> !CLOSED_REFS.contains(text(ref.getStatus()).toUpperCase(Locale.ROOT)))) {
            return "По счёту есть незавершённая или подтверждённая банковская попытка. Сначала выполните сверку оплаты";
        }
        return initialization.compositionReservationBlockReason(invoice, preview);
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}

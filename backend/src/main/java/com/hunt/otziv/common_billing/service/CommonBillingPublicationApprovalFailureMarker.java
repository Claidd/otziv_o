package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.p_products.review.PublicationApprovalException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonBillingPublicationApprovalFailureMarker {

    public static final String ERROR_PREFIX = "review_approval_failed:";
    private static final int MAX_ERROR_LENGTH = 512;

    private final CommonInvoiceRepository invoiceRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAttention(Long invoiceId, Throwable cause) {
        if (invoiceId == null) {
            return;
        }
        try {
            CommonInvoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
            if (invoice == null) {
                return;
            }
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setLastError(limit(error(cause), MAX_ERROR_LENGTH));
            invoiceRepository.save(invoice);
        } catch (RuntimeException markerFailure) {
            log.warn(
                    "Не удалось создать замечание менеджеру после сбоя одобрения общего счета {}",
                    invoiceId,
                    markerFailure
            );
        }
    }

    private String error(Throwable throwable) {
        PublicationApprovalException approval = findApprovalException(throwable);
        if (approval != null) {
            return ERROR_PREFIX
                    + " order=" + (approval.getOrderId() == null ? "-" : approval.getOrderId())
                    + "; problem=" + approval.getProblem()
                    + "; solution=" + approval.getSolution();
        }
        return ERROR_PREFIX
                + " problem=" + concise(throwable)
                + "; solution=обновите общий счет, проверьте заказы и повторите одобрение";
    }

    private PublicationApprovalException findApprovalException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof PublicationApprovalException approvalException) {
                return approvalException;
            }
            current = current.getCause();
        }
        return null;
    }

    private String concise(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        if (current == null) {
            return "неизвестная ошибка";
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message.trim();
    }

    private String limit(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
    }
}

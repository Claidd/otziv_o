package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.AdminPaymentLinksPageResponse;
import com.hunt.otziv.payments.dto.AdminPaymentLinkSummaryResponse;
import com.hunt.otziv.payments.dto.PaymentLinkAdminSummary;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentReceiptStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hunt.otziv.payments.service.PaymentLinkPreparationWorkflow.REUSABLE_STATUSES;
import static com.hunt.otziv.payments.service.ManualPaymentConfirmationWorkflow.PAID_STATUSES;
import static com.hunt.otziv.payments.service.PaymentLinkCancellationWorkflow.REFUNDED_STATUSES;
import static com.hunt.otziv.payments.service.PaymentLinkPresenter.REFUNDABLE_STATUSES;

@Service
@Slf4j
@RequiredArgsConstructor
/** Preserves administrative read-before-response expiry, filtering and archive projections in their write transaction. */
public class PaymentLinkAdminBoardWorkflow {

    private final PaymentLinkPreparationWorkflow preparationWorkflow;

    private final PaymentLinkPresenter paymentPresenter;

    static final Set<PaymentLinkStatus> FAILED_STATUSES = Set.of(PaymentLinkStatus.REJECTED, PaymentLinkStatus.FAILED, PaymentLinkStatus.NEEDS_RECONCILIATION, PaymentLinkStatus.EXPIRED);

    static final Set<PaymentLinkStatus> REJECTED_STATUSES = Set.of(PaymentLinkStatus.REJECTED, PaymentLinkStatus.FAILED, PaymentLinkStatus.NEEDS_RECONCILIATION);

    static final Set<PaymentLinkStatus> MANUAL_PENDING_STATUSES = Set.of(PaymentLinkStatus.WAITING_MANUAL_PAYMENT, PaymentLinkStatus.MANUAL_REPORTED);

    static final Set<PaymentMethod> MANUAL_METHODS = Set.of(PaymentMethod.MANUAL_MOBILE_BANK, PaymentMethod.MANUAL_EXTERNAL_LINK, PaymentMethod.OWNER_PAPER_INVOICE);

    private final PaymentLinkRepository paymentLinkRepository;

    private final PaymentLinkArchiveService paymentLinkArchiveService;

    private final ContractorPaymentTargetAccessPolicy contractorPaymentTargetAccessPolicy;

    private void expireStaleManualLinks(LocalDateTime now) {
        preparationWorkflow.expireStaleManualLinks(now);
    }

    @Transactional
    public AdminPaymentLinksPageResponse adminLinks(int page, int size, String statusFilter, String search, LocalDate from, LocalDate to, String source) {
        boolean excludePrivilegedTargets = contractorPaymentTargetAccessPolicy.excludePrivilegedTargets();
        if (!excludePrivilegedTargets) {
            expireStaleManualLinks(LocalDateTime.now());
        }
        int resolvedPage = Math.max(0, page);
        int resolvedSize = Math.max(10, Math.min(size, 100));
        String resolvedFilter = normalizeStatusFilter(statusFilter);
        String resolvedSearch = normalize(search);
        String resolvedSource = normalizeSource(source);
        String searchText = resolvedSearch.isBlank() ? null : "%" + resolvedSearch.toLowerCase(Locale.ROOT) + "%";
        Long searchId = parseLongOrNull(resolvedSearch);
        LocalDateTime fromAt = from == null ? null : from.atStartOfDay();
        LocalDateTime toAt = to == null ? null : to.plusDays(1).atStartOfDay();
        if ("ARCHIVE".equals(resolvedSource)) {
            return paymentLinkArchiveService.archivedLinks(resolvedPage, resolvedSize, resolvedFilter, resolvedSearch, searchId, from, to, excludePrivilegedTargets);
        }
        Page<PaymentLink> links = paymentLinkRepository.findAdminPage(resolvedFilter, searchText, searchId, fromAt, toAt, REUSABLE_STATUSES, PAID_STATUSES, REFUNDED_STATUSES, FAILED_STATUSES, MANUAL_METHODS, excludePrivilegedTargets, PageRequest.of(resolvedPage, resolvedSize));
        PaymentLinkAdminSummary summary = paymentLinkRepository.summarizeAdminPage(resolvedFilter, searchText, searchId, fromAt, toAt, REUSABLE_STATUSES, PAID_STATUSES, REFUNDED_STATUSES, FAILED_STATUSES, MANUAL_METHODS, MANUAL_PENDING_STATUSES, REFUNDABLE_STATUSES, REJECTED_STATUSES, PaymentReceiptStatus.PENDING, LocalDateTime.now().minusHours(24), excludePrivilegedTargets);
        return new AdminPaymentLinksPageResponse(links.stream().map(this::toAdminResponse).toList(), links.getNumber(), links.getSize(), links.getTotalElements(), links.getTotalPages(), resolvedSource, toSummaryResponse(summary));
    }

    private String normalizeStatusFilter(String statusFilter) {
        String value = normalize(statusFilter).toLowerCase(Locale.ROOT);
        return switch(value) {
            case "active", "paid", "refunded", "failed", "created", "manual" ->
                value;
            default ->
                "all";
        };
    }

    private String normalizeSource(String source) {
        String value = normalize(source).toUpperCase(Locale.ROOT);
        return "ARCHIVE".equals(value) ? "ARCHIVE" : "LIVE";
    }

    private Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private AdminPaymentLinkSummaryResponse toSummaryResponse(PaymentLinkAdminSummary summary) {
        return paymentPresenter.toSummaryResponse(summary);
    }

    private AdminPaymentLinkResponse toAdminResponse(PaymentLink link) {
        return paymentPresenter.toAdminResponse(link);
    }

    private String normalize(String value) {
        return paymentPresenter.normalize(value);
    }
}

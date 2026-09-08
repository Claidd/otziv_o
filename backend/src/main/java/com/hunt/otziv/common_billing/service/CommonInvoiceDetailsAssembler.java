package com.hunt.otziv.common_billing.service;

import static com.hunt.otziv.common_billing.service.CommonInvoiceInitializationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceCancellationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoicePresenter.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceSettlementService.*;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceNextCycleResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceOrderResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoicePaymentRefResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository.CurrentOrderInvoiceView;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.mapper.OrderDtoMapper;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequest;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.service.PaymentProfileService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceDetailsAssembler {

    private final CommonInvoicePresenter invoicePresenter;

    private final CommonInvoiceSettlementService settlementService;

    static final String MIGRATION_PAYMENT_REGISTRY_MANUAL_CONFIRM_REASON = "nonterminal_or_unknown_payment_ref_on_invoice";

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    private final CommonInvoicePaymentRefRepository paymentRefRepository;

    private final OrderRepository orderRepository;

    private final NextOrderRequestRepository nextOrderRequestRepository;

    private final OrderDtoMapper orderDtoMapper;

    private final BadReviewTaskService badReviewTaskService;

    private final PaymentProfileService paymentProfileService;

    boolean isPaymentInitManualCheckAttention(CommonInvoice invoice) {
        String error = attentionError(invoice);
        return error.startsWith(PAYMENT_INIT_STALE) || error.startsWith("payment_init_conflict") || error.startsWith("payment_init_exception") || error.startsWith("payment_init_response_mismatch") || error.startsWith("payment_init_response_collision") || error.startsWith("payment_init_invalid_url") || error.startsWith("payment_cached_invalid_url") || error.startsWith("tbank_init_failed") || isManuallyConfirmableMigrationPaymentRegistryAttention(invoice);
    }

    boolean isMigrationPaymentRegistryAttention(CommonInvoice invoice) {
        return settlementService.isMigrationPaymentRegistryAttention(invoice);
    }

    boolean isManuallyConfirmableMigrationPaymentRegistryAttention(CommonInvoice invoice) {
        String error = attentionError(invoice);
        if (!error.startsWith(MIGRATION_PAYMENT_REGISTRY_ATTENTION)) {
            return false;
        }
        String reason = error.substring(MIGRATION_PAYMENT_REGISTRY_ATTENTION.length());
        int separator = reason.indexOf(';');
        if (separator >= 0) {
            reason = reason.substring(0, separator);
        }
        return MIGRATION_PAYMENT_REGISTRY_MANUAL_CONFIRM_REASON.equals(reason.trim());
    }

    boolean isPreparedPaymentRef(CommonInvoicePaymentRef ref) {
        String status = paymentRefStatus(ref);
        return PAYMENT_REF_INIT_PREPARED.equals(status) || PAYMENT_REF_INIT_CONFLICT.equals(status);
    }

    String paymentRefStatus(CommonInvoicePaymentRef ref) {
        return settlementService.paymentRefStatus(ref);
    }

    String normalizedPaymentProvider(CommonInvoicePaymentRef ref) {
        return settlementService.normalizedPaymentProvider(ref);
    }

    String providerOrderId(CommonInvoicePaymentRef ref) {
        return CommonInvoicePaymentIdentity.providerOrderId(ref);
    }

    String providerPaymentId(CommonInvoicePaymentRef ref) {
        return settlementService.providerPaymentId(ref);
    }

    String providerMerchantId(CommonInvoicePaymentRef ref) {
        return CommonInvoicePaymentIdentity.providerMerchantId(ref);
    }

    String attentionError(CommonInvoice invoice) {
        return settlementService.attentionError(invoice);
    }

    CommonInvoiceDetailsResponse invoiceDetails(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        List<CommonInvoicePaymentRef> paymentRefs = paymentRefEvidenceRows(invoice);
        Map<String, String> terminalLabels = paymentTerminalLabels(invoice, paymentRefs);
        return new CommonInvoiceDetailsResponse(toInvoiceSummary(invoice, items, terminalLabels.get(normalize(invoice == null ? null : invoice.getTbankTerminalKey()))), items.stream().map(this::toOrderResponse).toList(), toOrderCards(items), toNextCycleOrders(items), toPaymentRefEvidence(paymentRefs, terminalLabels), paymentEvidenceToken(invoice, paymentRefs));
    }

    List<CommonInvoicePaymentRef> paymentRefEvidenceRows(CommonInvoice invoice) {
        if (invoice == null || invoice.getId() == null || (!isPaymentInitManualCheckAttention(invoice) && !isMigrationPaymentRegistryAttention(invoice))) {
            return List.of();
        }
        return filterPaymentRefEvidenceRows(paymentRefRepository.findByInvoiceIdOrderByCreatedAtAsc(invoice.getId()));
    }

    List<CommonInvoicePaymentRef> filterPaymentRefEvidenceRows(List<CommonInvoicePaymentRef> paymentRefs) {
        return (paymentRefs == null ? List.<CommonInvoicePaymentRef>of() : paymentRefs).stream().filter(ref -> ref != null && (isPreparedPaymentRef(ref) || !normalize(ref.getTbankOrderId()).isBlank() || !normalize(ref.getTbankPaymentId()).isBlank() || !providerOrderId(ref).isBlank() || !providerPaymentId(ref).isBlank())).toList();
    }

    List<CommonInvoicePaymentRefResponse> toPaymentRefEvidence(List<CommonInvoicePaymentRef> paymentRefs, Map<String, String> terminalLabels) {
        return (paymentRefs == null ? List.<CommonInvoicePaymentRef>of() : paymentRefs).stream().map(ref -> new CommonInvoicePaymentRefResponse(ref.getId(), paymentRefStatus(ref), providerOrderId(ref).isBlank() ? null : providerOrderId(ref), providerPaymentId(ref).isBlank() ? null : providerPaymentId(ref), ref.getAmountKopecks(), PROVIDER_TOCHKA.equals(normalizedPaymentProvider(ref)) ? "Точка Банк" : terminalLabels == null ? null : terminalLabels.get(normalize(ref.getTbankTerminalKey())), providerMerchantId(ref).isBlank() ? null : providerMerchantId(ref), normalize(ref.getReason()).isBlank() ? null : ref.getReason())).toList();
    }

    Map<String, String> paymentTerminalLabels(CommonInvoice invoice, List<CommonInvoicePaymentRef> paymentRefs) {
        Set<String> terminalKeys = new HashSet<>();
        String invoiceTerminalKey = normalize(invoice == null ? null : invoice.getTbankTerminalKey());
        if (!invoiceTerminalKey.isBlank()) {
            terminalKeys.add(invoiceTerminalKey);
        }
        for (CommonInvoicePaymentRef ref : paymentRefs == null ? List.<CommonInvoicePaymentRef>of() : paymentRefs) {
            String terminalKey = normalize(ref == null ? null : ref.getTbankTerminalKey());
            if (!terminalKey.isBlank()) {
                terminalKeys.add(terminalKey);
            }
        }
        if (terminalKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, PaymentProfile> profilesByTerminal = paymentProfileService.findByTerminalKeys(terminalKeys);
        if (profilesByTerminal == null) {
            profilesByTerminal = Map.of();
        }
        Map<String, String> labels = new HashMap<>();
        for (String terminalKey : terminalKeys) {
            PaymentProfile profile = profilesByTerminal.get(terminalKey);
            String label = normalize(profile == null ? null : profile.getName());
            if (label.isBlank()) {
                label = terminalKey;
            }
            labels.put(terminalKey, label);
        }
        return labels;
    }

    String paymentEvidenceToken(CommonInvoice invoice, List<CommonInvoicePaymentRef> paymentRefs) {
        if (invoice == null || invoice.getId() == null || (!isPaymentInitManualCheckAttention(invoice) && !isMigrationPaymentRegistryAttention(invoice))) {
            return null;
        }
        StringBuilder evidence = new StringBuilder(512);
        appendEvidenceField(evidence, invoice.getId());
        appendEvidenceField(evidence, invoice.getStatus());
        appendEvidenceField(evidence, invoice.getLastError());
        appendEvidenceField(evidence, invoice.getTbankOrderId());
        appendEvidenceField(evidence, invoice.getTbankPaymentId());
        appendEvidenceField(evidence, invoice.getTbankTerminalKey());
        appendEvidenceField(evidence, invoice.getTbankPaymentAmountKopecks());
        appendEvidenceField(evidence, invoice.getTbankPaymentCreatedAt());
        appendEvidenceField(evidence, invoice.getPaymentUrl());
        for (CommonInvoicePaymentRef ref : paymentRefs == null ? List.<CommonInvoicePaymentRef>of() : paymentRefs) {
            appendEvidenceField(evidence, ref.getId());
            appendEvidenceField(evidence, ref.getStatus());
            appendEvidenceField(evidence, ref.getTbankOrderId());
            appendEvidenceField(evidence, ref.getTbankPaymentId());
            appendEvidenceField(evidence, ref.getTbankTerminalKey());
            appendEvidenceField(evidence, normalizedPaymentProvider(ref));
            appendEvidenceField(evidence, ref.getPaymentProfileId());
            appendEvidenceField(evidence, providerOrderId(ref));
            appendEvidenceField(evidence, providerPaymentId(ref));
            appendEvidenceField(evidence, providerMerchantId(ref));
            appendEvidenceField(evidence, ref.getProviderPaymentMode());
            appendEvidenceField(evidence, ref.getProviderTestMode());
            appendEvidenceField(evidence, ref.getProviderStatus());
            appendEvidenceField(evidence, ref.getProviderExpiresAt());
            appendEvidenceField(evidence, ref.getAmountKopecks());
            appendEvidenceField(evidence, ref.getReason());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(evidence.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    void appendEvidenceField(StringBuilder target, Object value) {
        String text = value == null ? "" : String.valueOf(value);
        target.append(text.length()).append(':').append(text).append('|');
    }

    List<CommonInvoiceNextCycleResponse> toNextCycleOrders(List<CommonInvoiceOrder> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Long> sourceOrderIds = items.stream().map(CommonInvoiceOrder::getOrder).filter(order -> order != null && order.getId() != null).map(Order::getId).toList();
        if (sourceOrderIds.isEmpty()) {
            return List.of();
        }
        List<NextOrderRequest> requests = nextOrderRequestRepository.findBySourceOrderIdsWithCreatedOrder(sourceOrderIds)
                .stream().filter(request -> request.getCreatedOrder() != null).toList();
        Map<Long, CurrentOrderInvoiceView> memberships = currentInvoiceMemberships(requests);
        return requests.stream().map(request -> toNextCycleResponse(request,
                request.getCreatedOrder().getId() == null ? null : memberships.get(request.getCreatedOrder().getId()))).toList();
    }

    CommonInvoiceNextCycleResponse toNextCycleResponse(NextOrderRequest request) {
        Map<Long, CurrentOrderInvoiceView> memberships = currentInvoiceMemberships(List.of(request));
        return toNextCycleResponse(request, request.getCreatedOrder() == null || request.getCreatedOrder().getId() == null ? null
                : memberships.get(request.getCreatedOrder().getId()));
    }

    private Map<Long, CurrentOrderInvoiceView> currentInvoiceMemberships(List<NextOrderRequest> requests) {
        List<Long> createdOrderIds = requests.stream().map(NextOrderRequest::getCreatedOrder)
                .filter(order -> order != null && order.getId() != null).map(Order::getId).distinct().toList();
        if (createdOrderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, CurrentOrderInvoiceView> memberships = new HashMap<>();
        for (CurrentOrderInvoiceView binding : invoiceOrderRepository.findCurrentInvoiceBindingsByOrderIds(createdOrderIds)) {
            if (memberships.putIfAbsent(binding.getOrderId(), binding) != null) {
                // The old singular lookup failed on ambiguous active membership instead of choosing an invoice.
                throw new IncorrectResultSizeDataAccessException(1, 2);
            }
        }
        return memberships;
    }

    private CommonInvoiceNextCycleResponse toNextCycleResponse(NextOrderRequest request, CurrentOrderInvoiceView linkedInvoice) {
        Order created = request.getCreatedOrder();
        return new CommonInvoiceNextCycleResponse(request.getSourceOrder() == null ? null : request.getSourceOrder().getId(), created == null ? null : created.getId(), linkedInvoice == null ? null : linkedInvoice.getInvoiceId(), linkedInvoice == null ? null : linkedInvoice.getInvoiceStatus().name(), created == null || created.getCompany() == null ? "" : created.getCompany().getTitle(), created == null || created.getFilial() == null ? "" : created.getFilial().getTitle(), statusTitle(created));
    }

    List<OrderDTOList> toOrderCards(List<CommonInvoiceOrder> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Long> ids = items.stream().map(CommonInvoiceOrder::getOrder).filter(order -> order != null && order.getId() != null).map(Order::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> orderById = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            orderById.put(ids.get(i), i);
        }
        List<OrderDTOList> cards = orderRepository.findOrderListRows(ids).stream().map(orderDtoMapper::toBoardDTO).filter(card -> card != null && card.getId() != null).sorted(Comparator.comparingInt(card -> orderById.getOrDefault(card.getId(), Integer.MAX_VALUE))).toList();
        badReviewTaskService.enrichOrderList(cards);
        return cards;
    }

    CommonInvoiceSummaryResponse toInvoiceSummary(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoicePresenter.toInvoiceSummary(invoice, items);
    }

    CommonInvoiceSummaryResponse toInvoiceSummary(CommonInvoice invoice, List<CommonInvoiceOrder> items, String tbankTerminalLabel) {
        return invoicePresenter.toInvoiceSummary(invoice, items, tbankTerminalLabel);
    }

    CommonInvoiceOrderResponse toOrderResponse(CommonInvoiceOrder item) {
        Order order = item.getOrder();
        Company company = order.getCompany();
        return new CommonInvoiceOrderResponse(order.getId(), company == null ? null : company.getId(), company == null ? "" : company.getTitle(), order.getFilial() == null ? "" : order.getFilial().getTitle(), statusTitle(order), normalize(item.getOriginalOrderStatusTitle()), amountRubles(item.getAmountKopecks()), item.getAmountKopecks(), item.isReady(), item.isPaid(), item.isUnpaid(), !hasFrozenCommonPaymentRoute(item.getInvoice()) && item.getInvoice().getStatus() != CommonInvoiceStatus.PAID && item.getInvoice().getStatus() != CommonInvoiceStatus.UNPAID && item.getInvoice().getStatus() != CommonInvoiceStatus.BAN && item.getInvoice().getStatus() != CommonInvoiceStatus.ARCHIVED && item.getInvoice().getStatus() != CommonInvoiceStatus.NEEDS_ATTENTION, item.getPaidAt(), resolvedPaymentMethod(item), normalize(item.getManualPaidBy()), normalize(item.getManualPaymentComment()), normalize(item.getManualPaymentReceiptUrl()));
    }

    String resolvedPaymentMethod(CommonInvoiceOrder item) {
        String method = normalize(item.getPaymentMethod()).toUpperCase(Locale.ROOT);
        if (!method.isBlank()) {
            return method;
        }
        if (!item.isPaid()) {
            return "";
        }
        CommonInvoice invoice = item.getInvoice();
        if (invoice != null && (!normalize(invoice.getTbankPaymentId()).isBlank() || !normalize(invoice.getTbankOrderId()).isBlank())) {
            return PAYMENT_METHOD_TBANK;
        }
        return "MANUAL_LEGACY";
    }

    boolean hasFrozenCommonPaymentRoute(CommonInvoice invoice) {
        return CommonInvoiceRouteState.hasFrozenCommonPaymentRoute(invoice);
    }

    BigDecimal amountRubles(long kopecks) {
        return settlementService.amountRubles(kopecks);
    }

    String statusTitle(Order order) {
        return settlementService.statusTitle(order);
    }

    String normalize(String value) {
        return settlementService.normalize(value);
    }
}

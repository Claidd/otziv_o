package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.dto.ManagerManualCardPaymentResultResponse;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.repository.SpecialistManualPaymentReviewRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ManualTransferReceiptService {
    public record Line(@NotNull @Positive Long orderId, @Positive long amountKopecks) {}
    public record Transfer(@NotBlank @Size(max=100) String bankReference, @NotNull @Positive Long recipientProfileId,
            @Positive long amountKopecks, @NotEmpty @Size(max=100) List<@NotNull @Valid Line> orders,
            @NotBlank @Size(max=500) String reason, @Size(max=1024) String receiptUrl) {}
    private final PaymentLinkRepository links;
    private final ManagerAccessService access;
    private final SpecialistManualPaymentReviewRepository reviews;
    private final SpecialistManualPaymentReviewService reviewService;
    private final SpecialistManualPaymentReviewSettlementService settlement;
    private final ManualCardRoutePolicy routes;
    private final ManualCardPaymentWorkflow payments;
    private final PaymentLinkTransactionExecutor transactions;
    private final JdbcTemplate jdbc;

    public List<ManagerManualCardPaymentResultResponse> record(Transfer transfer, Authentication actor) {
        List<Line> lines = transfer.orders().stream().sorted(Comparator.comparing(Line::orderId)).toList();
        long sum = 0;
        for (Line line : lines) {
            if (line.amountKopecks() > transfer.amountKopecks() - sum) throw conflict("Распределено больше суммы перевода");
            sum += line.amountKopecks();
        }
        if (sum != transfer.amountKopecks() || lines.stream().map(Line::orderId).distinct().count() != lines.size())
            throw conflict("Проверьте сумму перевода и список заказов");
        String url = payments.validatedReceiptUrl(transfer.receiptUrl());
        List<Long> reviewIds = transactions.required(() -> reserve(transfer, lines, url, actor));
        List<ManagerManualCardPaymentResultResponse> results = new ArrayList<>();
        for (Long id : reviewIds) {
            settlement.creditNow(id);
            results.add(reviewService.result(reviews.findById(id).orElseThrow()));
        }
        return results;
    }

    private List<Long> reserve(Transfer transfer, List<Line> lines, String url, Authentication actor) {
        Map<Long, Order> lockedOrders = new HashMap<>();
        for (Line line : lines) {
            Order order = payments.lockOrderForManualPayment(line.orderId()).orElseThrow(() -> conflict("Заказ не найден"));
            access.requireOrderAccess(line.orderId(), actor);
            lockedOrders.put(line.orderId(), order);
        }
        jdbc.update("""
                INSERT INTO manual_transfer_receipts(recipient_profile_id,bank_reference,amount_kopecks,reason,receipt_url,created_by)
                VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id
                """, transfer.recipientProfileId(), transfer.bankReference().trim(), transfer.amountKopecks(),
                transfer.reason().trim(), url, actor.getName());
        var header = jdbc.queryForMap("""
                SELECT id,amount_kopecks,reason,receipt_url FROM manual_transfer_receipts
                WHERE recipient_profile_id=? AND bank_reference=? FOR UPDATE
                """, transfer.recipientProfileId(), transfer.bankReference().trim());
        long id = ((Number)header.get("id")).longValue();
        var saved = jdbc.queryForList("SELECT order_id,review_id,amount_kopecks FROM manual_transfer_orders WHERE receipt_id=? ORDER BY order_id", id);
        if (!saved.isEmpty()) {
            boolean same = ((Number)header.get("amount_kopecks")).longValue() == transfer.amountKopecks()
                    && Objects.equals(header.get("reason"), transfer.reason().trim()) && Objects.equals(header.get("receipt_url"), url)
                    && lines.equals(saved.stream().map(row -> new Line(((Number)row.get("order_id")).longValue(),
                            ((Number)row.get("amount_kopecks")).longValue())).toList());
            if (!same) throw conflict("Этот перевод уже записан с другим распределением");
            return saved.stream().map(row -> ((Number)row.get("review_id")).longValue()).toList();
        }
        List<Long> reviewIds = new ArrayList<>();
        for (Line line : lines) {
            Order order = lockedOrders.get(line.orderId());
            var link = routes.selectManualCardPaymentRoute(links.findByOrderIdForUpdate(line.orderId()));
            if (line.amountKopecks() != link.getAmountKopecks() || reviews.lockByPaymentLinkId(link.getId()).isPresent())
                throw conflict("По заказу №" + line.orderId() + " изменилась сумма или уже зарегистрирован перевод");
            payments.requireManualCardPaymentLocalEligibility(order, link, line.orderId());
            payments.requireCurrentManualCardRouteAmount(order, link);
            var candidate = routes.manualCardPaymentContextForRoute(order, link).candidates().stream()
                    .filter(c -> transfer.recipientProfileId().equals(c.recipientProfileId())
                            && (c.recipientType() == ContractorRecipientType.SPECIALIST || c.recipientType() == ContractorRecipientType.MANAGER))
                    .findFirst().orElseThrow(() -> conflict("Выбранный получатель недоступен по заказу №" + line.orderId()));
            var review = reviewService.requestRecipient(line.orderId(), link.getId(), line.amountKopecks(), candidate.recipientType().name(),
                    candidate.recipientProfileId(), candidate.key(), transfer.reason().trim(), url, actor.getName(), actor);
            jdbc.update("INSERT INTO manual_transfer_orders(receipt_id,order_id,review_id,amount_kopecks) VALUES(?,?,?,?)",
                    id, line.orderId(), review.getId(), line.amountKopecks());
            reviewIds.add(review.getId());
        }
        return reviewIds;
    }

    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}

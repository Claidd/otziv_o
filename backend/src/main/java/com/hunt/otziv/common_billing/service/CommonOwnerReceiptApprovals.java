package com.hunt.otziv.common_billing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionRequest;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentOptionsResponse;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentOptionsResponse.OwnerReport;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CommonOwnerReceiptApprovals {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ManagerPermissionService permissions;

    static boolean hasBankRoute(CommonInvoice invoice) {
        return com.hunt.otziv.payments.service.BankPaymentInstructionSource.isBankLink(invoice.getPaymentRouteType())
                || ((invoice.getPaymentRouteType() == null || invoice.getPaymentRouteType().isBlank())
                && invoice.getTbankPaymentId() != null && !invoice.getTbankPaymentId().isBlank());
    }

    static boolean includesOwner(CommonManualPaymentAttributionRequest request) {
        return request.attributions().stream().anyMatch(row -> row != null
                && (row.recipientType() == ContractorRecipientType.OWNER || "OWNER".equals(row.recipientKey())));
    }

    private boolean canDecide() {
        return permissions.hasAnyRole(SecurityContextHolder.getContext().getAuthentication(), "OWNER", "ADMIN");
    }

    private record Saved(OwnerReport report, String fingerprint) {}

    private Saved pending(long invoiceId) {
        return jdbc.query("SELECT * FROM common_owner_receipt_approval WHERE pending_invoice_id=? FOR UPDATE", (rs, index) -> {
            try {
                var request = json.readValue(rs.getString("request_json"), CommonManualPaymentAttributionRequest.class);
                return new Saved(new OwnerReport(rs.getLong("id"), rs.getString("reported_by"), rs.getBoolean("attention_mode"),
                        canDecide(), request), rs.getString("route_fingerprint"));
            } catch (JsonProcessingException e) { throw new IllegalStateException("Не удалось прочитать запрос подтверждения", e); }
        }, invoiceId).stream().findFirst().orElse(null);
    }

    /** The report that currently waits for the owner, for the notices sent around its decision. */
    record Pending(long id, String reportedBy) {}

    java.util.Optional<Pending> pendingReport(long invoiceId) {
        return jdbc.query("SELECT id, reported_by FROM common_owner_receipt_approval WHERE pending_invoice_id=?",
                (rs, index) -> new Pending(rs.getLong("id"), rs.getString("reported_by")), invoiceId).stream().findFirst();
    }

    /** The receipt time of the report staged with this request key: its repeat and the owner's decision keep it. */
    java.util.Optional<java.time.LocalDateTime> reportedReceiptTime(long invoiceId, String requestKey) {
        return jdbc.query("SELECT request_json FROM common_owner_receipt_approval WHERE invoice_id=? AND request_key=? ORDER BY id DESC LIMIT 1",
                (rs, index) -> rs.getString("request_json"), invoiceId, requestKey).stream().findFirst().map(body -> {
            try { return json.readValue(body, CommonManualPaymentAttributionRequest.class).effectiveAt(); }
            catch (JsonProcessingException e) { throw new IllegalStateException("Не удалось прочитать запрос подтверждения", e); }
        });
    }

    CommonManualPaymentOptionsResponse attach(CommonManualPaymentOptionsResponse options) {
        Saved saved = pending(options.invoiceId());
        return saved == null ? options : options.withReport(saved.report());
    }

    // Caller holds the invoice lock. The fingerprint covers the instructions and the exact invoice positions.
    boolean stage(CommonInvoice invoice, List<CommonInvoiceOrder> items, CommonManualPaymentAttributionRequest request,
            boolean attention, Principal actor, CommonManualPaymentOptionsResponse options) {
        Saved saved = pending(invoice.getId());
        String fingerprint = fingerprint(invoice, items);
        if (saved != null) {
            if (!saved.report().request().equals(request) || saved.report().attentionMode() != attention
                    || !saved.fingerprint().equals(fingerprint)) throw conflict("Счёт или запрос изменился. Проверьте ожидающее подтверждение владельца");
            return !canDecide();
        }
        if (canDecide()) return false;
        long remaining = options.remainingKopecks();
        var seen = new java.util.HashSet<String>();
        for (var row : request.attributions()) {
            if (row == null || row.amountKopecks() <= 0 || row.amountKopecks() > remaining || !seen.add(row.recipientKey())
                    || options.candidates().stream().noneMatch(c -> c.key().equals(row.recipientKey())
                    && c.recipientType() == row.recipientType() && Objects.equals(c.recipientProfileId(), row.recipientProfileId())))
                throw conflict("Проверьте получателей и суммы запроса");
            remaining -= row.amountKopecks();
        }
        if (remaining != 0) throw conflict("Сумма не совпадает с остатком общего счёта");
        Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM common_owner_receipt_approval WHERE invoice_id=? AND request_key=?",
                Integer.class, invoice.getId(), request.idempotencyKey());
        if (used != null && used > 0) throw conflict("Этот запрос уже рассмотрен. Откройте форму заново");
        try {
            jdbc.update("""
                    INSERT INTO common_owner_receipt_approval(invoice_id,pending_invoice_id,request_key,request_json,
                    route_fingerprint,attention_mode,reported_by) VALUES(?,?,?,?,?,?,?)
                    """, invoice.getId(), invoice.getId(), request.idempotencyKey(), json.writeValueAsString(request),
                    fingerprint, attention, actor.getName());
        } catch (JsonProcessingException e) { throw new IllegalStateException("Не удалось сохранить подтверждение", e); }
        return true;
    }

    void requireMatchingReport(long invoiceId, CommonManualPaymentAttributionRequest request) {
        Saved saved = pending(invoiceId);
        if (saved != null && (!canDecide() || !saved.report().request().equals(request)))
            throw conflict("Сначала рассмотрите ожидающий запрос владельцу");
    }

    // The decision and final attribution commit in the same transaction.
    void accept(CommonInvoice invoice, List<CommonInvoiceOrder> items, CommonManualPaymentAttributionRequest request, Principal actor) {
        Saved saved = pending(invoice.getId());
        if (includesOwner(request) && !canDecide()) throw conflict("Поступление владельцу ожидает его подтверждения");
        if (saved == null) return;
        if (!canDecide() || !saved.report().request().equals(request) || !saved.fingerprint().equals(fingerprint(invoice, items)))
            throw conflict("Сначала рассмотрите запрос владельцу. Состав или маршрут счёта мог измениться");
        decide(invoice.getId(), saved.report().id(), "ACCEPTED", actor);
    }

    @Transactional
    public void reject(long invoiceId, long reportId, Principal actor) {
        if (!canDecide()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        decide(invoiceId, reportId, "REJECTED", actor);
    }

    private void decide(long invoiceId, long reportId, String decision, Principal actor) {
        if (jdbc.update("""
                UPDATE common_owner_receipt_approval SET pending_invoice_id=NULL,decision=?,decided_by=?,decided_at=NOW(6)
                WHERE pending_invoice_id=? AND id=?
                """, decision, actor.getName(), invoiceId, reportId) != 1) throw conflict("Запрос уже рассмотрен");
    }

    private String fingerprint(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        var positions = items.stream().map(i -> i.orderId() + "/" + i.getAmountKopecks() + "/" + i.isPaid()
                + "/" + i.isActiveMembership() + "/" + i.isOrdinaryAfterPaperCancel()).sorted().toList();
        String source = java.util.Arrays.asList(invoice.getId(), invoice.getPaymentRouteSelectedAt(), invoice.getPaymentRouteType(),
                invoice.getInvoicePaymentMode(), invoice.getContractorAllocationId(), invoice.getPaymentRouteManualTaskSourceGeneration(),
                invoice.getAmountKopecks(), invoice.getPaidKopecks(), positions).toString();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}

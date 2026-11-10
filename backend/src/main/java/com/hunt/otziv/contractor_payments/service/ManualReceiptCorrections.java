package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.common_billing.api.ManualReceiptInvoiceDebt;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentAttribution;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.contractor_payments.repository.ContractorActualPaymentAttributionRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.p_products.api.ManualReceiptOrderDebt;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ManualReceiptCorrections {
    public enum Action { REASSIGN, VOID }
    public record Request(@NotNull Action action, @NotBlank @Size(max=80) String requestKey,
            @Positive Long recipientProfileId, boolean owner, @NotBlank @Size(max=500) String reason) {}
    public record Receipt(long id, Long orderId, Long invoiceId, long amountKopecks, String recipient,
            String actor, String reason, String correction, String correctedBy, String correctionReason, boolean canAssignOwner) {}
    public record Recipient(Long id,String name) {}
    private static final String OWNER_ALLOWED = """
            CASE WHEN a.source_kind='COMMON_INVOICE' THEN NOT EXISTS (
                SELECT 1 FROM common_invoices i WHERE i.invoice_id=a.common_invoice_id
                AND i.payment_route_type IN ('BANK_LINK','TBANK_LINK','TOCHKA_LINK')
            ) ELSE a.evidence_id IS NULL OR a.source_id=a.evidence_id END
            """;

    private final ContractorActualPaymentAttributionRepository attributions;
    private final ContractorPaymentProfileRepository profiles;
    private final ContractorActualPaymentAttributionService accounting;
    private final ManualReceiptOrderDebt orderDebt;
    private final ManualReceiptInvoiceDebt invoiceDebt;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly=true)
    public List<Recipient> recipients() {
        return profiles.findAllWithUser().stream().map(p -> new Recipient(p.getId(),p.userDisplayName())).toList();
    }

    @Transactional(readOnly=true)
    public List<Receipt> find(long target, boolean invoice) {
        if (target <= 0) throw conflict("Укажите номер заказа или общего счёта");
        return jdbc.query("""
                SELECT a.id,a.order_id,a.common_invoice_id,a.amount_kopecks,
                       COALESCE(u.fio,IF(a.actual_manual_payment_task_id IS NULL,'Владелец',CONCAT('Задание №',a.actual_manual_payment_task_id))),
                       a.actor,a.reason,c.action,c.actor,c.reason,
                """ + OWNER_ALLOWED + """
                FROM contractor_actual_payment_attributions a LEFT JOIN users u ON u.id=a.actual_recipient_user_id
                LEFT JOIN manual_receipt_corrections c ON c.original_id=a.id
                WHERE (? AND a.common_invoice_id=?) OR (NOT ? AND a.order_id=?) ORDER BY a.id DESC
                """,
                (rs,n) -> new Receipt(rs.getLong(1), rs.getObject(2,Long.class),rs.getObject(3,Long.class),rs.getLong(4),
                        rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getBoolean(11)),invoice,target,invoice,target);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void correct(long id, Request request, Principal actor) {
        boolean cancel=request.action()==Action.VOID;
        if (cancel ? request.owner() || request.recipientProfileId()!=null : request.owner()==(request.recipientProfileId()!=null))
            throw conflict("Выберите фактического получателя или отмену зачисления");
        var snapshot=attributions.findById(id).orElseThrow(() -> conflict("Ручное поступление не найдено"));
        boolean group=snapshot.getSourceKind()==ContractorActualPaymentSourceKind.COMMON_INVOICE;
        if (group) {
            jdbc.queryForList("SELECT o.order_id FROM orders o JOIN common_invoice_orders i ON i.order_id=o.order_id WHERE i.invoice_id=? AND i.active_membership ORDER BY o.order_id FOR UPDATE",Long.class,snapshot.getCommonInvoiceId());
            jdbc.queryForObject("SELECT invoice_id FROM common_invoices WHERE invoice_id=? FOR UPDATE",Long.class,snapshot.getCommonInvoiceId());
        } else jdbc.queryForObject("SELECT order_id FROM orders WHERE order_id=? FOR UPDATE",Long.class,snapshot.getOrderId());
        var previous=jdbc.queryForList("SELECT request_key,action,recipient_profile_id,reason FROM manual_receipt_corrections WHERE original_id=? FOR UPDATE",id);
        if (!previous.isEmpty()) {
            var row=previous.getFirst();
            if (request.requestKey().equals(row.get("request_key")) && request.action().name().equals(row.get("action"))
                    && Objects.equals(request.recipientProfileId(),row.get("recipient_profile_id")) && request.reason().trim().equals(row.get("reason"))) return;
            throw conflict("Поступление уже исправлено. Обновите журнал");
        }
        // The attribution is immutable to Hibernate. The order/invoice lock serializes corrections;
        // lock the durable ledger keys in SQL without upgrading read-only managed entities.
        var sourceIds = jdbc.queryForList("""
                SELECT a.id FROM contractor_actual_payment_attributions a
                WHERE a.source_kind=? AND a.source_id=?
                  AND NOT EXISTS (SELECT 1 FROM manual_receipt_corrections c WHERE c.original_id=a.id)
                ORDER BY a.id FOR UPDATE
                """, Long.class, snapshot.getSourceKind().name(), snapshot.getSourceId());
        var active = attributions.findAllById(sourceIds);
        var selected=active.stream().filter(row -> Objects.equals(row.getId(),id)).findFirst().orElseThrow(() -> conflict("Поступление изменилось"));
        var rows=cancel && group ? active.stream().filter(row -> Objects.equals(row.getEvidenceReference(),selected.getEvidenceReference())).toList() : List.of(selected);
        if (!cancel && Objects.equals(request.recipientProfileId(),selected.getActualRecipientProfileId()) && selected.getActualManualPaymentTaskId()==null)
            throw conflict("Этот получатель уже указан");
        if (!cancel && request.owner() && !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT " + OWNER_ALLOWED + " FROM contractor_actual_payment_attributions a WHERE a.id=?", Boolean.class, id)))
            throw conflict("Оплату владельцу по банковской ссылке подтверждает банк");
        long total=0;
        for (ContractorActualPaymentAttribution receipt : rows) {
            total=Math.addExact(total,receipt.getAmountKopecks());
            var replacement=accounting.correctRecordedRecipient(receipt,request.recipientProfileId(),cancel,actor.getName(),
                    request.reason().trim(),"RECEIPT_CORRECTION:"+receipt.getId());
            jdbc.update("""
                    INSERT INTO manual_receipt_corrections(original_id,replacement_id,request_key,action,recipient_profile_id,actor,reason)
                    VALUES(?,?,?,?,?,?,?)
                    """,receipt.getId(),replacement==null?null:replacement.getId(),request.requestKey(),request.action().name(),
                    request.recipientProfileId(),actor.getName(),request.reason().trim());
        }
        if (!group) jdbc.update("""
                UPDATE specialist_manual_payment_reviews SET status='SUPERSEDED',resolved_at=NOW(6),resolved_by=?,
                resolution_note=?,resolution_kind='RECEIPT_CORRECTED',credit_next_attempt_at=NULL
                WHERE payment_link_id=? AND credited_at IS NOT NULL
                """,actor.getName(),request.reason().trim(),selected.getSourceId());
        if (cancel && group) invoiceDebt.reopen(selected.getCommonInvoiceId(),selected.getEvidenceReference(),total,actor,request.reason().trim());
        if (cancel && !group && selected.getEvidenceId() != null && !Objects.equals(selected.getEvidenceId(), selected.getSourceId())) {
            jdbc.update("""
                    UPDATE payment_links SET last_error='manual_receipt_voided' WHERE id=? AND order_id=?
                    AND status='CANCELED' AND last_error LIKE 'manual_card_payment_completed:%'
                    """, selected.getSourceId(), selected.getOrderId());
        }
        if (cancel && !group) orderDebt.reopen(selected.getOrderId(),selected.getEvidenceId()==null?selected.getSourceId():selected.getEvidenceId(),
                total,false,actor,request.reason().trim());
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
}

package com.hunt.otziv.paper_billing;

import static com.hunt.otziv.paper_billing.PaperBillingModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.hunt.otziv.common_billing.api.BankPaperInvoices;
import com.hunt.otziv.payments.service.PaymentLinkSettlementService;
import com.hunt.otziv.payments.tochka.service.TochkaWebhookJwtVerifier;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service @RequiredArgsConstructor
public class PaperBillingSettlement {
    private final PaperBillingStore store;
    private final PaperBillingService service;
    private final PaperBillingSettings settings;
    private final PaperBillingBankClient bank;
    private final PaymentLinkSettlementService standalone;
    private final BankPaperInvoices common;
    private final TochkaWebhookJwtVerifier verifier;
    private final PlatformTransactionManager transactions;
    private final com.hunt.otziv.client_messages.api.PaperInvoiceAutomation automation;
    @org.springframework.beans.factory.annotation.Autowired private com.hunt.otziv.common_billing.api.BankPaperInvoiceParts parts;
    private static final Pattern NUMBER=Pattern.compile("(?<![A-Z0-9-])PB-[0-9]+-[A-F0-9]{8}(?![A-Z0-9-])",Pattern.CASE_INSENSITIVE);
    // A payer who retypes the number by hand often writes it on the Russian layout: «РВ» for PB, look-alike letters in
    // the suffix, a long dash, spaces around the hyphens. On the text with those read as Latin the number must stand
    // apart from any letter or digit, so that it cannot be cut out of a longer word.
    private static final Pattern RETYPED_NUMBER=Pattern.compile(
            "(?<![\\p{L}\\p{N}-])PB\\s*-\\s*[0-9]+\\s*-\\s*[A-F0-9]{8}(?![\\p{L}\\p{N}-])",Pattern.CASE_INSENSITIVE);

    /**
     * Invoice numbers named in a transfer purpose, in their canonical upper-case form. The number as issued is
     * recognised first; then the purpose is read with Cyrillic look-alikes (Р, В, А, С, Е) and dash variants taken
     * as the Latin characters they stand for. The payer, the amount and the document checks that follow are unchanged.
     */
    static LinkedHashSet<String> invoiceNumbers(String purpose) {
        var numbers=new LinkedHashSet<String>();
        if (purpose==null || purpose.isBlank()) return numbers;
        var issued=NUMBER.matcher(purpose);
        while(issued.find()) numbers.add(issued.group().toUpperCase(Locale.ROOT));
        var retyped=RETYPED_NUMBER.matcher(latinLookalikes(purpose));
        while(retyped.find()) numbers.add(retyped.group().replaceAll("\\s","").toUpperCase(Locale.ROOT));
        return numbers;
    }

    static String latinLookalikes(String text) {
        var latin=new StringBuilder(text.length());
        for(int i=0;i<text.length();i++) {
            char symbol=text.charAt(i);
            latin.append(switch(symbol) {
                case 'Р','р' -> 'P';
                case 'В','в' -> 'B';
                case 'А','а' -> 'A';
                case 'С','с' -> 'C';
                case 'Е','е' -> 'E';
                // hyphen, non-breaking hyphen, figure dash, en dash, em dash, horizontal bar, minus
                case '\u2010','\u2011','\u2012','\u2013','\u2014','\u2015','\u2212' -> '-';
                // no-break and narrow no-break spaces
                case '\u00A0','\u202F' -> ' ';
                default -> symbol;
            });
        }
        return latin.toString();
    }
    private static final Pattern LEGACY_ORDER=Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])заказ\\s*(?:№|#)?\\s*([1-9][0-9]{0,17})(?![0-9])");
    public void webhook(String raw) {
        var claims=verifier.verifyIncomingPayment(raw);
        // The subscription receives all incoming transfers, including bank test events.
        // Verify every signature, then ignore traffic unrelated to our invoice numbers.
        String purpose=claims.path("purpose").asText();
        if (invoiceNumbers(purpose).isEmpty() && !LEGACY_ORDER.matcher(purpose).find()) return;
        accept(parse(claims));
    }
    public BankReceipt parse(JsonNode data) {
        var recipient=data.path("SideRecipient"); var payer=data.path("SidePayer");
        if (!"RUB".equals(recipient.path("currency").asText()) || !"RUB".equals(payer.path("currency").asText()))
            throw problem("Некорректная валюта или сумма банковского перевода");
        try {
            long amount=new BigDecimal(recipient.path("amount").asText()).movePointRight(2).longValueExact();
            if (new BigDecimal(payer.path("amount").asText()).movePointRight(2).longValueExact()!=amount)
                throw problem("Суммы сторон банковского перевода не совпадают");
            String payment=data.path("paymentId").asText();
            String account=recipient.path("account").asText()+"/"+recipient.path("bankCode").asText();
            String customer=data.path("customerCode").asText();
            String inn=payer.path("inn").asText();
            String purpose=data.path("purpose").asText();
            if (amount<=0 || payment.isBlank() || payment.length()>150 || purpose.length()>2000 || !validInn(inn)
                    || !account.matches("\\d{20}/\\d{9}") || !customer.equals(bank.customer()))
                throw problem("Банковский перевод не соответствует подключению");
            return new BankReceipt(customer,account,payment,inn,amount,purpose);
        } catch(ArithmeticException | NumberFormatException e) { throw problem("Некорректная сумма банковского перевода"); }
    }
    public void accept(BankReceipt receipt) {
        if (!settings.isEnabled() || !settings.isBankOperationsEnabled()) throw problem("Сверка банковских счетов выключена");
        var numbers=invoiceNumbers(receipt.purpose());
        List<Map<String,Object>> rows;
        if(numbers.size()==1) rows=store.jdbc().queryForList("SELECT * FROM paper_invoice_document WHERE number=?",numbers.iterator().next());
        else if(numbers.isEmpty() && !receipt.purpose().toUpperCase(Locale.ROOT).contains("PB-")) {
            var references=new LinkedHashSet<Long>();
            var legacy=LEGACY_ORDER.matcher(receipt.purpose());
            while(legacy.find()) references.add(Long.parseLong(legacy.group(1)));
            if(references.size()!=1) return;
            // Older bank PDFs suggested "Заказ <target_id>". Never guess by amount
            // alone or choose the newest reissue: the historical reference must
            // identify exactly one document for this supplier and payer.
            var historical=store.jdbc().queryForList("""
                SELECT * FROM paper_invoice_document
                WHERE target_id=? AND customer_code=? AND supplier_account=? AND bank_invoice_id IS NOT NULL
                  AND payment_reference IS NULL
                ORDER BY created_at LIMIT 101
                """,references.iterator().next(),receipt.customerCode(),receipt.accountId());
            if(historical.size()>100) return;
            rows=historical.stream()
                    .filter(row->receipt.payerInn().equals(service.payer((String)row.get("payer_json")).inn())).toList();
            if(rows.size()!=1) return;
        } else return; // unrelated or ambiguous traffic never closes an order
        if (rows.isEmpty()) return;
        var candidate=rows.getFirst();
        String id=(String)candidate.get("id");
        try {
            var transaction=new TransactionTemplate(transactions);
            transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            transaction.executeWithoutResult(tx -> {
                // Canonical order locks before the document lock, including all members of a common invoice.
                if (candidate.get("target_kind").equals("ORDER"))
                    store.jdbc().queryForList("SELECT order_id FROM orders WHERE order_id=? FOR UPDATE",candidate.get("target_id"));
                else if("COMMON_PART".equals(candidate.get("target_kind"))) parts.lock(((Number)candidate.get("target_id")).longValue());
                else {
                    var ids=store.jdbc().queryForList("SELECT order_id FROM common_invoice_orders WHERE invoice_id=? ORDER BY order_id",Long.class,candidate.get("target_id"));
                    for(Long order:ids) store.jdbc().queryForList("SELECT order_id FROM orders WHERE order_id=? FOR UPDATE",order);
                }
                var row=store.jdbc().queryForMap("SELECT * FROM paper_invoice_document WHERE id=? FOR UPDATE",id);
                int inserted=store.jdbc().update("""
                    INSERT IGNORE INTO paper_invoice_bank_receipt(customer_code,account_id,payment_id,document_id,
                    amount_kopecks,payer_inn,purpose,state,received_at) VALUES(?,?,?,?,?,?,?,'RECEIVED',NOW(6))
                    """,receipt.customerCode(),receipt.accountId(),receipt.paymentId(),id,receipt.amountKopecks(),receipt.payerInn(),receipt.purpose());
                if (inserted==0) return;
                if (Boolean.TRUE.equals(store.jdbc().queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM paper_invoice_event WHERE document_id=? AND event_key='PAID_BY_PREVIOUS_LINK')",Boolean.class,id))) {
                    store.jdbc().update("UPDATE paper_invoice_bank_receipt SET state='NEEDS_REVIEW' WHERE customer_code=? AND account_id=? AND payment_id=?",
                            receipt.customerCode(),receipt.accountId(),receipt.paymentId());
                    store.error(id,"NEEDS_REVIEW","По бумажному документу пришла повторная оплата. Долг уже закрыт по прежней банковской ссылке.");
                    return;
                }
                if(row.get("cancel_requested_at")!=null) {
                    store.jdbc().update("UPDATE paper_invoice_bank_receipt SET state='NEEDS_REVIEW' WHERE customer_code=? AND account_id=? AND payment_id=?",
                            receipt.customerCode(),receipt.accountId(),receipt.paymentId());
                    String reason="Перевод "+receipt.paymentId()+" на "+PaperBillingDelivery.amountText(receipt.amountKopecks())
                            +" поступил во время или после отмены счёта № "+row.get("number")+". Нужна ручная сверка; заказ автоматически не оплачен.";
                    store.jdbc().update("UPDATE paper_invoice_document SET stage=IF(cancelled_at IS NULL,'CANCEL_REVIEW',stage),cancel_step=IF(cancelled_at IS NULL,'REVIEW',cancel_step),cancel_replacement_state=IF(cancel_replacement_state IS NULL,NULL,'REVIEW'),cancel_replacement_error=IF(cancel_replacement_state IS NULL,NULL,?),error_text=?,updated_at=NOW(6) WHERE id=?",reason,reason,id);
                    store.event(id,"PAYMENT_REVIEW",reason);
                    service.holdForCancellationReview(row);
                    store.jdbc().update("""
                        UPDATE paper_invoice_document SET stage='NEEDS_REVIEW',error_text=?,updated_at=NOW(6)
                        WHERE target_kind=? AND target_id=? AND cancelled_at IS NULL AND cancel_requested_at IS NULL AND paid_at IS NULL
                        """,reason,row.get("target_kind"),row.get("target_id"));
                    return;
                }
                var payer=service.payer((String)row.get("payer_json"));
                long total = ((Number) row.get("amount_kopecks")).longValue();
                long partial = store.jdbc().queryForObject("""
                        SELECT COALESCE(SUM(amount_kopecks),0) FROM paper_invoice_bank_receipt
                        WHERE document_id=? AND state='PARTIAL'
                        """, Long.class, id);
                if (!receipt.customerCode().equals(row.get("customer_code")) || !receipt.accountId().equals(row.get("supplier_account"))
                        || !receipt.payerInn().equals(payer.inn()) || receipt.amountKopecks() <= 0 || receipt.amountKopecks() > total - partial
                        || row.get("bank_invoice_id")==null || row.get("paid_at")!=null
                        || store.hasUnresolvedReceipt(new Target((String)row.get("target_kind"), ((Number)row.get("target_id")).longValue()), null)
                        || !store.composition(new Target((String)row.get("target_kind"), ((Number)row.get("target_id")).longValue())).equals(row.get("composition_hash"))) {
                    store.jdbc().update("UPDATE paper_invoice_bank_receipt SET state='NEEDS_REVIEW' WHERE customer_code=? AND account_id=? AND payment_id=?",
                            receipt.customerCode(),receipt.accountId(),receipt.paymentId());
                    if (row.get("paid_at")==null) store.error(id,"NEEDS_REVIEW","Проверьте плательщика, сумму и состав счёта");
                    return;
                }
                if ("ORDER".equals(row.get("target_kind")) && partial == 0 && receipt.amountKopecks() < total) {
                    store.jdbc().update("UPDATE paper_invoice_bank_receipt SET state='NEEDS_REVIEW' WHERE customer_code=? AND account_id=? AND payment_id=?",
                            receipt.customerCode(),receipt.accountId(),receipt.paymentId());
                    store.error(id,"NEEDS_REVIEW","По обычному заказу поступила неполная сумма. Проверьте перевод по выписке.");
                    return;
                }
                if (receipt.amountKopecks() < total - partial) {
                    store.jdbc().update("UPDATE paper_invoice_bank_receipt SET state='PARTIAL' WHERE customer_code=? AND account_id=? AND payment_id=?",
                            receipt.customerCode(), receipt.accountId(), receipt.paymentId());
                    return;
                }
                if (row.get("target_kind").equals("ORDER")) {
                    standalone.applyVerifiedPaperBankPayment(((Number)row.get("payment_link_id")).longValue(),total);
                    automation.synchronizeOrder(((Number)row.get("target_id")).longValue());
                }
                else if("COMMON_PART".equals(row.get("target_kind"))) parts.settle(((Number)row.get("target_id")).longValue(),total,receipt.paymentId());
                else common.settle(((Number)row.get("target_id")).longValue(),total,receipt.paymentId());
                store.jdbc().update("""
                    UPDATE paper_invoice_document SET stage=IF(stage='WAITING_PAYMENT','PAID',stage),
                    paid_at=NOW(6),error_text=NULL,updated_at=NOW(6) WHERE id=?
                    """,id);
                store.jdbc().update("UPDATE paper_invoice_bank_receipt SET state='APPLIED' WHERE document_id=? AND state='PARTIAL'", id);
                store.jdbc().update("UPDATE paper_invoice_bank_receipt SET state='APPLIED' WHERE customer_code=? AND account_id=? AND payment_id=?",
                        receipt.customerCode(),receipt.accountId(),receipt.paymentId());
            });
        } catch(org.springframework.web.server.ResponseStatusException failure) {
            // No partial financial writes survive. Keep evidence for an explicit reconciliation.
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                store.jdbc().update("""
                    INSERT IGNORE INTO paper_invoice_bank_receipt(customer_code,account_id,payment_id,document_id,
                    amount_kopecks,payer_inn,purpose,state,received_at) VALUES(?,?,?,?,?,?,?,'NEEDS_REVIEW',NOW(6))
                    """,receipt.customerCode(),receipt.accountId(),receipt.paymentId(),id,receipt.amountKopecks(),receipt.payerInn(),receipt.purpose());
                String reason=clean(failure.getReason());
                store.error(id,"NEEDS_REVIEW",reason.isBlank()?"Состояние заказа изменилось; поступление сохранено для сверки":"Оплата требует сверки: "+reason);
            });
        }
    }
}

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
    private static final Pattern NUMBER=Pattern.compile("(?<![A-Z0-9-])PB-[0-9]+-[A-F0-9]{8}(?![A-Z0-9-])",Pattern.CASE_INSENSITIVE);
    public void webhook(String raw) {
        var claims=verifier.verifyIncomingPayment(raw);
        // The subscription receives all incoming transfers, including bank test events.
        // Verify every signature, then ignore traffic unrelated to our invoice numbers.
        if (!NUMBER.matcher(claims.path("purpose").asText()).find()) return;
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
        var numbers=new LinkedHashSet<String>();
        var matcher=NUMBER.matcher(receipt.purpose());
        while(matcher.find()) numbers.add(matcher.group().toUpperCase(Locale.ROOT));
        if (numbers.size()!=1) return; // unrelated bank traffic never closes an order
        var rows=store.jdbc().queryForList("SELECT * FROM paper_invoice_document WHERE number=?",numbers.iterator().next());
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
                var payer=service.payer((String)row.get("payer_json"));
                long receipts=store.jdbc().queryForObject("SELECT COUNT(*) FROM paper_invoice_bank_receipt WHERE document_id=?",Long.class,id);
                if (!receipt.customerCode().equals(row.get("customer_code")) || !receipt.accountId().equals(row.get("supplier_account"))
                        || !receipt.payerInn().equals(payer.inn()) || receipt.amountKopecks()!=((Number)row.get("amount_kopecks")).longValue()
                        || row.get("bank_invoice_id")==null || receipts!=1 || row.get("paid_at")!=null
                        || !store.composition(new Target((String)row.get("target_kind"),((Number)row.get("target_id")).longValue())).equals(row.get("composition_hash"))) {
                    store.jdbc().update("UPDATE paper_invoice_bank_receipt SET state='NEEDS_REVIEW' WHERE customer_code=? AND account_id=? AND payment_id=?",
                            receipt.customerCode(),receipt.accountId(),receipt.paymentId());
                    if (row.get("paid_at")==null) store.error(id,"NEEDS_REVIEW","Перевод требует сверки: плательщик, сумма, состав счёта или несколько платежей");
                    return;
                }
                if (row.get("target_kind").equals("ORDER"))
                    standalone.applyVerifiedPaperBankPayment(((Number)row.get("payment_link_id")).longValue(),receipt.amountKopecks());
                else common.settle(((Number)row.get("target_id")).longValue(),receipt.amountKopecks(),receipt.paymentId());
                store.jdbc().update("""
                    UPDATE paper_invoice_document SET stage=IF(stage='WAITING_PAYMENT','PAID',stage),
                    paid_at=NOW(6),error_text=NULL,updated_at=NOW(6) WHERE id=?
                    """,id);
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
                store.error(id,"NEEDS_REVIEW","Состояние заказа изменилось; поступление сохранено для сверки");
            });
        }
    }
}

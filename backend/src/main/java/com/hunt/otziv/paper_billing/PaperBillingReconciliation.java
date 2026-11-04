package com.hunt.otziv.paper_billing;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import static com.hunt.otziv.paper_billing.PaperBillingModels.*;

/** Bank statements are an independent recovery source; receipt IDs share the webhook's unique key. */
@Service @RequiredArgsConstructor
public class PaperBillingReconciliation {
    static final String MULTI_PAGE_MARKER="multi_page_window";
    private final java.util.concurrent.atomic.AtomicLong lastManualCheckNanos=new java.util.concurrent.atomic.AtomicLong(System.nanoTime()-java.util.concurrent.TimeUnit.MINUTES.toNanos(5));

    private final PaperBillingStore store;
    private final PaperBillingBankClient bank;
    private final PaperBillingSettings settings;
    private final PaperBillingSettlement settlement;
    /**
     * "Проверить оплату" from a card runs the global bank sweep synchronously; several managers
     * clicking would hammer the bank API next to the 5-minute scheduler. Allow one manual sweep
     * per minute; a skipped click is served by the sweep that just ran or the next scheduled one.
     */
    public void checkRequestedByUser() {
        long now=System.nanoTime();
        long previous=lastManualCheckNanos.get();
        if (now-previous<java.util.concurrent.TimeUnit.MINUTES.toNanos(1) || !lastManualCheckNanos.compareAndSet(previous,now)) {
            return;
        }
        check();
    }

    @Scheduled(fixedDelay=300000,initialDelay=60000)
    public void check() {
        if (!settings.isEnabled() || !settings.isBankOperationsEnabled() || !bank.configured()) return;
        var accounts=store.jdbc().queryForList("""
            SELECT supplier_account,customer_code,DATE_SUB(MIN(DATE(created_at)),INTERVAL 1 DAY) AS first_date
            FROM paper_invoice_document WHERE paid_at IS NULL AND bank_invoice_id IS NOT NULL
            GROUP BY supplier_account,customer_code
            """);
        for(var account:accounts) {
            String id=(String)account.get("supplier_account");
            try { account(account); }
            catch(Exception failure) {
                store.jdbc().update("UPDATE paper_invoice_statement_cursor SET error_text=? WHERE account_id=?",
                        "Не удалось получить полную выписку банка; проверка будет повторена",id);
            }
        }
    }
    void account(Map<String,Object> account) {
        String id=(String)account.get("supplier_account");
        if (!id.matches("\\d{20}/\\d{9}") || !account.get("customer_code").equals(bank.customer())) return;
        store.jdbc().update("""
            INSERT IGNORE INTO paper_invoice_statement_cursor(account_id,customer_code,next_date) VALUES(?,?,?)
            """,id,account.get("customer_code"),account.get("first_date"));
        var cursor=store.jdbc().queryForMap("SELECT * FROM paper_invoice_statement_cursor WHERE account_id=?",id);
        String statement=(String)cursor.get("statement_id");
        if (statement==null) {
            LocalDate start=cursor.get("next_date") instanceof java.sql.Date d?d.toLocalDate():(LocalDate)cursor.get("next_date");
            LocalDate today=LocalDate.now(java.time.ZoneId.of("Europe/Moscow"));
            if (start.isAfter(today)) start=today;
            // A window whose statement came back in several pages is re-requested one day at a time.
            boolean narrow=MULTI_PAGE_MARKER.equals(cursor.get("error_text"));
            int span=narrow?0:6;
            LocalDate end=start.plusDays(span).isAfter(today)?today:start.plusDays(span);
            var response=bank.call("/open-banking/v1.0/statements",HttpMethod.POST,
                    Map.of("Data",Map.of("Statement",Map.of("accountId",id,"startDateTime",start.toString(),"endDateTime",end.toString()))));
            var data=response.path("Data").path("Statement");
            if (data.isArray()) data=data.path(0);
            statement=data.path("statementId").asText();
            if (!statement.matches("[A-Za-z0-9-]{1,96}")) throw problem("Банк не вернул идентификатор выписки");
            store.jdbc().update("""
                UPDATE paper_invoice_statement_cursor SET statement_id=?,window_end=?,requested_at=NOW(6),error_text=NULL
                WHERE account_id=? AND statement_id IS NULL
                """,statement,end,id);
            return;
        }
        var response=bank.call("/open-banking/v1.0/accounts/"+id+"/statements/"+statement,HttpMethod.GET,null);
        if (response.path("Meta").path("totalPages").asInt(1)>1) {
            LocalDate windowEnd=cursor.get("window_end") instanceof java.sql.Date wd?wd.toLocalDate():(LocalDate)cursor.get("window_end");
            LocalDate windowStart=cursor.get("next_date") instanceof java.sql.Date sd?sd.toLocalDate():(LocalDate)cursor.get("next_date");
            if (windowEnd!=null && windowStart!=null && windowEnd.isAfter(windowStart)) {
                // Previously this stopped reconciliation of the account forever (same request every
                // 5 minutes). Request a one-day window instead; only a single day that still spans
                // several pages needs manual attention.
                store.jdbc().update("""
                    UPDATE paper_invoice_statement_cursor SET statement_id=NULL,error_text=?
                    WHERE account_id=? AND statement_id=?
                    """,MULTI_PAGE_MARKER,id,statement);
                return;
            }
            throw problem("Выписка требует дополнительной сверки страниц");
        }
        boolean ready=false;
        for(var s:response.path("Data").path("Statement")) {
            if (!id.equals(s.path("accountId").asText()) || !statement.equals(s.path("statementId").asText()))
                throw problem("Банк вернул выписку по другому счёту");
            if (!s.path("status").asText().equals("Ready")) continue;
            ready=true;
            for(var t:s.path("Transaction")) {
                var receipt=fromStatement(t,(String)account.get("customer_code"),id);
                if (receipt!=null) settlement.accept(receipt);
            }
        }
        if (!ready) {
            store.jdbc().update("""
                UPDATE paper_invoice_statement_cursor SET statement_id=NULL WHERE account_id=?
                AND requested_at < DATE_SUB(NOW(),INTERVAL 12 HOUR)
                """,id);
            return;
        }
        LocalDate end=cursor.get("window_end") instanceof java.sql.Date d?d.toLocalDate():(LocalDate)cursor.get("window_end");
        LocalDate today=LocalDate.now(java.time.ZoneId.of("Europe/Moscow"));
        LocalDate next=end.isBefore(today)?end.plusDays(1):today.minusDays(2);
        store.jdbc().update("UPDATE paper_invoice_statement_cursor SET next_date=?,statement_id=NULL,error_text=NULL WHERE account_id=? AND statement_id=?",next,id,statement);
    }
    static BankReceipt fromStatement(JsonNode t,String customer,String account) {
        if (!"Credit".equals(t.path("creditDebitIndicator").asText()) || !"Booked".equals(t.path("status").asText())
                || !"RUB".equals(t.path("Amount").path("currency").asText())
                || !"Платежное поручение".equals(t.path("transactionTypeCode").asText())) return null;
        String payment=t.path("paymentId").asText();
        String inn=t.path("DebtorParty").path("inn").asText();
        String purpose=t.path("description").asText();
        if (payment.isBlank() || payment.length()>150 || !validInn(inn) || purpose.length()>2000) return null;
        try {
            long amount=new java.math.BigDecimal(t.path("Amount").path("amount").asText()).movePointRight(2).longValueExact();
            return amount>0?new BankReceipt(customer,account,payment,inn,amount,purpose):null;
        } catch(Exception failure) { return null; }
    }
}

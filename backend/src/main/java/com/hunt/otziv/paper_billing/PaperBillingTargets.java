package com.hunt.otziv.paper_billing;

import static com.hunt.otziv.paper_billing.PaperBillingModels.*;
import com.hunt.otziv.common_billing.api.BankPaperInvoices;
import com.hunt.otziv.manager.api.PaymentReviewOrderAccess;
import com.hunt.otziv.p_products.api.PaperInvoiceOrderData;
import com.hunt.otziv.c_companies.api.PaperInvoiceCompanyData;
import com.hunt.otziv.payments.dto.PaymentRouteChangeTarget;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.PaymentRouteReplacementWorkflow;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class PaperBillingTargets {
    private final JdbcTemplate jdbc;
    private final PaperInvoiceOrderData orders;
    private final PaperInvoiceCompanyData companies;
    private final PaymentReviewOrderAccess access;
    private final BankPaperInvoices common;
    private final PaymentLinkService links;
    private final PaymentRouteReplacementWorkflow routes;
    private final com.hunt.otziv.contractor_payments.api.PaymentReviewTargetAuthorization paymentTargets;
    private final com.hunt.otziv.payments.api.BankPaperPaymentCancellation cancellation;
    public void authorizeCancellation(Target target,Long link) {
        if(target.kind().equals("ORDER")) paymentTargets.requireCanManagePaymentLink(link);
        else paymentTargets.requireCanManageCommonInvoice(target.id());
    }
    public void requireUnpaidForCancellation(Target target) {
        if(target.kind().equals("ORDER")) orders.requireUnpaidForCancellation(target.id());
        else common.requireUnpaidForCancellation(target.id());
    }
    public java.util.List<Long> cancellationManagers(Target target) {
        if(target.kind().equals("COMMON_INVOICE")) return common.staffContext(target.id()).managerIds();
        Long manager=orders.staffContext(target.id()).managerId();return manager==null?java.util.List.of():java.util.List.of(manager);
    }
    public void holdAutomation(Target target,boolean hold) {
        if(target.kind().equals("ORDER")) orders.holdAutomation(target.id(),hold);
        else common.holdAutomation(target.id(),hold);
    }
    public void holdForPaymentReview(Target target) {
        if(target.kind().equals("ORDER")) orders.holdForPaymentReview(target.id());else common.holdForPaymentReview(target.id());
    }
    public void cancelled(Target target,String id,Long link) {
        if(target.kind().equals("ORDER")) {cancellation.cancel(link,id);orders.cancelled(target.id(),false);}
        else common.cancelled(target.id(),id);
    }
    public long previewAmount(Target target) {
        if (target.kind().equals("COMMON_INVOICE"))
            return common.read(target.id()).amountKopecks();
        return links.paperInvoicePreviewAmount(target.id());
    }
    public long company(Target t,Authentication auth) {
        Long company;
        if (t.kind().equals("ORDER")) {
            access.requireOrderAccess(t.id(),auth);
            company=orders.companyId(t.id());
        } else {
            company=common.read(t.id()).companyId();
            access.requireCompanyAccess(company,auth);
        }
        if (company==null) throw problem("Компания не найдена");
        return company;
    }
    public Map<String,Object> companyInfo(long company) {
        return companies.read(company);
    }
    public void authorizeHistorical(long company,Authentication auth) {access.requireCompanyAccess(company,auth);}
    public void lockDelivery(Target target) {
        if(target.kind().equals("ORDER")) orders.lockForDelivery(target.id());
        else common.lockForDelivery(target.id());
    }
    public void delivered(Target target,String document) {
        if(target.kind().equals("ORDER")) orders.markDelivered(target.id());
        else common.delivered(target.id(),document);
    }
    public void configureCommon(Target t,Authentication auth) {
        if (t.kind().equals("COMMON_INVOICE")) {
            paymentTargets.requireCanManageCommonInvoice(t.id());
            common.configure(t.id(),auth);
        }
    }
    public String deliveryClient(Target t,String companyClient) {
        if (!t.kind().equals("COMMON_INVOICE")) return clean(companyClient);
        return common.deliveryClient(t.id(),clean(companyClient));
    }
    @Transactional
    public Prepared prepare(Target t,Authentication auth) {
        if (t.kind().equals("ORDER")) {
            orders.lockCompletedUnpaid(t.id());
            long company=company(t,auth);
            var context=links.paymentRouteChangeContextAuthorized(t.id(),auth);
            paymentTargets.requireCanManagePaymentLink(context.paymentLinkId());
            if (!context.configuredMode().equals("OWNER_PAPER_INVOICE")) {
                if (!context.canChange()) throw problem(context.blockReason());
                routes.replacePaymentRouteAuthorized(t.id(),context.paymentLinkId(),PaymentRouteChangeTarget.OWNER_PAPER_INVOICE,true,null,auth);
            }
            var prepared=links.createForOrderAuthorized(t.id(),auth);
            if (!"OWNER_PAPER_INVOICE".equals(prepared.paymentMethod())) throw problem("Бумажный режим не подготовлен");
            Long link=jdbc.queryForObject("""
                SELECT id FROM payment_links WHERE order_id=? AND payment_method='OWNER_PAPER_INVOICE'
                AND status IN ('CREATED','WAITING_MANUAL_PAYMENT') ORDER BY id DESC LIMIT 1
                """,Long.class,t.id());
            return new Prepared(company,link,prepared.amountKopecks());
        }
        var invoice=common.prepare(t.id());
        long company=company(t,auth);
        return new Prepared(company,null,invoice.amountKopecks());
    }
    public record Prepared(long companyId,Long linkId,long amountKopecks) {}
}

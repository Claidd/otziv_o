package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.common_billing.service.CommonInvoicePublicationBlockerService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagerControlInvoiceRepairWorkflowTest {
    private final CommonInvoiceRepository invoices = mock(CommonInvoiceRepository.class);
    private final CommonInvoiceOrderRepository orders = mock(CommonInvoiceOrderRepository.class);
    private final CommonInvoicePublicationBlockerService blockers = mock(CommonInvoicePublicationBlockerService.class);
    private final CommonBillingService billing = mock(CommonBillingService.class);
    private final ManagerControlInvoiceDiagnostics diagnostics = mock(ManagerControlInvoiceDiagnostics.class);
    private final LocalTransactionManager transactions = new LocalTransactionManager();

    @Test
    void realSpringBoundarySuspendsAndResumesTheControlTransactionAroundConfirmedSend() {
        CommonInvoice invoice = invoice(CommonInvoiceStatus.READY);
        when(invoices.findByIdWithAccount(88L)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(invoice);
        });
        CommonInvoiceDetailsResponse ready = details(CommonInvoiceStatus.READY, "");
        CommonInvoiceDetailsResponse sent = details(CommonInvoiceStatus.INVOICED, "");
        when(billing.invoice(88L)).thenReturn(ready);
        when(billing.sendInvoice(88L, true)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return sent;
        });
        ManagerControlInvoiceRepairWorkflow workflow = proxiedWorkflow();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(workflow.repair(88L).eventDescription().contains("отправлен"));
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        verify(billing).sendInvoice(88L, true);
    }

    @Test
    void failedExternalOutcomeCannotReturnAResolvedOutcomeAndStillResumesOuterTransaction() {
        when(invoices.findByIdWithAccount(88L)).thenReturn(Optional.of(invoice(CommonInvoiceStatus.READY)));
        CommonInvoiceDetailsResponse ready = details(CommonInvoiceStatus.READY, "");
        CommonInvoiceDetailsResponse failed = details(CommonInvoiceStatus.NEEDS_ATTENTION, "fixture delivery failed");
        when(billing.invoice(88L)).thenReturn(ready);
        when(billing.sendInvoice(88L, true)).thenReturn(failed);
        ManagerControlInvoiceRepairWorkflow workflow = proxiedWorkflow();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> workflow.repair(88L));
            assertEquals(409, error.getStatusCode().value());
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void publicationBlockerStopsWorkflowBeforeBillingOrSending() {
        when(invoices.findByIdWithAccount(88L)).thenReturn(Optional.of(invoice(CommonInvoiceStatus.COLLECTING)));
        when(orders.findByInvoiceIdWithOrders(88L)).thenReturn(java.util.List.of());
        when(blockers.hasOverdueBlockers(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        assertThrows(ResponseStatusException.class, () -> proxiedWorkflow().repair(88L));
        verify(billing, never()).invoice(88L);
        verify(billing, never()).sendInvoice(88L, true);
    }

    private ManagerControlInvoiceRepairWorkflow proxiedWorkflow() {
        ManagerControlInvoiceRepairWorkflow target = new ManagerControlInvoiceRepairWorkflow(
                invoices, orders, blockers, billing, diagnostics);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (ManagerControlInvoiceRepairWorkflow) factory.getProxy();
    }

    private CommonInvoice invoice(CommonInvoiceStatus status) {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(88L);
        invoice.setStatus(status);
        return invoice;
    }

    private CommonInvoiceDetailsResponse details(CommonInvoiceStatus status, String error) {
        CommonInvoiceSummaryResponse summary = mock(CommonInvoiceSummaryResponse.class);
        when(summary.status()).thenReturn(status.name());
        when(summary.lastError()).thenReturn(error);
        CommonInvoiceDetailsResponse details = mock(CommonInvoiceDetailsResponse.class);
        when(details.summary()).thenReturn(summary);
        return details;
    }

    /** A real Spring transaction lifecycle, with no database or network effects. */
    private static final class LocalTransactionManager extends AbstractPlatformTransactionManager {
        private final ThreadLocal<Object> active = new ThreadLocal<>();

        @Override protected Object doGetTransaction() { return active.get() == null ? new Object() : active.get(); }
        @Override protected boolean isExistingTransaction(Object transaction) { return active.get() == transaction; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { active.set(transaction); }
        @Override protected Object doSuspend(Object transaction) { active.remove(); return transaction; }
        @Override protected void doResume(Object transaction, Object suspendedResources) { active.set(suspendedResources); }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
        @Override protected void doCleanupAfterCompletion(Object transaction) { active.remove(); }
    }
}

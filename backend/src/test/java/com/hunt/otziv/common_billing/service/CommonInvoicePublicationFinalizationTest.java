package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.*;
import com.hunt.otziv.common_billing.repository.*;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual owner orchestration with explicit settlement/locking collaborator boundary.
 * Existing settlement tests retain responsibility for money/accrual calculations. */
class CommonInvoicePublicationFinalizationTest {
    CommonInvoiceMembershipWorkflow owner;
    CommonInvoiceSettlementService settlement;
    CommonInvoiceOrderRepository positions;
    CommonInvoiceRepository invoices;
    CommonInvoice invoice;
    CommonInvoiceOrder item;
    List<CommonInvoiceOrder> items;

    @BeforeEach void setup() throws Exception {
        settlement=mock(CommonInvoiceSettlementService.class);positions=mock(CommonInvoiceOrderRepository.class);invoices=mock(CommonInvoiceRepository.class);
        var supplied=Map.<Class<?>,Object>of(CommonInvoiceSettlementService.class,settlement,CommonInvoiceOrderRepository.class,positions,CommonInvoiceRepository.class,invoices);
        var constructor=Arrays.stream(CommonInvoiceMembershipWorkflow.class.getConstructors()).max(Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount)).orElseThrow();
        owner=(CommonInvoiceMembershipWorkflow)constructor.newInstance(Arrays.stream(constructor.getParameterTypes()).map(type->supplied.containsKey(type)?supplied.get(type):mock(type)).toArray());
        invoice=new CommonInvoice();invoice.setId(10L);invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        var order=new Order();order.setId(7L);var status=new OrderStatus();status.setTitle("Ожидает общего счета");order.setStatus(status);
        item=new CommonInvoiceOrder();item.setId(70L);item.setInvoice(invoice);item.setOrder(order);item.setActiveMembership(true);item.setReady(true);items=List.of(item);
        var binding=mock(CommonInvoiceOrderRepository.CurrentOrderInvoiceView.class);when(binding.getInvoiceId()).thenReturn(10L);
        when(positions.findCurrentInvoiceBindingsByOrderIds(List.of(7L))).thenReturn(List.of(binding));
        when(positions.findByInvoiceIdWithOrders(10L)).thenReturn(items);
        when(settlement.lockedInvoice(10L)).thenReturn(Optional.of(invoice));
        when(settlement.writeTransaction(any())).thenAnswer(call -> call.getArgument(0,Supplier.class).get());
        when(settlement.allOrdersReady(items)).thenReturn(true);
        when(settlement.statusTitle(any(Order.class))).thenAnswer(call -> call.getArgument(0,Order.class).getStatus().getTitle());
        when(settlement.areInvoiceItemsReady(items)).thenReturn(true);
        when(settlement.immediateClientMessagesEnabled()).thenReturn(true);
    }

    @AfterEach void clearSynchronization(){if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();}

    @Test void recoveryAfterLostCallbackFinalizesCurrentCollectingInvoiceOnceAndReplayDoesNotSendAgain() {
        assertThat(owner.finalizePublishedInvoiceForOrder(7)).isTrue();
        assertThat(invoice.getStatus()).isEqualTo(CommonInvoiceStatus.READY);
        var ordered=inOrder(settlement,positions,invoices);
        ordered.verify(positions).findCurrentInvoiceBindingsByOrderIds(List.of(7L));
        ordered.verify(settlement).writeTransaction(any());ordered.verify(settlement).lockedInvoice(10L);
        ordered.verify(positions).findByInvoiceIdWithOrders(10L);
        ordered.verify(settlement).allOrdersReady(items);ordered.verify(settlement).recalculateInvoice(invoice,items);
        ordered.verify(settlement).applyCommonInvoicePrepaymentIfReady(invoice,items);
        ordered.verify(positions).findByInvoiceIdWithOrders(10L);ordered.verify(settlement).areInvoiceItemsReady(items);
        ordered.verify(invoices).save(invoice);ordered.verify(settlement).markInvoiceOrdersPublished(items);
        assertThat(owner.finalizePublishedInvoiceForOrder(7)).isTrue();
        verify(settlement,times(1)).sendInvoiceAfterCommit(10L,false);
        verify(invoices,times(1)).save(invoice);
    }

    @Test void oldFastCallbackAndDurableRecoveryUseSameIdempotentFinalization() {
        TransactionSynchronizationManager.initSynchronization();
        assertThat(owner.deferReadyCommonInvoiceFinalizationUntilAfterCommit(invoice)).isTrue();
        var callbacks=TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        assertThat(invoice.getStatus()).isEqualTo(CommonInvoiceStatus.COLLECTING);
        owner.finalizePublishedInvoiceForOrder(7); // process restart did not run the old callback
        callbacks.forEach(TransactionSynchronization::afterCommit);
        verify(settlement,times(1)).sendInvoiceAfterCommit(10L,false);
        assertThat(invoice.getStatus()).isEqualTo(CommonInvoiceStatus.READY);
    }

    @ParameterizedTest
    @EnumSource(value=CommonInvoiceStatus.class,names={"READY","INVOICED","PARTIALLY_PAID","REMINDER","NEEDS_ATTENTION","PAID","UNPAID","BAN","ARCHIVED","DISABLED"})
    void terminalAttentionOrPreviouslyPreparedInvoicesAreNeverResurrected(CommonInvoiceStatus status) {
        invoice.setStatus(status);
        assertThat(owner.finalizePublishedInvoiceForOrder(7)).isTrue();
        assertThat(invoice.getStatus()).isEqualTo(status);
        verify(settlement,never()).recalculateInvoice(any(),anyList());
        verify(settlement,never()).applyCommonInvoicePrepaymentIfReady(any(),anyList());
        verify(settlement,never()).sendInvoiceAfterCommit(anyLong(),anyBoolean());
        verifyNoInteractions(invoices);
    }

    @Test void changedMembershipCannotFinalizeFormerInvoice() {
        item.setActiveMembership(false);
        assertThat(owner.finalizePublishedInvoiceForOrder(7)).isFalse();
        verify(settlement,never()).allOrdersReady(anyList());verifyNoInteractions(invoices);
    }

    @Test void recoveryBlockIsRetryableAndCurrentReadyEvidenceIsRechecked() {
        item.setReady(false);when(settlement.allOrdersReady(items)).thenReturn(false);
        assertThat(owner.finalizePublishedInvoiceForOrder(7)).isFalse();
        verify(settlement,never()).recalculateInvoice(any(),anyList());
        item.setReady(true);when(settlement.allOrdersReady(items)).thenReturn(true);
        assertThat(owner.finalizePublishedInvoiceForOrder(7)).isTrue();
        verify(settlement,times(2)).allOrdersReady(items);
    }

    @ParameterizedTest
    @EnumSource(value=CommonInvoiceStatus.class,names={"PAID","PARTIALLY_PAID"})
    void existingPrepaymentOwnerCompletesBeforeAnyRegularInvoicePromotion(CommonInvoiceStatus settled) {
        when(settlement.applyCommonInvoicePrepaymentIfReady(invoice,items)).thenAnswer(call->{invoice.setStatus(settled);return true;});
        assertThat(owner.finalizePublishedInvoiceForOrder(7)).isTrue();
        assertThat(invoice.getStatus()).isEqualTo(settled);
        verify(settlement,never()).areInvoiceItemsReady(anyList());
        verify(settlement,never()).sendInvoiceAfterCommit(anyLong(),anyBoolean());
        assertThat(owner.finalizePublishedInvoiceForOrder(7)).isTrue();
        verify(settlement,times(1)).applyCommonInvoicePrepaymentIfReady(invoice,items);
    }

    @Test void disabledImmediateSendStillProducesDurableReadyInvoiceWithExistingDisabledPolicy() {
        when(settlement.immediateClientMessagesEnabled()).thenReturn(false);
        assertThat(owner.finalizePublishedInvoiceForOrder(7)).isTrue();
        assertThat(invoice.getStatus()).isEqualTo(CommonInvoiceStatus.READY);
        assertThat(invoice.getLastError()).startsWith("auto_send_disabled:");
        verify(settlement,never()).sendInvoiceAfterCommit(anyLong(),anyBoolean());
    }

    @Test void laterManualFinancialClosureOfMemberIsRespected() {
        for(String status:List.of("Не оплачено","Бан","Архив")){
            item.getOrder().getStatus().setTitle(status);
            assertThat(owner.finalizePublishedInvoiceForOrder(7)).isTrue();
            assertThat(invoice.getStatus()).isEqualTo(CommonInvoiceStatus.COLLECTING);
        }
        verify(settlement,never()).allOrdersReady(anyList());verifyNoInteractions(invoices);
    }
}

package com.hunt.otziv.common_billing.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.hunt.otziv.common_billing.model.*;
import com.hunt.otziv.common_billing.repository.*;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommonInvoiceSilenceArchiveTest {
    CommonInvoiceMembershipWorkflow workflow;
    CommonInvoiceSettlementService settlement;
    CommonInvoiceOrderRepository positions;
    CommonInvoiceRepository invoices;
    CommonInvoice invoice;
    CommonInvoiceOrder item;
    @BeforeEach void setup() throws Exception {
        settlement=mock(CommonInvoiceSettlementService.class);positions=mock(CommonInvoiceOrderRepository.class);invoices=mock(CommonInvoiceRepository.class);
        var supplied=Map.<Class<?>,Object>of(CommonInvoiceSettlementService.class,settlement,CommonInvoiceOrderRepository.class,positions,CommonInvoiceRepository.class,invoices);
        var constructor=Arrays.stream(CommonInvoiceMembershipWorkflow.class.getConstructors()).max(Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount)).orElseThrow();
        workflow=(CommonInvoiceMembershipWorkflow)constructor.newInstance(Arrays.stream(constructor.getParameterTypes()).map(type->supplied.containsKey(type)?supplied.get(type):mock(type)).toArray());
        invoice=new CommonInvoice();invoice.setId(10L);invoice.setStatus(CommonInvoiceStatus.COLLECTING);invoice.setAmountKopecks(450000);
        var order=new Order();order.setId(7L);order.setStatus(OrderStatus.builder().title("На проверке").build());
        item=new CommonInvoiceOrder();item.setId(70L);item.setInvoice(invoice);item.setOrder(order);item.setActiveMembership(true);item.setAmountKopecks(150000);
        when(positions.findByOrder_IdAndActiveMembershipTrue(7L)).thenReturn(Optional.of(item));
        when(settlement.lockedInvoiceAfterOrderPrelude(10L)).thenReturn(Optional.of(invoice));
        when(settlement.statusTitle(any())).thenAnswer(i->i.getArgument(0,Order.class).getStatus().getTitle());
        when(settlement.normalize(any())).thenAnswer(i->i.getArgument(0)==null?"":i.getArgument(0,String.class).trim());
    }
    @Test void lastCurrentPositionArchivesInvoiceWithoutDeletingPositionOrChangingPaymentHistory() {
        when(positions.findByInvoiceIdWithOrders(10L)).thenReturn(List.of());
        var evidence=workflow.archiveReviewMembershipAfterReminders(7L);
        assertThat(invoice.getStatus()).isEqualTo(CommonInvoiceStatus.ARCHIVED);
        assertThat(invoice.getAmountKopecks()).isEqualTo(450000);assertThat(invoice.getPaidKopecks()).isZero();
        assertThat(item.isActiveMembership()).isFalse();assertThat(item.getSilenceArchivedAt()).isNotNull();
        assertThat(item.getAmountKopecks()).isEqualTo(150000);assertThat(item.getArchiveSourceOrderStatusTitle()).isEqualTo("На проверке");
        assertThat(evidence).containsEntry("invoiceAmountKopecks",450000L);
        verify(positions,never()).deleteByOrderId(anyLong());verify(invoices).save(invoice);
    }
    @Test void otherCurrentPositionKeepsInvoiceAndRecalculatesOnlyCurrentComposition() {
        var remaining=new CommonInvoiceOrder();remaining.setId(71L);remaining.setAmountKopecks(300000);
        when(positions.findByInvoiceIdWithOrders(10L)).thenReturn(List.of(remaining));
        workflow.archiveReviewMembershipAfterReminders(7L);
        assertThat(invoice.getStatus()).isEqualTo(CommonInvoiceStatus.COLLECTING);
        verify(settlement).recalculateInvoice(invoice,List.of(remaining));
        verify(positions,never()).deleteByOrderId(anyLong());
    }
    @ParameterizedTest @ValueSource(strings={"paid","sent","reported","positionPaid","paymentUrl","invoiceChanged"})
    void financialOrChangedStateIsUntouched(String condition) {
        switch(condition){
            case "paid"->invoice.setPaidKopecks(150000);
            case "sent"->invoice.setSentAt(java.time.LocalDateTime.now());
            case "reported"->invoice.setClientReportedAt(java.time.LocalDateTime.now());
            case "positionPaid"->item.setPaid(true);
            case "paymentUrl"->invoice.setPaymentUrl("https://example.test/payment");
            case "invoiceChanged"->invoice.setStatus(CommonInvoiceStatus.INVOICED);
        }
        assertThatThrownBy(()->workflow.archiveReviewMembershipAfterReminders(7L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(item.isActiveMembership()).isTrue();assertThat(item.getSilenceArchivedAt()).isNull();
        verify(positions,never()).save(any());verify(invoices,never()).save(any());
    }
}

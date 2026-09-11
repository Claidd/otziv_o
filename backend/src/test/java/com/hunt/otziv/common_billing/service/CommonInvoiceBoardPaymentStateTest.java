package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.*;
import com.hunt.otziv.common_billing.repository.*;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CommonInvoiceBoardPaymentStateTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void batchPreservesMoneyAndCommandPathStillReadsCurrentFacts(boolean attributed) throws Exception {
        var refs = mock(CommonInvoicePaymentRefRepository.class);
        var attribution = mock(CommonManualPaymentAttributionCoordinator.class);
        var supplied = Map.<Class<?>, Object>of(CommonInvoicePaymentRefRepository.class, refs,
                CommonManualPaymentAttributionCoordinator.class, attribution);
        var constructor = Arrays.stream(CommonInvoiceSettlementService.class.getConstructors())
                .max(Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount)).orElseThrow();
        var service = (CommonInvoiceSettlementService) constructor.newInstance(Arrays.stream(constructor.getParameterTypes())
                .map(type -> supplied.containsKey(type) ? supplied.get(type) : mock(type)).toArray());
        var invoice = invoice();
        var items = items(invoice);
        var amounts = Map.of(2L, new BigDecimal("100.00"));
        var recovery = Map.of(1L, false, 2L, false);
        service.refreshInvoiceAmounts(invoice, items, amounts, recovery,
                new CommonInvoiceSettlementService.BoardPaymentState(attributed, 500));
        assertThat(invoice.getAmountKopecks()).isEqualTo(12000);
        assertThat(invoice.getPaidKopecks()).isEqualTo(attributed ? 7000 : 2500);
        verifyNoInteractions(refs, attribution);

        when(refs.sumAmountKopecksByInvoiceIdAndStatus(10L, "PREPAID")).thenReturn(500L);
        when(attribution.hasRecordedAttribution(10L)).thenReturn(attributed);
        var current = invoice();
        service.refreshInvoiceAmounts(current, items(current), amounts, recovery);
        assertThat(current.getAmountKopecks()).isEqualTo(invoice.getAmountKopecks());
        assertThat(current.getPaidKopecks()).isEqualTo(invoice.getPaidKopecks());
        assertThat(current.getStatus()).isEqualTo(invoice.getStatus());
        verify(refs).sumAmountKopecksByInvoiceIdAndStatus(10L, "PREPAID");
        verify(attribution).hasRecordedAttribution(10L);
    }

    private CommonInvoice invoice() {
        var invoice = new CommonInvoice(); invoice.setId(10L); invoice.setStatus(CommonInvoiceStatus.INVOICED);
        invoice.setAmountKopecks(12000); invoice.setPaidKopecks(7000); return invoice;
    }
    private List<CommonInvoiceOrder> items(CommonInvoice invoice) {
        var items = new ArrayList<CommonInvoiceOrder>();
        for (long id : List.of(1L, 2L)) {
            var order = new Order(); order.setId(id); var status = new OrderStatus(); status.setTitle("Новый"); order.setStatus(status);
            var item = new CommonInvoiceOrder(); item.setId(id); item.setInvoice(invoice); item.setOrder(order);
            item.setAmountKopecks(id == 1 ? 2000 : 10000); item.setPaid(id == 1); items.add(item);
        }
        return items;
    }
}

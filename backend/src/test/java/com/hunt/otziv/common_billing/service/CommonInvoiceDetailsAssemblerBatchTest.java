package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.dto.CommonInvoiceNextCycleResponse;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository.CurrentOrderInvoiceView;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequest;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.data.jpa.repository.Query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CommonInvoiceDetailsAssemblerBatchTest {
    private CommonInvoiceOrderRepository memberships;
    private NextOrderRequestRepository requests;
    private CommonInvoiceDetailsAssembler assembler;

    @BeforeEach
    void setUp() {
        memberships = mock(CommonInvoiceOrderRepository.class);
        requests = mock(NextOrderRequestRepository.class);
        CommonInvoiceSettlementService settlement = mock(CommonInvoiceSettlementService.class);
        when(settlement.statusTitle(any())).thenReturn("В работе");
        assembler = new CommonInvoiceDetailsAssembler(null, settlement, memberships, null,
                null, requests, null, null, null);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 50})
    void successorCountDoesNotIncreaseRepositoryCalls(int count) {
        List<CommonInvoiceOrder> items = IntStream.rangeClosed(1, count)
                .mapToObj(index -> item((long) index)).toList();
        List<NextOrderRequest> next = IntStream.rangeClosed(1, count)
                .mapToObj(index -> request((long) index, 1000L + index)).toList();
        List<Long> sourceIds = IntStream.rangeClosed(1, count).mapToObj(index -> (long) index).toList();
        List<Long> createdIds = IntStream.rangeClosed(1, count).mapToObj(index -> 1000L + index).toList();
        List<CurrentOrderInvoiceView> bindings = IntStream.rangeClosed(1, count)
                .mapToObj(index -> binding(1000L + index, 2000L + index, CommonInvoiceStatus.READY)).toList();
        when(requests.findBySourceOrderIdsWithCreatedOrder(sourceIds)).thenReturn(next);
        when(memberships.findCurrentInvoiceBindingsByOrderIds(createdIds)).thenReturn(bindings);

        List<CommonInvoiceNextCycleResponse> result = assembler.toNextCycleOrders(items);

        assertThat(result).hasSize(count);
        assertThat(result).extracting(CommonInvoiceNextCycleResponse::orderId).containsExactlyElementsOf(createdIds);
        assertThat(result).extracting(CommonInvoiceNextCycleResponse::invoiceId)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, count).mapToObj(index -> 2000L + index).toList());
        verify(requests).findBySourceOrderIdsWithCreatedOrder(sourceIds);
        verify(memberships).findCurrentInvoiceBindingsByOrderIds(createdIds);
        verifyNoMoreInteractions(requests, memberships);
    }

    @Test
    void preservesRequestOrderAndDuplicatesWhileMissingMembershipStaysNull() {
        List<NextOrderRequest> next = new ArrayList<>(List.of(
                request(3L, 30L), request(1L, 10L), request(2L, 30L), request(4L, 40L)));
        NextOrderRequest noCreatedOrder = new NextOrderRequest();
        noCreatedOrder.setSourceOrder(order(5L));
        next.add(noCreatedOrder);
        next.add(request(6L, null));
        when(requests.findBySourceOrderIdsWithCreatedOrder(List.of(1L, 2L, 3L, 4L, 5L, 6L))).thenReturn(next);
        // The batch may return rows in any order. A missing binding is valid for a standalone successor.
        when(memberships.findCurrentInvoiceBindingsByOrderIds(List.of(30L, 10L, 40L)))
                .thenReturn(List.of(binding(10L, 110L, CommonInvoiceStatus.PAID),
                        binding(30L, 130L, CommonInvoiceStatus.COLLECTING)));

        List<CommonInvoiceNextCycleResponse> result = assembler.toNextCycleOrders(
                IntStream.rangeClosed(1, 6).mapToObj(index -> item((long) index)).toList());

        assertThat(result).extracting(CommonInvoiceNextCycleResponse::sourceOrderId).containsExactly(3L, 1L, 2L, 4L, 6L);
        assertThat(result).extracting(CommonInvoiceNextCycleResponse::orderId).containsExactly(30L, 10L, 30L, 40L, null);
        assertThat(result).extracting(CommonInvoiceNextCycleResponse::invoiceId).containsExactly(130L, 110L, 130L, null, null);
        assertThat(result).extracting(CommonInvoiceNextCycleResponse::invoiceStatus)
                .containsExactly("COLLECTING", "PAID", "COLLECTING", null, null);
        verify(memberships).findCurrentInvoiceBindingsByOrderIds(List.of(30L, 10L, 40L));
        verify(memberships, never()).findByOrderIdWithInvoice(any());
    }

    @Test
    void ambiguousActiveMembershipStillFailsInsteadOfChoosingAnArbitraryInvoice() {
        when(requests.findBySourceOrderIdsWithCreatedOrder(List.of(1L))).thenReturn(List.of(request(1L, 10L)));
        when(memberships.findCurrentInvoiceBindingsByOrderIds(List.of(10L)))
                .thenReturn(List.of(binding(10L, 110L, CommonInvoiceStatus.PAID),
                        binding(10L, 120L, CommonInvoiceStatus.COLLECTING)));

        assertThatThrownBy(() -> assembler.toNextCycleOrders(List.of(item(1L))))
                .isInstanceOf(IncorrectResultSizeDataAccessException.class);
    }

    @Test
    void emptyOrUnpersistedSuccessorsDoNotCreateAnEmptyInQuery() {
        assertThat(assembler.toNextCycleOrders(List.of())).isEmpty();
        verifyNoInteractions(requests, memberships);
        when(requests.findBySourceOrderIdsWithCreatedOrder(List.of(1L))).thenReturn(List.of(request(1L, null)));
        List<CommonInvoiceNextCycleResponse> result = assembler.toNextCycleOrders(List.of(item(1L)));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().invoiceId()).isNull();
        verifyNoInteractions(memberships);
    }

    @Test
    void batchUsesOnlyCurrentMembershipRegardlessOfHistoricalInvoiceStatus() throws Exception {
        String batch = CommonInvoiceOrderRepository.class
                .getMethod("findCurrentInvoiceBindingsByOrderIds", Collection.class)
                .getAnnotation(Query.class).value().replaceAll("\\s+", " ");
        String singular = CommonInvoiceOrderRepository.class.getMethod("findByOrderIdWithInvoice", Long.class)
                .getAnnotation(Query.class).value().replaceAll("\\s+", " ");
        assertThat(batch).contains("item.activeMembership = TRUE", "item.order.id IN :orderIds",
                "JOIN item.invoice invoice", "JOIN invoice.account account");
        assertThat(singular).contains("item.activeMembership = TRUE");
        assertThat(batch).doesNotContain("invoice.status IN", "invoice.status =", "invoice.status <>");
    }

    private static CommonInvoiceOrder item(Long orderId) {
        CommonInvoiceOrder item = new CommonInvoiceOrder();
        item.setOrder(order(orderId));
        return item;
    }

    private static NextOrderRequest request(Long sourceId, Long createdId) {
        NextOrderRequest request = new NextOrderRequest();
        request.setSourceOrder(order(sourceId));
        request.setCreatedOrder(order(createdId));
        return request;
    }

    private static Order order(Long id) {
        Order order = new Order();
        order.setId(id);
        return order;
    }

    private static CurrentOrderInvoiceView binding(Long orderId, Long invoiceId, CommonInvoiceStatus status) {
        return new CurrentOrderInvoiceView() {
            @Override public Long getOrderId() { return orderId; }
            @Override public Long getInvoiceId() { return invoiceId; }
            @Override public CommonInvoiceStatus getInvoiceStatus() { return status; }
        };
    }
}

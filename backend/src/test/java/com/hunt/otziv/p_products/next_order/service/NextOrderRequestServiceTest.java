package com.hunt.otziv.p_products.next_order;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.common_billing.service.CommonBillingNextOrderFailureMarker;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.u_users.model.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class NextOrderRequestServiceTest {

    @Mock
    private NextOrderRequestRepository requestRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CompanyService companyService;

    @Mock
    private CompanyStatusService companyStatusService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private NextOrderFailureNotifier nextOrderFailureNotifier;

    @Mock
    private CommonBillingNextOrderFailureMarker commonBillingNextOrderFailureMarker;

    @Test
    void openForPaidOrderChecksActiveOrdersOnlyInsideSameFilial() {
        NextOrderRequestService service = service();
        Company company = company(10L);
        Filial filial = filial(20L);
        Worker worker = worker(70L);
        Order sourceOrder = order(30L, company, filial, worker);

        when(orderRepository.existsActiveOrderByCompanyIdAndFilialId(eq(10L), eq(20L), eq(70L), eq(30L), anySet()))
                .thenReturn(false);
        when(requestRepository.findBySourceOrderId(30L)).thenReturn(Optional.empty());
        when(requestRepository.findOpenByCompanyIdAndFilialId(eq(10L), eq(20L), eq(70L), anySet(), any(Pageable.class)))
                .thenReturn(List.of());
        when(companyService.getCompaniesById(10L)).thenReturn(null);
        when(requestRepository.save(any(NextOrderRequest.class))).thenAnswer(invocation -> {
            NextOrderRequest request = invocation.getArgument(0);
            request.setId(40L);
            return request;
        });

        service.openForPaidOrder(sourceOrder);

        ArgumentCaptor<NextOrderRequest> requestCaptor = ArgumentCaptor.forClass(NextOrderRequest.class);
        verify(requestRepository).save(requestCaptor.capture());
        assertSame(company, requestCaptor.getValue().getCompany());
        assertSame(filial, requestCaptor.getValue().getFilial());
        assertSame(sourceOrder, requestCaptor.getValue().getSourceOrder());
        assertEquals(NextOrderRequestStatus.PENDING, requestCaptor.getValue().getStatus());
        verify(eventPublisher).publishEvent(new NextOrderRequestedEvent(40L));
        verify(orderRepository).existsActiveOrderByCompanyIdAndFilialId(eq(10L), eq(20L), eq(70L), eq(30L), anySet());
    }

    @Test
    void openForPaidOrderDoesNotCreateRequestWhenSameWorkerHasActiveOrderInSameFilial() {
        NextOrderRequestService service = service();
        Company company = company(10L);
        Filial filial = filial(20L);
        Worker worker = worker(70L);
        Order sourceOrder = order(30L, company, filial, worker);

        when(orderRepository.existsActiveOrderByCompanyIdAndFilialId(eq(10L), eq(20L), eq(70L), eq(30L), anySet()))
                .thenReturn(true);
        when(companyService.getCompaniesById(10L)).thenReturn(null);

        Optional<NextOrderRequest> result = service.openForPaidOrder(sourceOrder);

        assertTrue(result.isEmpty());
        verify(requestRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void cancelForDeletedCreatedOrderClosesCreatedRequestAndClearsCreatedOrder() {
        NextOrderRequestService service = service();
        Company company = company(10L);
        Filial filial = filial(20L);
        Order sourceOrder = order(30L, company, filial);
        Order createdOrder = order(40L, company, filial);
        NextOrderRequest request = NextOrderRequest.builder()
                .company(company)
                .filial(filial)
                .sourceOrder(sourceOrder)
                .createdOrder(createdOrder)
                .status(NextOrderRequestStatus.CREATED)
                .build();
        request.setId(50L);

        when(requestRepository.findByCreatedOrder_Id(40L)).thenReturn(List.of(request));

        boolean result = service.cancelForDeletedCreatedOrder(createdOrder);

        assertTrue(result);
        assertEquals(NextOrderRequestStatus.CANCELED, request.getStatus());
        assertNull(request.getCreatedOrder());
        assertEquals("Автосозданный следующий заказ 40 удален", request.getErrorMessage());
        verify(requestRepository).save(request);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void markFailedMarksLinkedCommonInvoiceAsNeedsAttention() {
        NextOrderRequestService service = service();
        Company company = company(10L);
        Filial filial = filial(20L);
        Order sourceOrder = order(30L, company, filial);
        NextOrderRequest request = NextOrderRequest.builder()
                .company(company)
                .filial(filial)
                .sourceOrder(sourceOrder)
                .status(NextOrderRequestStatus.PENDING)
                .build();
        request.setId(50L);
        RuntimeException cause = new RuntimeException("deadlock");

        when(requestRepository.findById(50L)).thenReturn(Optional.of(request));

        service.markFailed(50L, cause);

        assertEquals(NextOrderRequestStatus.FAILED, request.getStatus());
        verify(commonBillingNextOrderFailureMarker).markAttentionForSourceOrder(sourceOrder, 50L, cause);
        verify(requestRepository).save(request);
    }

    private NextOrderRequestService service() {
        return new NextOrderRequestService(
                requestRepository,
                orderRepository,
                companyService,
                companyStatusService,
                eventPublisher,
                nextOrderFailureNotifier,
                commonBillingNextOrderFailureMarker
        );
    }

    private Company company(Long id) {
        Company company = new Company();
        company.setId(id);
        return company;
    }

    private Filial filial(Long id) {
        Filial filial = new Filial();
        filial.setId(id);
        return filial;
    }

    private Order order(Long id, Company company, Filial filial) {
        return order(id, company, filial, null);
    }

    private Order order(Long id, Company company, Filial filial, Worker worker) {
        Order order = new Order();
        order.setId(id);
        order.setCompany(company);
        order.setFilial(filial);
        order.setWorker(worker);
        return order;
    }

    private Worker worker(Long id) {
        Worker worker = new Worker();
        worker.setId(id);
        return worker;
    }
}

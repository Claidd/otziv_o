package com.hunt.otziv.p_products.next_order;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void openForPaidOrderChecksActiveOrdersOnlyInsideSameFilial() {
        NextOrderRequestService service = service();
        Company company = company(10L);
        Filial filial = filial(20L);
        Order sourceOrder = order(30L, company, filial);

        when(orderRepository.existsActiveOrderByCompanyIdAndFilialId(eq(10L), eq(20L), eq(30L), anySet()))
                .thenReturn(false);
        when(requestRepository.findBySourceOrderId(30L)).thenReturn(Optional.empty());
        when(requestRepository.findOpenByCompanyIdAndFilialId(eq(10L), eq(20L), anySet(), any(Pageable.class)))
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
        verify(orderRepository).existsActiveOrderByCompanyIdAndFilialId(eq(10L), eq(20L), eq(30L), anySet());
    }

    private NextOrderRequestService service() {
        return new NextOrderRequestService(
                requestRepository,
                orderRepository,
                companyService,
                companyStatusService,
                eventPublisher
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
        Order order = new Order();
        order.setId(id);
        order.setCompany(company);
        order.setFilial(filial);
        return order;
    }
}

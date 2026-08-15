package com.hunt.otziv.p_products.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.repository.OrderDetailsRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderDetailsServiceImplTest {

    @Mock
    private OrderDetailsRepository orderDetailsRepository;
    @Mock
    private OrderRepository orderRepository;

    @Test
    void missingLiveReviewCheckDoesNotPoisonAnArchiveRestoreTransaction() throws Exception {
        Transactional transaction = OrderDetailsServiceImpl.class
                .getMethod("getOrderDetailForReviewCheckById", UUID.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.noRollbackFor()).contains(UsernameNotFoundException.class);
    }

    @Test
    void reviewApprovalBatchMapsEmptyDetailsWithoutPerDetailReloads() {
        Order order = Order.builder()
                .id(101L)
                .amount(2)
                .counter(0)
                .company(Company.builder()
                        .id(201L)
                        .title("Компания")
                        .commentsCompany("Внутренняя заметка")
                        .build())
                .filial(Filial.builder().id(301L).title("Филиал").build())
                .build();
        Product product = Product.builder()
                .id(401L)
                .title("Услуга")
                .price(BigDecimal.TEN)
                .build();
        OrderDetails first = detail(order, product);
        OrderDetails emptySecond = detail(order, product);
        when(orderDetailsRepository.findAllByOrderIdForReviewCheck(101L))
                .thenReturn(List.of(first, emptySecond));

        List<OrderDetailsDTO> result = service().getOrderDetailDTOsByOrderIdForReviewCheck(101L);

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(details -> {
            assertThat(details.getReviews()).isEmpty();
            assertThat(details.getWorkerFio()).isEmpty();
        });
        verify(orderDetailsRepository).findAllByOrderIdForReviewCheck(101L);
        verify(orderDetailsRepository, never()).findByIdForReviewCheck(first.getId());
        verify(orderDetailsRepository, never()).findByIdForReviewCheck(emptySecond.getId());
    }

    @Test
    void publicationMutationLoadsWholeManagedAggregateWithOneRepositoryQuery() {
        OrderDetails first = OrderDetails.builder().id(UUID.randomUUID()).build();
        OrderDetails second = OrderDetails.builder().id(UUID.randomUUID()).build();
        when(orderDetailsRepository.findAllByOrderIdForReviewCheck(101L))
                .thenReturn(List.of(first, second));

        List<OrderDetails> result = service().getOrderDetailsForReviewCheckByOrderId(101L);

        assertThat(result).containsExactly(first, second);
        verify(orderDetailsRepository).findAllByOrderIdForReviewCheck(101L);
        verify(orderDetailsRepository, never()).findByIdForReviewCheck(first.getId());
        verify(orderDetailsRepository, never()).findByIdForReviewCheck(second.getId());
    }

    private OrderDetailsServiceImpl service() {
        return new OrderDetailsServiceImpl(orderDetailsRepository, orderRepository);
    }

    private OrderDetails detail(Order order, Product product) {
        return OrderDetails.builder()
                .id(UUID.randomUUID())
                .order(order)
                .product(product)
                .price(BigDecimal.TEN)
                .reviews(List.of())
                .build();
    }
}

package com.hunt.otziv.admin.controller;

import com.hunt.otziv.p_products.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * order_details.order_detail_product is ON DELETE CASCADE in the schema, so deleting a used
 * product would silently erase order lines. The endpoint must refuse such a delete with 409.
 */
class ApiAdminDictionaryProductDeletionTest {

    private static final String CONFLICT_MESSAGE = "Продукт уже есть в заказах, поэтому удалить его нельзя.";

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ApiAdminDictionaryController controller = controllerWith(productRepository);

    @Test
    void productUsedInOrdersIsRefusedWithConflictAndIsNotDeleted() {
        when(productRepository.existsById(7L)).thenReturn(true);
        when(productRepository.countOrderUsagesByProductId(7L)).thenReturn(3L);

        assertThatThrownBy(() -> controller.deleteProduct(7L))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getReason()).isEqualTo(CONFLICT_MESSAGE);
                });

        verify(productRepository, never()).deleteById(any());
        verify(productRepository, never()).delete(any());
    }

    @Test
    void conflictMessageIsTheSameForAnyNumberOfUsages() {
        when(productRepository.existsById(7L)).thenReturn(true);
        when(productRepository.countOrderUsagesByProductId(7L)).thenReturn(172_835L);

        assertThatThrownBy(() -> controller.deleteProduct(7L))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getReason())
                        .isEqualTo(CONFLICT_MESSAGE));

        verify(productRepository, never()).deleteById(any());
    }

    @Test
    void unusedProductIsDeletedOnlyAfterTheUsageCheck() {
        when(productRepository.existsById(7L)).thenReturn(true);
        when(productRepository.countOrderUsagesByProductId(7L)).thenReturn(0L);

        controller.deleteProduct(7L);

        InOrder order = inOrder(productRepository);
        order.verify(productRepository).existsById(7L);
        order.verify(productRepository).countOrderUsagesByProductId(7L);
        order.verify(productRepository).deleteById(7L);
        verifyNoMoreInteractions(productRepository);
    }

    @Test
    void missingProductStillAnswersNotFoundWithoutCountingOrDeleting() {
        when(productRepository.existsById(7L)).thenReturn(false);

        assertThatThrownBy(() -> controller.deleteProduct(7L))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.getReason()).isEqualTo("Продукт не найден");
                });

        verify(productRepository, never()).countOrderUsagesByProductId(any());
        verify(productRepository, never()).deleteById(any());
    }

    @Test
    void usageCheckAndDeleteRunInOneTransactionWithUnchangedRouteAndAccess() throws Exception {
        Method method = ApiAdminDictionaryController.class.getMethod("deleteProduct", Long.class);

        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
        assertThat(method.getAnnotation(DeleteMapping.class).value()).containsExactly("/products/{id}");
        assertThat(method.getAnnotation(ResponseStatus.class).value()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAnyRole('ADMIN', 'OWNER')");
    }

    @Test
    void usageQueryCountsLiveAndNotRestoredArchivedOrderLinesAndReviews() throws Exception {
        Method method = ProductRepository.class.getMethod("countOrderUsagesByProductId", Long.class);
        Query query = method.getAnnotation(Query.class);

        assertThat(query.nativeQuery()).isTrue();
        assertThat(method.getReturnType()).isEqualTo(long.class);
        String sql = query.value().replaceAll("\\s+", " ");
        assertThat(sql)
                .contains("(SELECT COUNT(*) FROM order_details od WHERE od.order_detail_product = :productId)")
                .contains("(SELECT COUNT(*) FROM reviews r WHERE r.review_product = :productId)")
                .contains("FROM archive_order_details aod"
                        + " JOIN archive_orders ao ON ao.order_id = aod.order_detail_order"
                        + " WHERE aod.order_detail_product = :productId AND ao.restored_at IS NULL)")
                .contains("FROM archive_reviews ar"
                        + " JOIN archive_order_details aod ON aod.order_detail_id = ar.review_order_details"
                        + " JOIN archive_orders ao ON ao.order_id = aod.order_detail_order"
                        + " WHERE ar.review_product = :productId AND ao.restored_at IS NULL)");
        assertThat(sql.split("\\+", -1)).hasSize(4);
    }

    private static ApiAdminDictionaryController controllerWith(ProductRepository productRepository) {
        ApiAdminDictionaryController controller = mock(ApiAdminDictionaryController.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller, "productRepository", productRepository);
        return controller;
    }
}

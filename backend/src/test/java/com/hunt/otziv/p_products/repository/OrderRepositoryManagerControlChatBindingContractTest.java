package com.hunt.otziv.p_products.repository;

import com.hunt.otziv.u_users.model.Manager;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderRepositoryManagerControlChatBindingContractTest {

    @Test
    void countIncludesIncompleteOrdersButExcludesStoppedAndBannedCompanies() throws Exception {
        Method method = OrderRepository.class.getMethod(
                "countManagerControlChatBindingIssuesByManager",
                Manager.class
        );

        assertManagerControlScope(method.getAnnotation(Query.class).value());
    }

    @Test
    void examplesUseTheSameCompanyStatusScopeAndDoNotHideCommonInvoiceOrders() throws Exception {
        Method method = OrderRepository.class.getMethod(
                "findManagerControlChatBindingIssueOrdersByManager",
                Manager.class,
                Pageable.class
        );

        assertManagerControlScope(method.getAnnotation(Query.class).value());
    }

    private void assertManagerControlScope(String query) {
        String normalized = query.replaceAll("\\s+", " ").trim();

        assertTrue(normalized.contains("o.complete = false"));
        assertTrue(normalized.contains("o.manager = :manager"));
        assertTrue(normalized.contains("c.groupId IS NULL OR TRIM(c.groupId) = ''"));
        assertTrue(normalized.contains("cs.title NOT IN ('Бан', 'На стопе')"));
        assertFalse(normalized.contains("os.title <> 'Бан'"));
        assertFalse(normalized.contains("CommonInvoiceOrder"));
        assertFalse(normalized.contains("disabledCommonInvoiceStatus"));
    }
}

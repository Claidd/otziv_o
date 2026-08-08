package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ContractorOrderManagerResolverTest {

    private final ContractorOrderManagerResolver resolver = new ContractorOrderManagerResolver();

    @Test
    void orderManagerOverridesDifferentCompanyManager() {
        Manager orderManager = manager(10L, 110L);
        Order order = order(orderManager, manager(20L, 120L));

        assertThat(resolver.resolve(order, true)).isSameAs(orderManager);
        assertThat(resolver.resolve(order, false)).isSameAs(orderManager);
    }

    @Test
    void currentCompletionMayUseCompanyManagerFallback() {
        Manager companyManager = manager(20L, 120L);

        assertThat(resolver.resolve(order(null, companyManager), true)).isSameAs(companyManager);
    }

    @Test
    void historicalRepairNeverInfersManagerFromCurrentCompanyCard() {
        assertThatThrownBy(() -> resolver.resolve(order(null, manager(20L, 120L)), false))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void historicalRepairWithoutAnyFrozenManagerAlsoFailsClosed() {
        assertThatThrownBy(() -> resolver.resolve(order(null, null), false))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void invalidOrderManagerDoesNotFallThroughToValidCompanyManager() {
        Manager invalid = manager(null, 110L);

        assertThatThrownBy(() -> resolver.resolve(order(invalid, manager(20L, 120L)), true))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(resolver.resolveForRouting(order(invalid, manager(20L, 120L)))).isNull();
    }

    @Test
    void conflictingAliasesFailClosed() {
        Manager orderManager = manager(10L, 110L);
        Manager sameManagerDifferentUser = manager(10L, 120L);
        Manager sameUserDifferentManager = manager(20L, 110L);

        assertThatThrownBy(() -> resolver.resolve(order(orderManager, sameManagerDifferentUser), true))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> resolver.resolve(order(orderManager, sameUserDifferentManager), true))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void currentCompletionWithoutAnyManagerHasNoManagerCandidate() {
        assertThat(resolver.resolve(order(null, null), true)).isNull();
    }

    private Order order(Manager orderManager, Manager companyManager) {
        Company company = new Company();
        company.setManager(companyManager);
        Order order = new Order();
        order.setManager(orderManager);
        order.setCompany(company);
        return order;
    }

    private Manager manager(Long managerId, Long userId) {
        User user = new User();
        user.setId(userId);
        Manager manager = new Manager();
        manager.setId(managerId);
        manager.setUser(user);
        return manager;
    }
}

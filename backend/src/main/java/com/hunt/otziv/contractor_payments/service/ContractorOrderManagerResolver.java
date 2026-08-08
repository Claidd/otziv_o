package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Canonical manager identity used by contractor rewards and invoice routing.
 *
 * <p>The order assignment is authoritative. The company's current manager is
 * only a fallback for work that is being completed now; historical repair
 * must never infer a past recipient from mutable company state.</p>
 */
@Service
public class ContractorOrderManagerResolver {

    /**
     * Routing treats an incomplete candidate as unavailable so the normal
     * specialist -> manager -> owner chain can continue. An internally
     * contradictory alias remains a hard data-integrity conflict.
     */
    public Manager resolveForRouting(Order order) {
        if (order == null) {
            return null;
        }
        Manager orderManager = order.getManager();
        Manager companyManager = companyManager(order.getCompany());
        if (orderManager != null) {
            if (!validIdentity(orderManager)) {
                return null;
            }
            requireNoIdentityAliasConflict(orderManager, companyManager);
            return orderManager;
        }
        return validIdentity(companyManager) ? companyManager : null;
    }

    public Manager resolve(Order order, boolean allowCompanyFallback) {
        if (order == null) {
            return null;
        }
        Manager orderManager = order.getManager();
        Manager companyManager = companyManager(order.getCompany());
        if (orderManager != null) {
            requireValidIdentity(orderManager);
            requireNoIdentityAliasConflict(orderManager, companyManager);
            return orderManager;
        }
        if (!allowCompanyFallback) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Исторический получатель вознаграждения не зафиксирован в заказе; нужна ручная сверка"
            );
        }
        if (companyManager == null) {
            return null;
        }
        requireValidIdentity(companyManager);
        return companyManager;
    }

    private Manager companyManager(Company company) {
        return company == null ? null : company.getManager();
    }

    private void requireValidIdentity(Manager manager) {
        if (!validIdentity(manager)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Некорректная привязка менеджера; нужна ручная сверка"
            );
        }
    }

    private boolean validIdentity(Manager manager) {
        User user = manager == null ? null : manager.getUser();
        return manager != null
                && manager.getId() != null
                && manager.getId() > 0
                && user != null
                && user.getId() != null
                && user.getId() > 0;
    }

    private void requireNoIdentityAliasConflict(Manager orderManager, Manager companyManager) {
        if (companyManager == null || companyManager == orderManager) {
            return;
        }
        Long orderManagerId = orderManager.getId();
        Long companyManagerId = companyManager.getId();
        Long orderUserId = orderManager.getUser().getId();
        Long companyUserId = companyManager.getUser() == null ? null : companyManager.getUser().getId();
        boolean aliasesSameManager = Objects.equals(orderManagerId, companyManagerId);
        boolean aliasesSameUser = Objects.equals(orderUserId, companyUserId);
        if (!aliasesSameManager && !aliasesSameUser) {
            // A valid explicit order-level override of the company's manager.
            return;
        }
        requireValidIdentity(companyManager);
        if (!(aliasesSameManager && aliasesSameUser)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Неоднозначная привязка менеджера; нужна ручная сверка"
            );
        }
    }
}

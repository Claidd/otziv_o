package com.hunt.otziv.p_products.deletion;

import com.hunt.otziv.p_products.model.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeStatusTitle;

@Component
public class OrderDeletionPolicy {

    private static final String ADMIN = "ROLE_ADMIN";
    private static final String OWNER = "ROLE_OWNER";
    private static final String MANAGER = "ROLE_MANAGER";
    private static final Set<String> MANAGER_DELETABLE_STATUSES = Set.of(
            "Новый",
            "В проверку",
            "На проверке",
            "Коррекция",
            "Архив"
    );

    public boolean canDelete(String role, Order orderToDelete) {
        return canDelete(role, safeStatusTitle(orderToDelete));
    }

    public boolean canDelete(String role, String statusTitle) {
        String normalizedRole = normalizeRole(role);
        return isAdminOrOwner(normalizedRole) || (MANAGER.equals(normalizedRole) && canManagerDelete(statusTitle));
    }

    private boolean isAdminOrOwner(String role) {
        return ADMIN.equals(role) || OWNER.equals(role);
    }

    private boolean canManagerDelete(String statusTitle) {
        String status = statusTitle == null ? "" : statusTitle.trim();
        return MANAGER_DELETABLE_STATUSES.contains(status);
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "";
        }

        String trimmed = role.trim();
        String authority = trimmed.startsWith("ROLE_") ? trimmed : "ROLE_" + trimmed;
        return authority.toUpperCase(Locale.ROOT);
    }
}

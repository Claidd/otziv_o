package com.hunt.otziv.p_products.deletion;

import com.hunt.otziv.p_products.model.Order;
import org.springframework.stereotype.Component;

import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeStatusTitle;

@Component
public class OrderDeletionPolicy {

    private static final String ADMIN = "ROLE_ADMIN";
    private static final String OWNER = "ROLE_OWNER";
    private static final String MANAGER = "ROLE_MANAGER";
    private static final String STATUS_NEW = "Новый";

    public boolean canDelete(String role, Order orderToDelete) {
        return isAdminOrOwner(role) || (MANAGER.equals(role) && isNewlyCreatedOrder(orderToDelete));
    }

    private boolean isAdminOrOwner(String role) {
        return ADMIN.equals(role) || OWNER.equals(role);
    }

    private boolean isNewlyCreatedOrder(Order order) {
        return STATUS_NEW.equals(safeStatusTitle(order));
    }
}

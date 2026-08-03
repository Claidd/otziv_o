package com.hunt.otziv.p_products.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OrderEqualityTest {

    @Test
    void differentTransientOrdersAreNotEqual() {
        assertNotEquals(new Order(), new Order());
    }

    @Test
    void persistedIdentityDoesNotDependOnMutableFieldsOrVersion() {
        Order first = new Order();
        first.setId(42L);
        first.setZametka("first");
        first.setRowVersion(3L);

        Order second = new Order();
        second.setId(42L);
        second.setZametka("second");
        second.setRowVersion(99L);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void hashCodeIsStableWhenPersistenceAssignsId() {
        Order order = new Order();
        int before = order.hashCode();

        order.setId(7L);
        order.setZametka("changed");
        order.setRowVersion(1L);

        assertEquals(before, order.hashCode());
        assertFalse(order.equals(new Order()));
    }
}

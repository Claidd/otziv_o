package com.hunt.otziv.c_companies.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CompanyEqualityTest {

    @Test
    void differentTransientCompaniesAreNotEqual() {
        assertNotEquals(new Company(), new Company());
    }

    @Test
    void persistedIdentityAndHashAreStableAcrossMutableChanges() {
        Company first = new Company();
        int transientHash = first.hashCode();
        first.setId(17L);
        first.setTitle("first");
        first.setRowVersion(1L);

        Company second = new Company();
        second.setId(17L);
        second.setTitle("second");
        second.setRowVersion(50L);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(transientHash, first.hashCode());
    }
}

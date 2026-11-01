package com.hunt.otziv.c_companies.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CompanyOptionalPhoneTest {
    @Test void legacyEmptyPhoneCanBeComparedDuringUnrelatedCompanyEdits() {
        var service=mock(CompanyServiceImpl.class,CALLS_REAL_METHODS);
        assertNull(service.changeNumberPhone(null));assertEquals("",service.changeNumberPhone(""));
        assertEquals("79000003154",service.changeNumberPhone("8 (900) 000-31-54"));
    }
}

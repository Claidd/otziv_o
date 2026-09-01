package com.hunt.otziv.p_products.next_order.repository;

import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NextOrderRequestRepositoryContractTest {

    @Test
    void mutationLookupUsesPessimisticWriteLock() throws Exception {
        Method method = NextOrderRequestRepository.class.getMethod("findByIdForUpdate", Long.class);

        Lock lock = method.getAnnotation(Lock.class);
        Query query = method.getAnnotation(Query.class);

        assertNotNull(lock);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
        assertNotNull(query);
        assertTrue(query.value().contains("request.id = :requestId"));
    }
}

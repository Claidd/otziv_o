package com.hunt.otziv.contractor_payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.contractor_payments.model.ContractorRole;
import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

class ContractorPaymentProfileLockContractTest {

    @Test
    void profileMutexQueriesNeverJoinLockTheUserRow() throws Exception {
        Method all = ContractorPaymentProfileRepository.class.getMethod(
                "findAllByUserIdForUpdate",
                Long.class
        );
        Method one = ContractorPaymentProfileRepository.class.getMethod(
                "findByUserIdAndRoleForUpdate",
                Long.class,
                ContractorRole.class
        );

        assertProfileOnlyLock(all);
        assertProfileOnlyLock(one);
    }

    private void assertProfileOnlyLock(Method method) {
        assertThat(method.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        String query = method.getAnnotation(Query.class).value().toUpperCase(java.util.Locale.ROOT);
        assertThat(query).doesNotContain("JOIN", "FETCH");
        assertThat(query).contains("P.USER.ID");
    }
}

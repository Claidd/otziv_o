package com.hunt.otziv.payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.payments.model.PaymentLinkStatus;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class PaymentLinkRepositoryContractTest {

    @Test
    void bulkManualExpirationAdvancesTheOptimisticLockVersion() throws NoSuchMethodException {
        Method method = PaymentLinkRepository.class.getMethod(
                "expireManualLinks",
                Collection.class,
                Collection.class,
                PaymentLinkStatus.class,
                String.class,
                LocalDateTime.class
        );

        Query query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value().replaceAll("\\s+", " "))
                .contains("link.rowVersion = link.rowVersion + 1");
    }
}

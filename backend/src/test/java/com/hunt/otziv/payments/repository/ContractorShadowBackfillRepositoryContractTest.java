package com.hunt.otziv.payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

class ContractorShadowBackfillRepositoryContractTest {

    @Test
    void paymentLinkDiscoverySeparatesLegacyRowsFromMissingPostV222Snapshots() throws Exception {
        Method method = PaymentLinkRepository.class.getMethod(
                "findMissingContractorShadowRouteIds",
                LocalDateTime.class,
                LocalDateTime.class,
                LocalDateTime.class,
                Pageable.class
        );

        String sql = normalized(method.getAnnotation(Query.class).value());

        assertThat(sql)
                .contains("link.shadow_route_generation is not null")
                .contains("link.created_at >= :preparationstartedat");
    }

    @Test
    void commonInvoiceDiscoverySeparatesLegacyRowsFromMissingPostV222Snapshots() throws Exception {
        Method method = CommonInvoiceRepository.class.getMethod(
                "findMissingContractorShadowRouteIds",
                LocalDateTime.class,
                LocalDateTime.class,
                LocalDateTime.class,
                Pageable.class
        );

        String sql = normalized(method.getAnnotation(Query.class).value());

        assertThat(sql)
                .contains("invoice.shadow_route_generation is not null")
                .contains("invoice.payment_route_selected_at >= :preparationstartedat");
    }

    private String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    }
}

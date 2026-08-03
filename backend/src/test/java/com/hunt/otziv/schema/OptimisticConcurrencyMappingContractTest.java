package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.r_review.model.Review;
import jakarta.persistence.Column;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Version;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class OptimisticConcurrencyMappingContractTest {

    @Test
    void aggregateEntitiesMapTheExistingRowVersionColumns() throws Exception {
        for (Class<?> entity : new Class<?>[]{Order.class, OrderDetails.class, Company.class, Review.class}) {
            Field version = entity.getDeclaredField("rowVersion");

            assertThat(version.getAnnotation(Version.class))
                    .as(entity.getSimpleName())
                    .isNotNull();
            assertThat(version.getAnnotation(Column.class).name())
                    .as(entity.getSimpleName())
                    .isEqualTo("row_version");
        }
    }

    @Test
    void sharedStatusAndFilialAssociationsAreManyToOne() throws Exception {
        assertManyToOne(Bot.class.getDeclaredField("status"));
        assertManyToOne(Review.class.getDeclaredField("filial"));
    }

    private static void assertManyToOne(Field field) {
        assertThat(field.getAnnotation(ManyToOne.class)).isNotNull();
        assertThat(field.getAnnotation(OneToOne.class)).isNull();
    }
}

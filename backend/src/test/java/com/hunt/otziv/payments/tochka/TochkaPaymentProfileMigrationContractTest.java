package com.hunt.otziv.payments.tochka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class TochkaPaymentProfileMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_277__seed_disabled_tochka_payment_profile.sql";

    @Test
    void seedsOnlyAnInactiveNonDefaultTochkaProfileWithoutSecretsOrAssignments() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("'tochka-primary'")
                .contains("'tochka'")
                .contains("'tochka-profile-placeholder'")
                .contains("password_env_key")
                .contains("null")
                .contains("false,\n    false,\n    false")
                .doesNotContain("update managers")
                .doesNotContain("insert into managers")
                .doesNotContain("jwt")
                .doesNotMatch("(?s).*\\b[0-9]{15}\\b.*");
    }

    @Test
    void seedIsIdempotentForBothUniqueProfileKeys() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("where not exists")
                .contains("where code = 'tochka-primary'")
                .contains("or terminal_key = 'tochka-profile-placeholder'");
    }

    private String migration() throws Exception {
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .toLowerCase(Locale.ROOT);
        }
    }
}

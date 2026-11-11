package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LocalPaymentSandboxTest {

    private static final Set<String> PROFILES = Set.of("prod", "paper-invoice-fixture", "payment-sandbox");

    @Test
    void acceptsAnEnvironmentWhereEveryBankAndChatIsLocal() {
        assertThat(LocalPaymentSandbox.isolationProblems(isolated()::get, PROFILES)).isEmpty();
    }

    @Test
    void refusesWithoutThePaperFixtureProfile() {
        assertThat(LocalPaymentSandbox.isolationProblems(isolated()::get, Set.of("prod", "payment-sandbox")))
                .containsExactly("profile paper-invoice-fixture is not active");
    }

    @Test
    void refusesTheRealBankAddress() {
        Map<String, String> environment = isolated();
        environment.put("otziv.payments.tbank.base-url", "https://securepay.tinkoff.ru");

        assertThat(LocalPaymentSandbox.isolationProblems(environment::get, PROFILES))
                .containsExactly("otziv.payments.tbank.base-url is not the local fixture");
    }

    @Test
    void refusesEveryRealCredentialLeftInTheEnvironment() {
        Map<String, String> environment = isolated();
        environment.put("otziv.payments.tbank.primary-live-terminal-key", "1700000000000");
        environment.put("otziv.payments.tbank.secondary-test-password", "real-password");
        environment.put("otziv.payments.tbank.password", "legacy-password");
        environment.put("otziv.payments.tochka.jwt-token", "eyJhbGciOi");
        environment.put("max.bot.token", "real-token");
        environment.put("telegram.bot.sending-enabled", "true");
        environment.put("whatsapp.clients[0].url", "http://gateway:3000");
        environment.put("otziv.mobile.push.firebase.enabled", "true");
        environment.put("spring.mail.host", "smtp.example.com");

        assertThat(LocalPaymentSandbox.isolationProblems(environment::get, PROFILES)).containsExactly(
                "otziv.payments.tbank.primary-live-terminal-key is not a sandbox terminal",
                "otziv.payments.tbank.secondary-test-password is not the sandbox marker",
                "otziv.payments.tbank.password is not the sandbox marker",
                "otziv.payments.tochka.jwt-token is not the local marker",
                "max.bot.token is set",
                "telegram.bot.sending-enabled is not false",
                "a WhatsApp gateway is configured",
                "otziv.mobile.push.firebase.enabled is true",
                "spring.mail.host is not localhost");
    }

    @Test
    void treatsAnUnsetTelegramSwitchAsEnabled() {
        Map<String, String> environment = isolated();
        environment.remove("telegram.bot.sending-enabled");

        assertThat(LocalPaymentSandbox.isolationProblems(environment::get, PROFILES))
                .containsExactly("telegram.bot.sending-enabled is not false");
    }

    @Test
    void givesAWipedProfileTestRequisitesAndAdmission() {
        ContractorPaymentProfile profile = new ContractorPaymentProfile();
        profile.setId(18L);
        assertThat(LocalPaymentSandbox.prepare(profile, " Юлия Ф. ")).isTrue();

        assertThat(profile.getRecipientName()).isEqualTo("Тест Юлия Ф.");
        assertThat(profile.getPaymentPhone()).isEqualTo(LocalPaymentSandbox.testPhone(18L));
        assertThat(ContractorPaymentTransferNumber.isValid(profile.getPaymentPhone())).isTrue();
        assertThat(profile.getBankName()).isEqualTo("Тестовый банк");
        assertThat(profile.isEnabled()).isTrue();
        assertThat(profile.isLiveEnabled()).isTrue();
        assertThat(LocalPaymentSandbox.prepare(profile, "Юлия Ф.")).isFalse();
    }

    @Test
    void namesAnEmployeeWithoutANameByTheProfile() {
        ContractorPaymentProfile profile = new ContractorPaymentProfile();
        profile.setId(3L);

        LocalPaymentSandbox.prepare(profile, null);

        assertThat(profile.getRecipientName()).isEqualTo("Тест сотрудник 3");
    }

    @Test
    void keepsRequisitesAlreadyEnteredOnTheStand() {
        ContractorPaymentProfile profile = new ContractorPaymentProfile();
        profile.setId(7L);
        profile.setRecipientName("Юлия Тестовая");
        profile.setPaymentPhone("79990000018");
        profile.setBankName("Тестовый банк");
        profile.setEnabled(true);
        profile.setLiveEnabled(true);
        assertThat(LocalPaymentSandbox.prepare(profile, "Юлия Ф.")).isFalse();
        assertThat(profile.getRecipientName()).isEqualTo("Юлия Тестовая");
        assertThat(profile.getPaymentPhone()).isEqualTo("79990000018");
    }

    @Test
    void testPhonesDifferBetweenProfiles() {
        assertThat(List.of(LocalPaymentSandbox.testPhone(1L), LocalPaymentSandbox.testPhone(2L),
                LocalPaymentSandbox.testPhone(21L))).doesNotHaveDuplicates();
    }

    private static Map<String, String> isolated() {
        Map<String, String> environment = new HashMap<>();
        environment.put("paper-invoices.fixture-url", "http://paper-invoice-fixture:8099");
        environment.put("otziv.payments.tbank.base-url", "http://paper-invoice-fixture:8099/tbank");
        for (String shop : List.of("primary-", "primary-test-", "primary-live-")) {
            environment.put("otziv.payments.tbank." + shop + "terminal-key", "LocalSandboxPrimary");
            environment.put("otziv.payments.tbank." + shop + "password", "local-sandbox-only");
        }
        for (String shop : List.of("secondary-", "secondary-test-", "secondary-live-")) {
            environment.put("otziv.payments.tbank." + shop + "terminal-key", "LocalSandboxSecondary");
            environment.put("otziv.payments.tbank." + shop + "password", "local-sandbox-only");
        }
        environment.put("otziv.payments.tbank.terminal-key", "");
        environment.put("otziv.payments.tbank.password", "");
        environment.put("otziv.payments.tochka.jwt-token", "local-fixture-only");
        environment.put("max.bot.token", "");
        environment.put("telegram.bot.sending-enabled", "false");
        environment.put("otziv.mobile.push.firebase.enabled", "false");
        environment.put("spring.mail.host", "localhost");
        return environment;
    }
}

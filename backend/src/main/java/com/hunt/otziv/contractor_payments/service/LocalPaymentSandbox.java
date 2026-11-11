package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Local Docker stand only (profile {@code payment-sandbox}): the payment flows run as on production while
 * every bank is the isolated fixture and no client or employee chat can be reached.
 *
 * <p>The stand works on a copy of the production database and its env file holds real bank and messenger
 * credentials. The sandbox therefore refuses to start unless each of them is replaced by a local marker;
 * the switches it relies on are listed in {@code docs/invoice-flows/06-local-payment-sandbox.md}.</p>
 */
@Configuration
@Profile(LocalPaymentSandbox.PROFILE)
@Slf4j
public class LocalPaymentSandbox {

    public static final String PROFILE = "payment-sandbox";
    static final String FIXTURE_URL = "http://paper-invoice-fixture:8099";
    static final String TBANK_URL = FIXTURE_URL + "/tbank";
    static final Set<String> TERMINALS = Set.of("LocalSandboxPrimary", "LocalSandboxSecondary");
    /** The public marker that stands in for every terminal password in the sandbox; the fixture signs with it. */
    static final String SANDBOX_MARKER = "local-sandbox-only";
    static final String TOCHKA_TOKEN = "local-fixture-only";
    static final String TEST_BANK = "Тестовый банк";

    private static final List<String> TBANK_SHOPS = List.of("primary-", "primary-test-", "primary-live-",
            "secondary-", "secondary-test-", "secondary-live-", "");

    LocalPaymentSandbox(Environment environment) {
        List<String> problems = isolationProblems(environment::getProperty, Set.of(environment.getActiveProfiles()));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Local payment sandbox refused to start: " + String.join("; ", problems));
        }
        log.warn("Local payment sandbox is active: banks and client chats are the isolated fixture {}", FIXTURE_URL);
    }

    /** Everything that could let a sandbox action reach a real bank, client or employee. */
    static List<String> isolationProblems(Function<String, String> property, Set<String> activeProfiles) {
        List<String> problems = new ArrayList<>();
        if (!activeProfiles.contains("paper-invoice-fixture")) {
            problems.add("profile paper-invoice-fixture is not active");
        }
        if (!FIXTURE_URL.equals(clean(property.apply("paper-invoices.fixture-url")))) {
            problems.add("paper-invoices.fixture-url is not the local fixture");
        }
        if (!TBANK_URL.equals(clean(property.apply("otziv.payments.tbank.base-url")))) {
            problems.add("otziv.payments.tbank.base-url is not the local fixture");
        }
        for (String shop : TBANK_SHOPS) {
            String terminal = clean(property.apply("otziv.payments.tbank." + shop + "terminal-key"));
            if (!terminal.isEmpty() && !TERMINALS.contains(terminal)) {
                problems.add("otziv.payments.tbank." + shop + "terminal-key is not a sandbox terminal");
            }
            String password = clean(property.apply("otziv.payments.tbank." + shop + "password"));
            if (!password.isEmpty() && !SANDBOX_MARKER.equals(password)) {
                problems.add("otziv.payments.tbank." + shop + "password is not the sandbox marker");
            }
        }
        String tochkaToken = clean(property.apply("otziv.payments.tochka.jwt-token"));
        if (!tochkaToken.isEmpty() && !TOCHKA_TOKEN.equals(tochkaToken)) {
            problems.add("otziv.payments.tochka.jwt-token is not the local marker");
        }
        if (!clean(property.apply("max.bot.token")).isEmpty()) {
            problems.add("max.bot.token is set");
        }
        if (!"false".equalsIgnoreCase(clean(property.apply("telegram.bot.sending-enabled")))) {
            problems.add("telegram.bot.sending-enabled is not false");
        }
        if (!clean(property.apply("whatsapp.clients[0].id")).isEmpty()
                || !clean(property.apply("whatsapp.clients[0].url")).isEmpty()) {
            problems.add("a WhatsApp gateway is configured");
        }
        if ("true".equalsIgnoreCase(clean(property.apply("otziv.mobile.push.firebase.enabled")))) {
            problems.add("otziv.mobile.push.firebase.enabled is true");
        }
        String mailHost = clean(property.apply("spring.mail.host"));
        if (!mailHost.isEmpty() && !"localhost".equalsIgnoreCase(mailHost)) {
            problems.add("spring.mail.host is not localhost");
        }
        return List.copyOf(problems);
    }

    /**
     * The restore script removes real requisites and switches every profile off. Here the profiles of working
     * employees get obviously fake requisites and their admission back, so that a card number can be issued.
     */
    @Bean
    ApplicationRunner localPaymentSandboxRecipients(
            ContractorPaymentProfileRepository profiles,
            ContractorRewardInitialMonthSyncCoordinator monthSync,
            ContractorPaymentRuntimeSwitch runtimeSwitch,
            PlatformTransactionManager transactionManager
    ) {
        return arguments -> {
            List<Long> opened = new TransactionTemplate(transactionManager).execute(status -> {
                List<Long> changed = new ArrayList<>();
                for (Object[] row : profiles.findWorkingEmployeeProfileNames()) {
                    ContractorPaymentProfile profile = profiles.findById((Long) row[0]).orElse(null);
                    if (profile == null || !prepare(profile, (String) row[1])) {
                        continue;
                    }
                    profiles.save(profile);
                    changed.add(profile.getId());
                }
                return changed;
            });
            if (opened != null) {
                opened.forEach(monthSync::request);
                log.warn("Local payment sandbox: test requisites and admission given to {} payment profiles", opened.size());
            }
            // payment-sandbox.ps1 reads this line to tell the operator whether card numbers can be issued.
            List<String> blockers = runtimeSwitch.liveRoutingBlockers();
            log.warn("Local payment sandbox: recipient routing {}", blockers.isEmpty()
                    ? "is ON" : "is OFF: " + String.join("; ", blockers));
        };
    }

    /** @return whether the profile was changed */
    static boolean prepare(ContractorPaymentProfile profile, String employeeName) {
        boolean changed = false;
        if (clean(profile.getRecipientName()).isEmpty()) {
            String name = clean(employeeName);
            profile.setRecipientName("Тест " + (name.isEmpty() ? "сотрудник " + profile.getId() : name));
            changed = true;
        }
        if (clean(profile.getPaymentPhone()).isEmpty()) {
            profile.setPaymentPhone(testPhone(profile.getId()));
            changed = true;
        }
        if (clean(profile.getBankName()).isEmpty()) {
            profile.setBankName(TEST_BANK);
            changed = true;
        }
        if (!profile.isEnabled() || !profile.isLiveEnabled()) {
            profile.setEnabled(true);
            profile.setLiveEnabled(true);
            changed = true;
        }
        return changed;
    }

    /** A number from the range reserved for fiction, unique per profile. */
    static String testPhone(Long profileId) {
        return ContractorPaymentTransferNumber.normalize("+7900" + String.format("%07d", profileId == null ? 0 : profileId % 10_000_000L));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}

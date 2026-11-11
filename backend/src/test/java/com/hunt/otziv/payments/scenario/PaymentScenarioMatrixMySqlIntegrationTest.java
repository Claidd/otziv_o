package com.hunt.otziv.payments.scenario;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.common_billing.api.CommonInvoiceOverdueNotices;
import com.hunt.otziv.common_billing.dto.CommonInvoicePaymentRouteChangeRequest;
import com.hunt.otziv.common_billing.dto.CommonInvoicePaymentRouteChangeTarget;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionRequest;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionRowRequest;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentOptionsResponse;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentRecipientCandidateResponse;
import com.hunt.otziv.common_billing.service.BankRecipientChanges;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.common_billing.service.CommonInvoiceMessageWorker;
import com.hunt.otziv.common_billing.service.CommonInvoiceOverdueEscalation;
import com.hunt.otziv.common_billing.service.CommonInvoicePaymentNotificationDeliveryService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.dto.ManualCardPaymentRecipientResponse;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.notification_media.service.ThematicStaffNotificationJob;
import com.hunt.otziv.payments.api.OrderBankRecipientChange;
import com.hunt.otziv.payments.dto.ManagerManualCardPaymentResultResponse;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeRequest;
import com.hunt.otziv.payments.dto.PaymentRouteChangeResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeTarget;
import com.hunt.otziv.payments.scenario.PaymentScenarioWorld.CommonInvoiceSeed;
import com.hunt.otziv.payments.scenario.PaymentScenarioWorld.Person;
import com.hunt.otziv.payments.service.CommonInvoicePaymentDisputeService;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.PaymentRouteChangeService;
import com.hunt.otziv.payments.service.TbankClient;
import com.hunt.otziv.payments.tochka.service.TochkaClient;
import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static com.hunt.otziv.payments.scenario.PaymentScenarioWorld.STATUS_INVOICED;
import static com.hunt.otziv.payments.scenario.PaymentScenarioWorld.STATUS_PAID;
import static com.hunt.otziv.payments.scenario.PaymentScenarioWorld.STATUS_PUBLISHED;
import static com.hunt.otziv.payments.scenario.PaymentScenarioWorld.STATUS_UNPAID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whole payment scenarios — «выставить → сменить получателя → отметить оплату» — on the migrated MySQL
 * schema and the full Spring context. Every step is a call of the production service a controller or a
 * background worker calls; every expectation is read back from the tables. Only the banks and the
 * messengers are replaced. No outer test transaction: commits, row locks and after-commit hooks are real.
 *
 * <p>Read a scenario as: cast (who has which balance) → steps → rows. Numbers in the display names are
 * the scenario numbers of {@code docs/invoice-flows/05-scenario-tests.md}.</p>
 */
@SpringBootTest(properties = {
        "spring.main.banner-mode=off",
        "spring.jpa.open-in-view=false",
        "otziv.monitoring.enabled=false",
        "telegram.bot.sending-enabled=false",
        "telegram.bot.registration-enabled=false",
        "max.bot.webhook-auto-register-enabled=false",
        "max.bot.long-polling-enabled=false",
        // Deployment masters of the new accounting and of contractor requisites (off by default).
        "otziv.contractor-payments.live-routing-master-enabled=true",
        "otziv.contractor-payments.reward-attribution-master-enabled=true",
        // A demo T-Bank terminal with credentials: the bank itself is the mocked TbankClient.
        "otziv.payments.tbank.enabled=true",
        "otziv.payments.tbank.primary-terminal-key=1779443245436DEMO",
        "otziv.payments.tbank.primary-password=" + PaymentScenarioMatrixMySqlIntegrationTest.TERMINAL_PASSWORD,
        // Used only while a scenario switches the bank to LIVE: a payment of an order through a demo
        // terminal is recorded as a trial (TEST_CONFIRMED) and does not move the order.
        "otziv.payments.tbank.primary-live-terminal-key=ScenarioLiveTerminal",
        "otziv.payments.tbank.primary-live-password=" + PaymentScenarioMatrixMySqlIntegrationTest.TERMINAL_PASSWORD
})
@ActiveProfiles("test")
@Import(PaymentScenarioMatrixMySqlIntegrationTest.QuietBackground.class)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class PaymentScenarioMatrixMySqlIntegrationTest {

    /**
     * Production runs the application and MySQL with {@code TZ=Asia/Irkutsk}, and the payment code relies on
     * it: it mixes the JVM clock ({@code LocalDate.now()} for the pay day), the database clock ({@code NOW()})
     * and the business clock of contractor accounting. The stand keeps the three aligned the same way, so a
     * scenario behaves identically on a developer machine and on CI at any hour. Restored after the class.
     */
    private static final String PRODUCTION_ZONE = "Asia/Irkutsk";
    private static TimeZone hostZone;

    /** Runs before the Spring context of this class is created, and is always paired with the restore below. */
    @BeforeAll
    static void useProductionZone() {
        hostZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(PRODUCTION_ZONE));
    }

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("otziv").withUsername("root").withPassword(UUID.randomUUID().toString())
            // Irkutsk has no daylight saving time: a fixed offset needs no time zone tables in the image.
            .withCommand("--restrict-fk-on-non-standard-key=OFF", "--default-time-zone=+08:00");

    @AfterAll
    static void restoreHostZone() {
        if (hostZone != null) {
            TimeZone.setDefault(hostZone);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        // Frozen client messages and requisites snapshots are stored encrypted; production requires a key.
        registry.add("otziv.credential-encryption.active-key-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    /** The application without its timers: workers run only when a scenario calls them. */
    @TestConfiguration(proxyBeanMethods = false)
    static class QuietBackground {
        @Bean
        static BeanFactoryPostProcessor stopSchedulers() {
            return factory -> {
                String processor = "org.springframework.context.annotation.internalScheduledAnnotationProcessor";
                if (factory instanceof BeanDefinitionRegistry registry && registry.containsBeanDefinition(processor)) {
                    registry.removeBeanDefinition(processor);
                }
            };
        }
    }

    // External systems: banks and messengers.
    static final String TERMINAL_PASSWORD = "scenario-test-password";

    @MockitoBean TbankClient tbank;
    @MockitoBean TochkaClient tochka;
    @MockitoBean ClientChatMessageSender clientChat;
    @MockitoBean TelegramService staffTelegram;

    // Production entry points the scenarios drive.
    @Autowired CommonBillingService billing;
    @Autowired BankRecipientChanges recipientChanges;
    @Autowired OrderBankRecipientChange orderRecipientChanges;
    @Autowired PaymentLinkService payments;
    @Autowired PaymentRouteChangeService orderRouteChanges;
    @Autowired com.hunt.otziv.contractor_payments.service.ContractorPaymentReconciliationDispatcher reserveReconciliation;
    @Autowired CommonInvoiceMessageWorker clientMessageWorker;
    @Autowired CommonInvoicePaymentNotificationDeliveryService paymentNotices;
    @Autowired CommonInvoicePaymentDisputeService disputes;
    @Autowired CommonInvoiceOverdueEscalation overdue;
    @Autowired CommonInvoiceOverdueNotices overdueNotices;
    @Autowired ThematicStaffNotificationJob staffNotices;
    @Autowired AppSettingService settings;

    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired CredentialCipher cipher;

    private static boolean timersStopped;

    private PaymentScenarioWorld world;
    private PaymentScenarioWorld.FakeTbank bank;
    private final List<String> clientMessages = new ArrayList<>();

    @BeforeEach
    void openWorld() {
        if (!timersStopped) {
            // Removing the processor's definition (QuietBackground) does not stop the timers that Spring Boot has
            // already registered: without this the periodic workers run beside the scenarios and race them for the
            // same invoices (deliveries, reconciliation, repairs that switch routing off for a moment).
            context.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)
                    .values().forEach(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor::destroy);
            context.getBeansOfType(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class)
                    .values().forEach(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler::shutdown);
            timersStopped = true;
        }
        // The application and the database must tell the same time, as on production.
        LocalDateTime databaseNow = jdbc.queryForObject("SELECT NOW(6)", LocalDateTime.class);
        assertThat(ChronoUnit.MINUTES.between(databaseNow, LocalDateTime.now())).as("database clock vs application clock").isBetween(-2L, 2L);
        PaymentScenarioWorld.activateLiveRouting(context);
        world = PaymentScenarioWorld.open(context);
        bank = new PaymentScenarioWorld.FakeTbank();
        bank.install(tbank);
        clientMessages.clear();
        when(clientChat.deliverWithOperationId(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            clientMessages.add(call.getArgument(3, String.class));
            return ClientMessageSendResult.sent("WHATSAPP", "scenario-" + UUID.randomUUID());
        });
        when(staffTelegram.sendMessage(anyLong(), anyString())).thenReturn(true);
        when(staffTelegram.sendMessageWithInlineButton(anyLong(), anyString(), anyString(), anyString())).thenReturn(true);
        when(staffTelegram.sendMessageWithInlineKeyboard(anyLong(), anyString(), anyString(), any())).thenReturn(true);
        // The database is shared by the class and the worker takes five messages a pass, oldest first: what an
        // earlier scenario left queued must neither arrive in the middle of this one nor take its turns.
        for (int pass = 0; pass < 4; pass++) {
            deliverQueuedClientMessages();
        }
        clientMessages.clear();
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ================================================================================================
    // Общий счёт: выставление и автоматический выбор получателя
    // ================================================================================================

    @Test
    @DisplayName("01 общий счёт: один специалист на всех заказах, резерва хватает → его реквизиты, резерв RESERVED")
    void s01_commonInvoiceIsIssuedToTheOnlySpecialistWithEnoughBalance() {
        Person anna = world.specialist("Анна Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        world.payable(world.manager, ContractorRole.MANAGER, 10_000_00);
        long firstOrder = world.order(world.company("Кафе"), anna, 2_000_00, STATUS_PUBLISHED);
        long secondOrder = world.order(world.company("Пекарня"), anna, 1_000_00, STATUS_PUBLISHED);
        CommonInvoiceSeed invoice = world.commonInvoice("Связь", firstOrder, secondOrder);

        issue(invoice);

        Map<String, Object> issued = invoice(invoice);
        assertThat(issued).containsEntry("status", "INVOICED")
                .containsEntry("payment_route_type", "MANUAL_MOBILE_BANK")
                .containsEntry("payment_route_manual_source", "CONTRACTOR_PAYMENT_PROFILE");
        assertThat(num(issued, "amount_kopecks")).isEqualTo(3_000_00);
        assertThat(num(issued, "payment_route_amount_kopecks")).isEqualTo(3_000_00);
        assertThat(issued.get("sent_at")).isNotNull();

        Map<String, Object> reserve = reserve(issued.get("contractor_allocation_id"));
        assertThat(reserve).containsEntry("mode", "LIVE").containsEntry("source_type", "COMMON_INVOICE")
                .containsEntry("status", "RESERVED").containsEntry("recipient_type", "SPECIALIST")
                .containsEntry("routing_decision_reason", "SPECIALIST_SELECTED");
        assertThat(num(reserve, "source_id")).isEqualTo(invoice.invoiceId());
        assertThat(num(reserve, "recipient_profile_id")).isEqualTo(anna.specialistProfileId);
        assertThat(num(reserve, "amount_kopecks")).isEqualTo(3_000_00);
        assertThat(num(reserve, "available_before_kopecks")).isEqualTo(10_000_00);
        assertThat(reserves(invoice)).hasSize(1);

        // The frozen requisites are the specialist's, and they are what the client received.
        assertThat(digits(cipher.decrypt((String) reserve.get("payment_phone_snapshot")))).isEqualTo(requisites(anna));
        assertThat(clientMessages).hasSize(1);
        assertThat(digits(clientMessages.getFirst())).contains(requisites(anna));
        assertThat(orderStatus(firstOrder)).isEqualTo(STATUS_INVOICED);
        assertThat(orderStatus(secondOrder)).isEqualTo(STATUS_INVOICED);
    }

    /** Why the specialist of the invoice cannot take it; the manager is next in the chain. */
    enum SpecialistObstacle {
        LOW_BALANCE("INSUFFICIENT_AVAILABLE_BALANCE"),
        ADMISSION_OFF("PROFILE_DISABLED"),
        MIXED_SPECIALISTS("MIXED_SPECIALISTS");

        final String rejection;

        SpecialistObstacle(String rejection) {
            this.rejection = rejection;
        }
    }

    @ParameterizedTest(name = "02 общий счёт: специалист не подходит ({0}) → выбран менеджер")
    @EnumSource(SpecialistObstacle.class)
    void s02_managerIsChosenWhenTheSpecialistCannotTakeTheInvoice(SpecialistObstacle obstacle) {
        Person anna = world.specialist("Анна Специалист");
        Person boris = world.specialist("Борис Специалист");
        world.profile(anna, ContractorRole.SPECIALIST, obstacle == SpecialistObstacle.LOW_BALANCE ? 1_000_00 : 10_000_00,
                obstacle != SpecialistObstacle.ADMISSION_OFF, obstacle != SpecialistObstacle.ADMISSION_OFF);
        world.payable(boris, ContractorRole.SPECIALIST, 10_000_00);
        world.payable(world.manager, ContractorRole.MANAGER, 10_000_00);
        long company = world.company("Кафе");
        CommonInvoiceSeed invoice = world.commonInvoice("Связь",
                world.order(company, anna, 2_000_00, STATUS_PUBLISHED),
                world.order(company, obstacle == SpecialistObstacle.MIXED_SPECIALISTS ? boris : anna, 1_000_00, STATUS_PUBLISHED));

        issue(invoice);

        Map<String, Object> issued = invoice(invoice);
        assertThat(issued).containsEntry("status", "INVOICED").containsEntry("payment_route_type", "MANUAL_MOBILE_BANK");
        Map<String, Object> reserve = reserve(issued.get("contractor_allocation_id"));
        assertThat(reserve).containsEntry("status", "RESERVED").containsEntry("recipient_type", "MANAGER")
                .containsEntry("routing_decision_reason", "MANAGER_SELECTED")
                .containsEntry("specialist_rejection_reason", obstacle.rejection);
        assertThat(num(reserve, "recipient_profile_id")).isEqualTo(world.manager.managerProfileId);
        assertThat(num(reserve, "amount_kopecks")).isEqualTo(3_000_00);
        assertThat(digits(clientMessages.getFirst())).contains(requisites(world.manager));
    }

    @Test
    @DisplayName("03 общий счёт: никто не подходит → маршрут владельца (OWNER_FALLBACK), банковская ссылка создаётся по нажатию клиента")
    void s03_ownerRouteWhenNobodyCanTakeTheInvoice() {
        Person anna = world.specialist("Анна Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 500_00);
        world.payable(world.manager, ContractorRole.MANAGER, 500_00);
        CommonInvoiceSeed invoice = world.commonInvoice("Связь", world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED));

        issue(invoice);

        Map<String, Object> issued = invoice(invoice);
        assertThat(issued).containsEntry("status", "INVOICED").containsEntry("payment_route_type", "BANK_LINK");
        assertThat(issued.get("payment_route_manual_source")).isNull();
        Map<String, Object> reserve = reserve(issued.get("contractor_allocation_id"));
        assertThat(reserve).containsEntry("status", "OWNER_FALLBACK").containsEntry("recipient_type", "OWNER")
                .containsEntry("routing_decision_reason", "INSUFFICIENT_AVAILABLE_BALANCE")
                .containsEntry("specialist_rejection_reason", "INSUFFICIENT_AVAILABLE_BALANCE")
                .containsEntry("manager_rejection_reason", "INSUFFICIENT_AVAILABLE_BALANCE");
        assertThat(reserve.get("recipient_profile_id")).isNull();
        verify(tbank, never()).init(any(), any());

        // The client opens the invoice page and presses «Оплатить»: only now the bank is asked for a link.
        SecurityContextHolder.clearContext();
        billing.initPublicPayment(invoice.token(), "client@scenario.test", true, true, true);

        verify(tbank, times(1)).init(any(), any());
        List<Map<String, Object>> bankLinks = bankLinks(invoice);
        assertThat(bankLinks).hasSize(1);
        assertThat(bankLinks.getFirst()).containsEntry("status", "CURRENT").containsEntry("provider", "T_BANK");
        assertThat(num(bankLinks.getFirst(), "amount_kopecks")).isEqualTo(3_000_00);
        Map<String, Object> withLink = invoice(invoice);
        assertThat(withLink.get("tbank_payment_id")).isEqualTo(bankLinks.getFirst().get("provider_payment_id"));
        assertThat((String) withLink.get("payment_url")).startsWith("https://securepay.tinkoff.ru/");
        assertThat(bank.status((String) withLink.get("tbank_payment_id"))).isEqualTo("NEW");
        // Money to the owner's bank link is confirmed by the bank: the reserve stays an owner fallback.
        assertThat(reserve(issued.get("contractor_allocation_id"))).containsEntry("status", "OWNER_FALLBACK");
    }

    // ================================================================================================
    // Общий счёт: «Выдать номер карты»
    // ================================================================================================

    @ParameterizedTest(name = "04 «Выдать номер карты», автоматический выбор (другой получатель: {0}) → прежний резерв CANCELED, новый RESERVED у {1}")
    @CsvSource({"false, SPECIALIST", "true, MANAGER"})
    void s04_cardNumberWithAutomaticChoiceReplacesTheReserve(boolean differentRecipient, String expectedRecipient) {
        Person anna = world.specialist("Анна Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        world.payable(world.manager, ContractorRole.MANAGER, 10_000_00);
        CommonInvoiceSeed invoice = world.commonInvoice("Связь", world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED));
        issue(invoice);
        long firstReserve = num(invoice(invoice), "contractor_allocation_id");

        if (differentRecipient) {
            recipientChanges.invoice(invoice.invoiceId(), cardNumber(invoice, null), world.manager.auth());
        } else {
            recipientChanges.invoice(invoice.invoiceId(), cardNumber(invoice, null), world.manager.auth(), false);
        }

        // Before the client is told: the old reserve is gone, the new one is held, the invoice waits for re-sending.
        Map<String, Object> changed = invoice(invoice);
        assertThat(changed).containsEntry("status", "READY").containsEntry("payment_message_operation_kind", "ROUTE_CHANGE");
        assertThat(changed.get("sent_at")).isNull();
        assertThat(changed.get("payment_message_confirmed")).isEqualTo(false);
        assertThat((String) changed.get("last_error")).startsWith("payment_route_changed_message");
        assertThat(queuedClientMessages(invoice)).containsExactly("QUEUED");
        long secondReserve = num(changed, "contractor_allocation_id");
        assertThat(secondReserve).isNotEqualTo(firstReserve);
        assertThat(reserve(firstReserve)).containsEntry("status", "CANCELED");
        assertThat(reserve(firstReserve).get("released_at")).isNotNull();
        Map<String, Object> reserve = reserve(secondReserve);
        assertThat(reserve).containsEntry("status", "RESERVED").containsEntry("recipient_type", expectedRecipient);
        assertThat(num(reserve, "attempt_no")).isEqualTo(2);
        assertThat(num(reserve, "amount_kopecks")).isEqualTo(3_000_00);
        // The released amount is available again: nothing is counted twice.
        assertThat(num(reserve, "available_before_kopecks")).isEqualTo(10_000_00);
        assertThat(reserves(invoice)).hasSize(2);

        deliverQueuedClientMessages();

        Person recipient = differentRecipient ? world.manager : anna;
        assertThat(invoice(invoice)).containsEntry("status", "INVOICED").containsEntry("payment_message_confirmed", true);
        assertThat(invoice(invoice).get("sent_at")).isNotNull();
        assertThat(clientMessages).hasSize(2);
        assertThat(digits(clientMessages.getLast())).contains(requisites(recipient));
    }

    @Test
    @DisplayName("05 «Выдать номер карты» названному получателю сверх резерва → RESERVED, RECIPIENT_CHOSEN_BY_MANAGER; автоподбор его потом не берёт")
    void s05_namedRecipientIsReservedAboveBalanceAndThenLeftOutOfAutomaticRouting() {
        Person anna = world.specialist("Анна Специалист");
        Person boris = world.specialist("Борис Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        world.payable(boris, ContractorRole.SPECIALIST, 1_000_00);
        world.payable(world.manager, ContractorRole.MANAGER, 10_000_00);
        CommonInvoiceSeed invoice = world.commonInvoice("Связь", world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED));
        issue(invoice);
        long annaReserve = num(invoice(invoice), "contractor_allocation_id");

        // The window lists Boris as selectable, with the overrun the manager is about to accept.
        login(world.manager);
        var listed = billing.commonInvoiceRouteRecipients(invoice.invoiceId()).recipients().stream()
                .filter(recipient -> recipient.profileId().equals(boris.specialistProfileId)).findFirst().orElseThrow();
        assertThat(listed.blockReason()).isNull();
        assertThat(listed.teamMember()).isTrue();
        assertThat(listed.availableKopecks()).isEqualTo(1_000_00);
        assertThat(listed.overrunKopecks()).isEqualTo(2_000_00);

        recipientChanges.invoice(invoice.invoiceId(), cardNumber(invoice, boris.specialistProfileId), world.manager.auth(), false);
        deliverQueuedClientMessages();

        Map<String, Object> changed = invoice(invoice);
        assertThat(changed).containsEntry("status", "INVOICED").containsEntry("payment_route_type", "MANUAL_MOBILE_BANK");
        assertThat(reserve(annaReserve)).containsEntry("status", "CANCELED");
        Map<String, Object> borisReserve = reserve(changed.get("contractor_allocation_id"));
        assertThat(borisReserve).containsEntry("status", "RESERVED").containsEntry("recipient_type", "SPECIALIST")
                .containsEntry("routing_decision_reason", "RECIPIENT_CHOSEN_BY_MANAGER");
        assertThat(num(borisReserve, "recipient_profile_id")).isEqualTo(boris.specialistProfileId);
        assertThat(num(borisReserve, "amount_kopecks")).isEqualTo(3_000_00);
        assertThat(num(borisReserve, "available_before_kopecks")).isEqualTo(1_000_00).isLessThan(num(borisReserve, "amount_kopecks"));
        assertThat(digits(clientMessages.getLast())).contains(requisites(boris));

        // Another invoice of Boris's own order, small enough for his balance before the overrun.
        CommonInvoiceSeed next = world.commonInvoice("Вторая связь", world.order(world.company("Бар"), boris, 500_00, STATUS_PUBLISHED));
        issue(next);

        Map<String, Object> nextReserve = reserve(invoice(next).get("contractor_allocation_id"));
        assertThat(nextReserve).containsEntry("status", "RESERVED").containsEntry("recipient_type", "MANAGER")
                .containsEntry("specialist_rejection_reason", "INSUFFICIENT_AVAILABLE_BALANCE");
        assertThat(num(nextReserve, "recipient_profile_id")).isEqualTo(world.manager.managerProfileId);
    }

    @Test
    @DisplayName("06 «Выдать номер карты» названному получателю с выключенным допуском → 409, ничего не меняется")
    void s06_namedRecipientWithoutAdmissionIsRefusedAndNothingChanges() {
        Person anna = world.specialist("Анна Специалист");
        Person boris = world.specialist("Борис Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        world.payable(boris, ContractorRole.SPECIALIST, 10_000_00);
        CommonInvoiceSeed invoice = world.commonInvoice("Связь", world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED));
        issue(invoice);
        CommonInvoicePaymentRouteChangeRequest request = cardNumber(invoice, boris.specialistProfileId);
        world.switchAdmissionOff(boris.specialistProfileId);
        Map<String, Object> before = invoice(invoice);

        assertRejected(409, "допуск к приёму оплат выключен",
                () -> recipientChanges.invoice(invoice.invoiceId(), request, world.manager.auth(), false));

        Map<String, Object> after = invoice(invoice);
        assertThat(after).containsEntry("status", "INVOICED");
        for (String column : List.of("contractor_allocation_id", "sent_at", "payment_route_selected_at", "payment_route_type",
                "payment_message_operation_id", "last_error")) {
            assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
        }
        assertThat(reserves(invoice)).hasSize(1);
        assertThat(reserves(invoice).getFirst()).containsEntry("status", "RESERVED");
        assertThat(num(reserves(invoice).getFirst(), "recipient_profile_id")).isEqualTo(anna.specialistProfileId);
        assertThat(queuedClientMessages(invoice)).isEmpty();
        assertThat(clientMessages).hasSize(1);
    }

    @Test
    @DisplayName("07а неоплаченная ссылка Т-Банка → номер карты, принять некому → 409 до отмены в банке, ссылка действует")
    void s07a_unpaidTbankLinkIsKeptWhenNobodyCanTakeTheCardNumber() {
        Person anna = world.specialist("Анна Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 100_00);
        CommonInvoiceSeed invoice = invoiceOnUnpaidTbankLink(anna);
        Map<String, Object> before = invoice(invoice);

        assertRejected(409, "ни специалист, ни менеджер",
                () -> recipientChanges.invoice(invoice.invoiceId(), cardNumber(invoice, null), world.manager.auth(), false));

        verify(tbank, never()).cancel(any(), any());
        assertThat(bank.status((String) before.get("tbank_payment_id"))).isEqualTo("NEW");
        Map<String, Object> after = invoice(invoice);
        assertThat(after).containsEntry("status", "INVOICED").containsEntry("payment_route_type", "BANK_LINK");
        assertThat(after.get("tbank_payment_id")).isEqualTo(before.get("tbank_payment_id"));
        assertThat(after.get("contractor_allocation_id")).isEqualTo(before.get("contractor_allocation_id"));
        assertThat(bankLinks(invoice)).hasSize(1);
        assertThat(bankLinks(invoice).getFirst()).containsEntry("status", "CURRENT");
        assertThat(reserves(invoice)).hasSize(1);
        assertThat(reserves(invoice).getFirst()).containsEntry("status", "OWNER_FALLBACK");
        assertThat(recipientChangeJobs(invoice)).containsExactly("STOPPED");
    }

    @Test
    @DisplayName("07б неоплаченная ссылка Т-Банка → номер карты названному получателю → одна отмена в банке, резерв у названного")
    void s07b_unpaidTbankLinkIsCancelledOnceForANamedRecipient() {
        Person anna = world.specialist("Анна Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 100_00);
        CommonInvoiceSeed invoice = invoiceOnUnpaidTbankLink(anna);
        Map<String, Object> before = invoice(invoice);

        recipientChanges.invoice(invoice.invoiceId(), cardNumber(invoice, anna.specialistProfileId), world.manager.auth(), false);
        deliverQueuedClientMessages();

        verify(tbank, times(1)).cancel(any(), any());
        assertThat(bank.status((String) before.get("tbank_payment_id"))).isEqualTo("CANCELED");
        assertThat(bankLinks(invoice)).hasSize(1);
        assertThat(bankLinks(invoice).getFirst()).containsEntry("status", "CANCELED");
        assertThat(recipientChangeJobs(invoice)).containsExactly("DONE");
        Map<String, Object> after = invoice(invoice);
        assertThat(after).containsEntry("status", "INVOICED").containsEntry("payment_route_type", "MANUAL_MOBILE_BANK")
                .containsEntry("payment_route_manual_source", "CONTRACTOR_PAYMENT_PROFILE");
        assertThat(after.get("tbank_payment_id")).isNull();
        assertThat(reserve(before.get("contractor_allocation_id"))).containsEntry("status", "CANCELED");
        Map<String, Object> reserve = reserve(after.get("contractor_allocation_id"));
        assertThat(reserve).containsEntry("status", "RESERVED").containsEntry("routing_decision_reason", "RECIPIENT_CHOSEN_BY_MANAGER");
        assertThat(num(reserve, "recipient_profile_id")).isEqualTo(anna.specialistProfileId);
        assertThat(num(reserve, "available_before_kopecks")).isEqualTo(100_00);
        assertThat(digits(clientMessages.getLast())).contains(requisites(anna));
    }

    // ================================================================================================
    // Общий счёт: «Отметить оплату»
    // ================================================================================================

    @Test
    @DisplayName("08 оплата получателю из счёта → счёт PAID, резерв CONFIRMED, одна строка поступления")
    void s08_paidToTheInvoiceRecipient() {
        Person anna = world.specialist("Анна Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        long order = world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED);
        CommonInvoiceSeed invoice = world.commonInvoice("Связь", order);
        issue(invoice);
        long reserveId = num(invoice(invoice), "contractor_allocation_id");

        login(world.manager);
        CommonManualPaymentOptionsResponse window = billing.manualPaymentOptions(invoice.invoiceId());
        assertThat(window.remainingKopecks()).isEqualTo(3_000_00);
        assertThat(window.defaultRecipientKey()).isEqualTo(candidate(invoice, anna.specialistProfileId).key());
        billing.markPaidWithAttributions(invoice.invoiceId(),
                receipt("Клиент перевёл на карту из счёта", row("r1", candidate(invoice, anna.specialistProfileId), 3_000_00)),
                world.manager.auth());

        Map<String, Object> paid = invoice(invoice);
        assertThat(paid).containsEntry("status", "PAID").containsEntry("manual_paid_by", world.manager.username);
        assertThat(num(paid, "paid_kopecks")).isEqualTo(3_000_00);
        assertThat(paid.get("paid_at")).isNotNull();
        assertThat(orderStatus(order)).isEqualTo(STATUS_PAID);
        assertThat(jdbc.queryForObject("SELECT paid FROM common_invoice_orders WHERE invoice_id = ?", Boolean.class, invoice.invoiceId())).isTrue();

        Map<String, Object> reserve = reserve(reserveId);
        assertThat(reserve).containsEntry("status", "CONFIRMED");
        assertThat(num(reserve, "confirmed_kopecks")).isEqualTo(3_000_00);
        assertThat(reserves(invoice)).hasSize(1);
        assertThat(actualPaymentReserves(invoice)).isEmpty();

        List<Map<String, Object>> receipts = receipts(invoice);
        assertThat(receipts).hasSize(1);
        assertThat(receipts.getFirst()).containsEntry("accounting_mode", "LIVE").containsEntry("actual_recipient_type", "SPECIALIST")
                .containsEntry("actor", world.manager.username);
        assertThat(num(receipts.getFirst(), "actual_recipient_profile_id")).isEqualTo(anna.specialistProfileId);
        assertThat(num(receipts.getFirst(), "original_allocation_id")).isEqualTo(reserveId);
        assertThat(num(receipts.getFirst(), "amount_kopecks")).isEqualTo(3_000_00);
        assertThat(num(receipts.getFirst(), "projected_overrun_kopecks")).isZero();
        assertThat(paymentNoticeKinds(invoice)).containsExactlyInAnyOrder("CLIENT", "RECIPIENT");
    }

    @Test
    @DisplayName("09 оплата другому кандидату списка → исходный резерв CANCELED, ACTUAL_PAYMENT CONFIRMED у фактического, превышение записано")
    void s09_paidToAnotherCandidateOfTheList() {
        Person anna = world.specialist("Анна Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        world.payable(world.manager, ContractorRole.MANAGER, 1_000_00);
        long order = world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED);
        CommonInvoiceSeed invoice = world.commonInvoice("Связь", order);
        issue(invoice);
        long annaReserve = num(invoice(invoice), "contractor_allocation_id");

        login(world.manager);
        billing.markPaidWithAttributions(invoice.invoiceId(),
                receipt("Клиент перевёл менеджеру", row("r1", candidate(invoice, world.manager.managerProfileId), 3_000_00)),
                world.manager.auth());

        assertThat(invoice(invoice)).containsEntry("status", "PAID");
        assertThat(orderStatus(order)).isEqualTo(STATUS_PAID);
        Map<String, Object> released = reserve(annaReserve);
        assertThat(released).containsEntry("status", "CANCELED");
        assertThat(num(released, "confirmed_kopecks")).isZero();

        List<Map<String, Object>> receipts = receipts(invoice);
        assertThat(receipts).hasSize(1);
        Map<String, Object> receipt = receipts.getFirst();
        assertThat(receipt).containsEntry("original_recipient_type", "SPECIALIST").containsEntry("actual_recipient_type", "MANAGER");
        assertThat(num(receipt, "original_recipient_profile_id")).isEqualTo(anna.specialistProfileId);
        assertThat(num(receipt, "actual_recipient_profile_id")).isEqualTo(world.manager.managerProfileId);
        assertThat(num(receipt, "amount_kopecks")).isEqualTo(3_000_00);
        // The manager's balance was short: the money is a fact, the overrun is recorded with it.
        assertThat(num(receipt, "available_before_kopecks")).isLessThan(3_000_00);
        assertThat(num(receipt, "projected_overrun_kopecks")).isPositive()
                .isEqualTo(3_000_00 - num(receipt, "available_before_kopecks"));

        List<Map<String, Object>> actual = actualPaymentReserves(invoice);
        assertThat(actual).hasSize(1);
        assertThat(actual.getFirst()).containsEntry("status", "CONFIRMED").containsEntry("recipient_type", "MANAGER");
        assertThat(num(actual.getFirst(), "recipient_profile_id")).isEqualTo(world.manager.managerProfileId);
        assertThat(num(actual.getFirst(), "amount_kopecks")).isEqualTo(3_000_00);
        assertThat(num(actual.getFirst(), "confirmed_kopecks")).isEqualTo(3_000_00);
    }

    @Test
    @DisplayName("10а оплата разовому «другому получателю» из команды менеджера (не из заказов счёта, допуск выключен) → принята")
    void s10a_paidToAOneOffRecipientOfTheManagersTeam() {
        Person anna = world.specialist("Анна Специалист");
        Person vera = world.specialist("Вера Из Команды");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        // Vera's admission is off: no invoice may be issued to her, but money she received must stay attributable.
        world.profile(vera, ContractorRole.SPECIALIST, 10_000_00, false, false);
        long order = world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED);
        CommonInvoiceSeed invoice = world.commonInvoice("Связь", order);
        issue(invoice);
        long annaReserve = num(invoice(invoice), "contractor_allocation_id");

        login(world.manager);
        // Vera is not offered in the ordinary list, only behind «+ Другой получатель».
        assertThat(billing.manualPaymentOptions(invoice.invoiceId()).candidates())
                .noneMatch(candidate -> vera.specialistProfileId.equals(candidate.recipientProfileId()));
        var other = billing.manualPaymentOtherRecipients(invoice.invoiceId()).recipients().stream()
                .filter(item -> vera.specialistProfileId.equals(item.candidate().recipientProfileId())).findFirst().orElseThrow();
        assertThat(other.teamMember()).isTrue();
        assertThat(other.candidate().profileEnabled()).isFalse();

        billing.markPaidWithAttributions(invoice.invoiceId(),
                receipt("Клиент перевёл другому специалисту команды", row("r1", other.candidate(), 3_000_00)), world.manager.auth());

        assertThat(invoice(invoice)).containsEntry("status", "PAID");
        assertThat(reserve(annaReserve)).containsEntry("status", "CANCELED");
        List<Map<String, Object>> receipts = receipts(invoice);
        assertThat(receipts).hasSize(1);
        assertThat(num(receipts.getFirst(), "actual_recipient_profile_id")).isEqualTo(vera.specialistProfileId);
        assertThat(num(receipts.getFirst(), "original_recipient_profile_id")).isEqualTo(anna.specialistProfileId);
        List<Map<String, Object>> actual = actualPaymentReserves(invoice);
        assertThat(actual).hasSize(1);
        assertThat(actual.getFirst()).containsEntry("status", "CONFIRMED");
        assertThat(num(actual.getFirst(), "recipient_profile_id")).isEqualTo(vera.specialistProfileId);
        // The invoice keeps its recipient: the one-off person is recorded for this receipt only.
        assertThat(num(invoice(invoice), "contractor_allocation_id")).isEqualTo(annaReserve);
    }

    @Test
    @DisplayName("10б оплата профилю вне команды и вне компаний счёта → 409, счёт не оплачен")
    void s10b_paidToAProfileOutsideTheTeamAndCompaniesIsRefused() {
        Person anna = world.specialist("Анна Специалист");
        Person oleg = world.outsiderSpecialist("Олег Чужой");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        world.payable(oleg, ContractorRole.SPECIALIST, 10_000_00);
        long order = world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED);
        CommonInvoiceSeed invoice = world.commonInvoice("Связь", order);
        issue(invoice);

        login(world.manager);
        assertThat(billing.manualPaymentOtherRecipients(invoice.invoiceId()).recipients())
                .noneMatch(item -> oleg.specialistProfileId.equals(item.candidate().recipientProfileId()));
        CommonManualPaymentAttributionRequest request = receipt("Клиент перевёл постороннему",
                new CommonManualPaymentAttributionRowRequest("r1", ContractorRecipientType.SPECIALIST, oleg.specialistProfileId, 3_000_00));

        assertRejected(409, "Платёжный маршрут изменился",
                () -> billing.markPaidWithAttributions(invoice.invoiceId(), request, world.manager.auth()));

        assertUnpaidAndUntouched(invoice, order);
    }

    enum OwnerDecision { ACCEPTS, REJECTS }

    @ParameterizedTest(name = "11 менеджер сообщает «деньги получил владелец» → запрос владельцу, счёт не оплачен; владелец {0}")
    @EnumSource(OwnerDecision.class)
    void s11_ownerReceiptReportedByAManagerWaitsForTheOwner(OwnerDecision decision) {
        Person anna = world.specialist("Анна Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        long order = world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED);
        CommonInvoiceSeed invoice = world.commonInvoice("Связь", order);
        issue(invoice);
        long annaReserve = num(invoice(invoice), "contractor_allocation_id");

        login(world.manager);
        billing.markPaidWithAttributions(invoice.invoiceId(),
                receipt("Клиент перевёл владельцу", row("r1", candidate(invoice, null), 3_000_00)), world.manager.auth());

        // Staged, not credited.
        Map<String, Object> report = jdbc.queryForMap("SELECT * FROM common_owner_receipt_approval WHERE invoice_id = ?", invoice.invoiceId());
        long reportId = num(report, "id");
        assertThat(num(report, "pending_invoice_id")).isEqualTo(invoice.invoiceId());
        assertThat(report).containsEntry("reported_by", world.manager.username);
        assertThat(report.get("decision")).isNull();
        assertUnpaidAndUntouched(invoice, order);
        assertThat(openReminders("COMMON_INVOICE_OWNER_RECEIPT_APPROVAL", reportId)).contains(world.owner.userId, world.admin.userId);

        login(world.owner);
        CommonManualPaymentOptionsResponse.OwnerReport waiting = billing.manualPaymentOptions(invoice.invoiceId()).ownerReport();
        assertThat(waiting.id()).isEqualTo(reportId);
        assertThat(waiting.canDecide()).isTrue();

        if (decision == OwnerDecision.ACCEPTS) {
            billing.markPaidWithAttributions(invoice.invoiceId(), waiting.request(), world.owner.auth());

            assertThat(invoice(invoice)).containsEntry("status", "PAID");
            assertThat(orderStatus(order)).isEqualTo(STATUS_PAID);
            assertThat(jdbc.queryForMap("SELECT * FROM common_owner_receipt_approval WHERE id = ?", reportId))
                    .containsEntry("decision", "ACCEPTED").containsEntry("decided_by", world.owner.username)
                    .containsEntry("pending_invoice_id", null);
            assertThat(reserve(annaReserve)).containsEntry("status", "CANCELED");
            assertThat(receipts(invoice)).hasSize(1);
            assertThat(receipts(invoice).getFirst()).containsEntry("actual_recipient_type", "OWNER");
            assertThat(receipts(invoice).getFirst().get("actual_recipient_profile_id")).isNull();
            assertThat(actualPaymentReserves(invoice)).isEmpty();
        } else {
            billing.rejectOwnerReport(invoice.invoiceId(), reportId, world.owner.auth());

            assertThat(jdbc.queryForMap("SELECT * FROM common_owner_receipt_approval WHERE id = ?", reportId))
                    .containsEntry("decision", "REJECTED").containsEntry("decided_by", world.owner.username)
                    .containsEntry("pending_invoice_id", null);
            assertUnpaidAndUntouched(invoice, order);
            assertThat(openReminders("COMMON_INVOICE_OWNER_RECEIPT_REJECTED", reportId)).containsExactly(world.manager.userId);
        }
        // The decision is made: nobody is asked for it any more.
        assertThat(openReminders("COMMON_INVOICE_OWNER_RECEIPT_APPROVAL", reportId)).isEmpty();
    }

    @Test
    @DisplayName("12а частичная сумма → 400, счёт и заказ не тронуты")
    void s12a_partialAmountIsRefused() {
        Person anna = world.specialist("Анна Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        long order = world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED);
        CommonInvoiceSeed invoice = world.commonInvoice("Связь", order);
        issue(invoice);

        login(world.manager);
        CommonManualPaymentAttributionRequest request = receipt("Клиент перевёл часть",
                row("r1", candidate(invoice, anna.specialistProfileId), 1_000_00));

        assertRejected(400, "должна быть равна остатку",
                () -> billing.markPaidWithAttributions(invoice.invoiceId(), request, world.manager.auth()));

        assertUnpaidAndUntouched(invoice, order);
    }

    @Test
    @DisplayName("12б остаток, разделённый между двумя получателями → зачислено обоим")
    void s12b_remainingAmountSplitBetweenTwoRecipientsCreditsBoth() {
        Person anna = world.specialist("Анна Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        world.payable(world.manager, ContractorRole.MANAGER, 10_000_00);
        long order = world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED);
        CommonInvoiceSeed invoice = world.commonInvoice("Связь", order);
        issue(invoice);
        long annaReserve = num(invoice(invoice), "contractor_allocation_id");

        login(world.manager);
        billing.markPaidWithAttributions(invoice.invoiceId(), receipt("Клиент перевёл двум людям",
                row("r1", candidate(invoice, anna.specialistProfileId), 2_000_00),
                row("r2", candidate(invoice, world.manager.managerProfileId), 1_000_00)), world.manager.auth());

        Map<String, Object> paid = invoice(invoice);
        assertThat(paid).containsEntry("status", "PAID");
        assertThat(num(paid, "paid_kopecks")).isEqualTo(3_000_00);
        assertThat(orderStatus(order)).isEqualTo(STATUS_PAID);
        List<Map<String, Object>> receipts = receipts(invoice);
        assertThat(receipts).hasSize(2);
        assertThat(receipts).extracting(receipt -> num(receipt, "actual_recipient_profile_id"), receipt -> num(receipt, "amount_kopecks"))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(anna.specialistProfileId, 2_000_00L),
                        org.assertj.core.groups.Tuple.tuple(world.manager.managerProfileId, 1_000_00L));
        // A split is a different distribution than the one reserved: the reserve of 3000 is released,
        // each person is credited exactly what he received.
        assertThat(reserve(annaReserve)).containsEntry("status", "CANCELED");
        List<Map<String, Object>> actual = actualPaymentReserves(invoice);
        assertThat(actual).hasSize(2).allSatisfy(row -> assertThat(row).containsEntry("status", "CONFIRMED"));
        assertThat(actual).extracting(row -> num(row, "recipient_profile_id"), row -> num(row, "confirmed_kopecks"))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(anna.specialistProfileId, 2_000_00L),
                        org.assertj.core.groups.Tuple.tuple(world.manager.managerProfileId, 1_000_00L));
        assertThat(paymentNoticeKinds(invoice)).containsExactlyInAnyOrder("CLIENT", "RECIPIENT", "RECIPIENT");
    }

    // ================================================================================================
    // Счёт одного заказа
    // ================================================================================================

    @Test
    @DisplayName("13 счёт заказа: специалист с резервом → его реквизиты, резерв RESERVED")
    void s13_orderInvoiceIsIssuedToTheSpecialist() {
        Person anna = world.specialist("Анна Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        world.payable(world.manager, ContractorRole.MANAGER, 10_000_00);
        long order = world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED);

        login(world.manager);
        ManagerPaymentLinkResponse issued = payments.createForOrderAuthorized(order, world.manager.auth());

        assertThat(issued.amountKopecks()).isEqualTo(3_000_00);
        Map<String, Object> link = currentLink(order);
        assertThat(link).containsEntry("status", "WAITING_MANUAL_PAYMENT").containsEntry("payment_method", "MANUAL_MOBILE_BANK")
                .containsEntry("manual_source", "CONTRACTOR_PAYMENT_PROFILE").containsEntry("token", issued.token());
        assertThat(num(link, "amount_kopecks")).isEqualTo(3_000_00);
        Map<String, Object> reserve = reserve(link.get("contractor_allocation_id"));
        assertThat(reserve).containsEntry("mode", "LIVE").containsEntry("source_type", "PAYMENT_LINK")
                .containsEntry("status", "RESERVED").containsEntry("recipient_type", "SPECIALIST")
                .containsEntry("routing_decision_reason", "SPECIALIST_SELECTED");
        assertThat(num(reserve, "source_id")).isEqualTo(num(link, "id"));
        assertThat(num(reserve, "order_id")).isEqualTo(order);
        assertThat(num(reserve, "recipient_profile_id")).isEqualTo(anna.specialistProfileId);
        assertThat(num(reserve, "amount_kopecks")).isEqualTo(3_000_00);
        assertThat(digits(cipher.decrypt((String) reserve.get("payment_phone_snapshot")))).isEqualTo(requisites(anna));
        assertThat(digits(issued.copyText())).contains(requisites(anna));
    }

    @Test
    @DisplayName("14 счёт заказа: «Выдать номер карты» названному получателю сверх резерва → новый счёт и резерв у названного")
    void s14_orderInvoiceRouteChangeToANamedRecipientAboveBalance() {
        Person anna = world.specialist("Анна Специалист");
        Person boris = world.specialist("Борис Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        world.payable(boris, ContractorRole.SPECIALIST, 1_000_00);
        long order = world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED);
        login(world.manager);
        payments.createForOrderAuthorized(order, world.manager.auth());
        Map<String, Object> firstLink = currentLink(order);

        PaymentRouteChangeResponse changed = orderRecipientChanges.method(order, new PaymentRouteChangeRequest(
                num(firstLink, "id"), PaymentRouteChangeTarget.EMPLOYEE_REQUISITES, true, null, null, boris.specialistProfileId),
                world.manager.auth());

        assertThat(changed.previousPaymentLinkId()).isEqualTo(num(firstLink, "id"));
        assertThat(changed.paymentLinkId()).isNotEqualTo(num(firstLink, "id"));
        assertThat(link(num(firstLink, "id")).get("status")).isIn("CANCELED", "EXPIRED");
        assertThat(reserve(firstLink.get("contractor_allocation_id")).get("status")).isIn("CANCELED", "EXPIRED");
        Map<String, Object> newLink = link(changed.paymentLinkId());
        assertThat(newLink).containsEntry("status", "WAITING_MANUAL_PAYMENT").containsEntry("payment_method", "MANUAL_MOBILE_BANK")
                .containsEntry("manual_source", "CONTRACTOR_PAYMENT_PROFILE");
        assertThat(num(newLink, "order_id")).isEqualTo(order);
        Map<String, Object> reserve = reserve(newLink.get("contractor_allocation_id"));
        assertThat(reserve).containsEntry("status", "RESERVED").containsEntry("routing_decision_reason", "RECIPIENT_CHOSEN_BY_MANAGER");
        assertThat(num(reserve, "recipient_profile_id")).isEqualTo(boris.specialistProfileId);
        assertThat(num(reserve, "amount_kopecks")).isEqualTo(3_000_00);
        assertThat(num(reserve, "available_before_kopecks")).isEqualTo(1_000_00);
    }

    @Test
    @DisplayName("15 счёт заказа: ручная отметка оплаты другому получателю → заказ оплачен, резерв перенесён на фактического")
    void s15_orderInvoiceMarkedPaidToAnotherRecipient() {
        Person anna = world.specialist("Анна Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        world.payable(world.manager, ContractorRole.MANAGER, 10_000_00);
        long order = world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED);
        login(world.manager);
        payments.createForOrderAuthorized(order, world.manager.auth());
        Map<String, Object> issuedLink = currentLink(order);
        long annaReserve = num(issuedLink, "contractor_allocation_id");

        ManualCardPaymentRecipientResponse actualRecipient = payments.manualCardPaymentContextForOrder(order, world.manager.auth())
                .candidates().stream()
                .filter(candidate -> world.manager.managerProfileId.equals(candidate.recipientProfileId())).findFirst().orElseThrow();
        ManagerManualCardPaymentResultResponse result = payments.submitManagerManualCardPaymentForOrder(order,
                "Клиент перевёл менеджеру", null, actualRecipient.recipientType(), actualRecipient.recipientProfileId(),
                actualRecipient.key(), world.manager.username, world.manager.auth());

        assertThat(result.status()).isEqualTo(ManagerManualCardPaymentResultResponse.COMPLETED);
        assertThat(orderStatus(order)).isEqualTo(STATUS_PAID);
        assertThat(reserve(annaReserve)).containsEntry("status", "CANCELED");
        Map<String, Object> paidLink = link(result.paymentLinkId());
        assertThat(paidLink).containsEntry("status", "CONFIRMED").containsEntry("manual_actual_recipient_type", "MANAGER");
        assertThat(num(paidLink, "manual_actual_recipient_profile_id")).isEqualTo(world.manager.managerProfileId);
        List<Map<String, Object>> receipts = jdbc.queryForList(
                "SELECT * FROM contractor_actual_payment_attributions WHERE order_id = ? ORDER BY id", order);
        assertThat(receipts).hasSize(1);
        assertThat(receipts.getFirst()).containsEntry("source_kind", "PAYMENT_LINK").containsEntry("accounting_mode", "LIVE");
        assertThat(num(receipts.getFirst(), "original_recipient_profile_id")).isEqualTo(anna.specialistProfileId);
        assertThat(num(receipts.getFirst(), "actual_recipient_profile_id")).isEqualTo(world.manager.managerProfileId);
        assertThat(num(receipts.getFirst(), "amount_kopecks")).isEqualTo(3_000_00);
        List<Map<String, Object>> actual = jdbc.queryForList("""
                SELECT * FROM contractor_payment_allocations
                WHERE mode = 'LIVE' AND source_type = 'ACTUAL_PAYMENT' AND source_id = ?
                """, num(receipts.getFirst(), "id"));
        assertThat(actual).hasSize(1);
        assertThat(actual.getFirst()).containsEntry("status", "CONFIRMED");
        assertThat(num(actual.getFirst(), "recipient_profile_id")).isEqualTo(world.manager.managerProfileId);
        assertThat(num(actual.getFirst(), "confirmed_kopecks")).isEqualTo(3_000_00);
    }

    // ================================================================================================
    // Уведомления и автоматика
    // ================================================================================================

    @Test
    @DisplayName("16 LIVE-поступление человеку → уведомление получателю в outbox; спор в течение 24 часов фиксируется один раз")
    void s16_recipientNoticeAndASingleDispute() {
        Person anna = world.specialist("Анна Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        long order = world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED);
        CommonInvoiceSeed invoice = world.commonInvoice("Связь", order);
        issue(invoice);
        login(world.manager);
        billing.markPaidWithAttributions(invoice.invoiceId(),
                receipt("Клиент перевёл на карту из счёта", row("r1", candidate(invoice, anna.specialistProfileId), 3_000_00)),
                world.manager.auth());

        Map<String, Object> notice = jdbc.queryForMap("""
                SELECT * FROM common_invoice_payment_notification_outbox WHERE invoice_id = ? AND notification_kind = 'RECIPIENT'
                """, invoice.invoiceId());
        long deliveryId = num(notice, "delivery_id");
        assertThat(notice).containsEntry("recipient_type", "SPECIALIST").containsEntry("actor", world.manager.username);
        assertThat(num(notice, "recipient_user_id")).isEqualTo(anna.userId);
        assertThat(num(notice, "amount_kopecks")).isEqualTo(3_000_00);
        assertThat(num(notice, "attribution_id")).isEqualTo(num(receipts(invoice).getFirst(), "id"));
        assertThat(notice.get("sent_at")).isNull();

        // The outbox worker delivers the notice to the specialist's work group, with the dispute button.
        SecurityContextHolder.clearContext();
        paymentNotices.retryPendingNotifications();

        verify(staffTelegram).sendMessageWithInlineButton(eq(anna.groupChatId.longValue()), anyString(), anyString(),
                eq(CommonInvoicePaymentDisputeService.CALLBACK_PREFIX + deliveryId));
        assertThat(recipientNotice(deliveryId).get("sent_at")).isNotNull();
        assertThat(recipientNotice(deliveryId).get("disputed_at")).isNull();

        disputes.dispute(deliveryId, anna.telegramChatId, anna.groupChatId);

        Map<String, Object> disputed = recipientNotice(deliveryId);
        assertThat(disputed.get("disputed_at")).isNotNull();
        assertThat(num(disputed, "disputed_by_telegram_user_id")).isEqualTo(anna.telegramChatId);
        List<Long> told = openReminders(CommonInvoicePaymentDisputeService.DISPUTE_ISSUE, deliveryId);
        assertThat(told).contains(world.manager.userId, world.owner.userId, world.admin.userId);
        // The receipt stays credited: a dispute is a task for people, not a reversal.
        assertThat(invoice(invoice)).containsEntry("status", "PAID");
        assertThat(reserve(invoice(invoice).get("contractor_allocation_id"))).containsEntry("status", "CONFIRMED");

        disputes.dispute(deliveryId, anna.telegramChatId, anna.groupChatId);

        assertThat(recipientNotice(deliveryId).get("disputed_at")).isEqualTo(disputed.get("disputed_at"));
        assertThat(openReminders(CommonInvoicePaymentDisputeService.DISPUTE_ISSUE, deliveryId)).hasSameSizeAs(told);
    }

    @ParameterizedTest(name = "17 просроченный общий счёт, боевой перевод включён: {0} → статус {1}")
    @CsvSource({"true, UNPAID", "false, INVOICED"})
    void s17_overdueCommonInvoiceMovesToUnpaidOnlyWithTheLiveSwitch(boolean liveEnabled, String expectedStatus) {
        Person anna = world.specialist("Анна Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        long order = world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED);
        CommonInvoiceSeed invoice = world.commonInvoice("Связь", order);
        issue(invoice);
        settings.setBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_LIVE_ENABLED, liveEnabled);
        try {
            // The payment term (30 days by default) has run out: the invoice was sent 31 days ago.
            LocalDateTime now = LocalDateTime.now();
            jdbc.update("UPDATE common_invoices SET sent_at = ? WHERE invoice_id = ?", now.minusDays(31), invoice.invoiceId());

            // Background jobs run without a logged-in user.
            SecurityContextHolder.clearContext();
            // The staff notification job warns the manager's group and records the notice it sent.
            assertThat(overdueNotices.pendingNotices(now)).filteredOn(item -> item.invoiceId() == invoice.invoiceId())
                    .hasSize(liveEnabled ? 1 : 0);
            staffNotices.dispatchCommonInvoiceOverdueNotices();
            verify(staffTelegram, times(liveEnabled ? 1 : 0)).sendMessageWithInlineKeyboard(
                    eq(world.manager.groupChatId.longValue()), anyString(), anyString(), any());
            assertThat(invoice(invoice).get("overdue_notice_at") != null).isEqualTo(liveEnabled);

            // Inside the notice period nothing moves.
            overdue.escalate();
            assertThat(invoice(invoice)).containsEntry("status", "INVOICED");

            // The notice period (2 days) has passed.
            jdbc.update("UPDATE common_invoices SET overdue_notice_at = ? WHERE invoice_id = ? AND overdue_notice_at IS NOT NULL",
                    now.minusHours(49), invoice.invoiceId());
            overdue.escalate();

            assertThat(invoice(invoice)).containsEntry("status", expectedStatus);
            assertThat(orderStatus(order)).isEqualTo(liveEnabled ? STATUS_UNPAID : STATUS_INVOICED);
            assertThat(jdbc.queryForObject("SELECT unpaid FROM common_invoice_orders WHERE invoice_id = ?", Boolean.class, invoice.invoiceId()))
                    .isEqualTo(liveEnabled);
        } finally {
            settings.setBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_LIVE_ENABLED, false);
        }
    }

    // ================================================================================================
    // «Выдать ссылку банка»
    // ================================================================================================

    @Test
    @DisplayName("18 общий счёт: «Выдать ссылку банка» вместо номера карты → резерв сотрудника CANCELED, владелец с причиной CLIENT_REQUEST_OWNER_TBANK, клиенту новая ссылка; оплата по ссылке → PAID")
    void s18_ownersBankLinkReplacesTheCardNumberAndIsPaidThroughTheBank() {
        Person anna = world.specialist("Анна Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        long order = world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED);
        CommonInvoiceSeed invoice = world.commonInvoice("Связь", order);
        issue(invoice);
        Map<String, Object> issued = invoice(invoice);
        assertThat(issued).containsEntry("payment_route_type", "MANUAL_MOBILE_BANK");
        long cardNumberReserve = num(issued, "contractor_allocation_id");
        settings.setBoolean(AppSettingService.PAYMENTS_TBANK_APPLY_CONFIRMED_PAYMENTS, true);
        try {
            // The manager presses «Выдать ссылку банка»: the service call the page's request ends in.
            login(world.manager);
            var window = billing.commonInvoicePaymentRouteChangeContext(invoice.invoiceId());
            billing.changeCommonInvoicePaymentRoute(invoice.invoiceId(), new CommonInvoicePaymentRouteChangeRequest(
                    CommonInvoicePaymentRouteChangeTarget.OWNER_TBANK, true, window.paymentEvidenceToken(), null, null),
                    world.manager.auth());
            deliverQueuedClientMessages();

            Map<String, Object> changed = invoice(invoice);
            assertThat(changed).containsEntry("status", "INVOICED").containsEntry("payment_route_type", "BANK_LINK");
            assertThat(changed.get("sent_at")).isNotNull();
            assertThat(reserve(cardNumberReserve)).containsEntry("status", "CANCELED");
            long ownerReserve = num(changed, "contractor_allocation_id");
            assertThat(reserve(ownerReserve)).containsEntry("recipient_type", "OWNER").containsEntry("status", "OWNER_FALLBACK")
                    .containsEntry("routing_decision_reason", "CLIENT_REQUEST_OWNER_TBANK");
            assertThat(reserve(ownerReserve).get("recipient_profile_id")).isNull();
            assertThat(num(reserve(ownerReserve), "amount_kopecks")).isEqualTo(3_000_00);
            // The client is told at once and no longer sees the employee's card number.
            assertThat(clientMessages).hasSize(2);
            assertThat(clientMessages.getLast()).contains("Способ оплаты изменён").contains(invoice.token());
            assertThat(digits(clientMessages.getLast())).doesNotContain(requisites(anna));
            // The bank is asked for a link only when the client opens the invoice page.
            verify(tbank, never()).init(any(), any());

            SecurityContextHolder.clearContext();
            billing.initPublicPayment(invoice.token(), "client@scenario.test", true, true, true);
            verify(tbank, times(1)).init(any(), any());
            String paymentId = (String) invoice(invoice).get("tbank_payment_id");
            assertThat(bank.status(paymentId)).isEqualTo("NEW");

            // The client pays; the bank's signed notification goes to the handler the webhook controller calls.
            payments.handleTbankWebhook(bank.confirmed(paymentId, TERMINAL_PASSWORD));

            Map<String, Object> paid = invoice(invoice);
            assertThat(paid).containsEntry("status", "PAID");
            assertThat(num(paid, "paid_kopecks")).isEqualTo(3_000_00);
            assertThat(reserve(ownerReserve)).containsEntry("status", "CONFIRMED");
            assertThat(num(reserve(ownerReserve), "confirmed_kopecks")).isEqualTo(3_000_00);
            assertThat(reserve(cardNumberReserve)).containsEntry("status", "CANCELED");
            assertThat(orderStatus(order)).isEqualTo(STATUS_PAID);
        } finally {
            settings.setBoolean(AppSettingService.PAYMENTS_TBANK_APPLY_CONFIRMED_PAYMENTS, false);
        }
    }

    @Test
    @DisplayName("19 счёт заказа: «Выдать ссылку банка» вместо номера карты → старый счёт закрыт, владелец с причиной CLIENT_REQUEST_OWNER_TBANK, клиенту уходит новый счёт; оплата по ссылке → заказ оплачен")
    void s19_orderInvoiceSwitchedToTheOwnersBankLinkAndPaidThroughTheBank() {
        Person anna = world.specialist("Анна Специалист");
        world.payable(anna, ContractorRole.SPECIALIST, 10_000_00);
        long order = world.order(world.company("Кафе"), anna, 3_000_00, STATUS_PUBLISHED);
        login(world.manager);
        payments.createForOrderAuthorized(order, world.manager.auth());
        Map<String, Object> cardLink = currentLink(order);
        assertThat(cardLink).containsEntry("status", "WAITING_MANUAL_PAYMENT");
        settings.setBoolean(AppSettingService.PAYMENTS_TBANK_APPLY_CONFIRMED_PAYMENTS, true);
        settings.setString(AppSettingService.PAYMENTS_TBANK_RUNTIME_MODE, "LIVE");
        try {
            // The window names the owner's bank the manager sees; the request carries it back, as the page does.
            var window = orderRouteChanges.context(order, world.manager.auth());
            assertThat(window.canChange()).as(window.blockReason()).isTrue();
            PaymentRouteChangeResponse changed = orderRouteChanges.change(order, new PaymentRouteChangeRequest(
                    num(cardLink, "id"), PaymentRouteChangeTarget.OWNER_TBANK, true, window.expectedTargetPaymentProfileId(), null, null),
                    world.manager.auth());

            assertThat(changed.previousPaymentLinkId()).isEqualTo(num(cardLink, "id"));
            assertThat(changed.paymentLinkId()).isNotEqualTo(num(cardLink, "id"));
            assertThat(link(num(cardLink, "id")).get("status")).isIn("CANCELED", "EXPIRED");
            assertThat(reserve(cardLink.get("contractor_allocation_id")).get("status")).isIn("CANCELED", "EXPIRED");
            Map<String, Object> bankLink = link(changed.paymentLinkId());
            assertThat(bankLink).containsEntry("status", "CREATED");
            assertThat(num(bankLink, "order_id")).isEqualTo(order);
            long ownerReserve = num(bankLink, "contractor_allocation_id");
            assertThat(reserve(ownerReserve)).containsEntry("recipient_type", "OWNER").containsEntry("status", "OWNER_FALLBACK")
                    .containsEntry("routing_decision_reason", "CLIENT_REQUEST_OWNER_TBANK");
            assertThat(num(reserve(ownerReserve), "amount_kopecks")).isEqualTo(3_000_00);
            // The new invoice for the client is queued in the route-change outbox.
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_route_change_notification_outbox WHERE payment_link_id = ?",
                    Long.class, changed.paymentLinkId())).isEqualTo(1L);
            verify(tbank, never()).init(any(), any());

            // The client opens the link and presses «Оплатить», then pays.
            SecurityContextHolder.clearContext();
            payments.init((String) bankLink.get("token"), "client@scenario.test", true, true, true, "127.0.0.1", "scenario");
            verify(tbank, times(1)).init(any(), any());
            String paymentId = (String) link(changed.paymentLinkId()).get("tbank_payment_id");
            assertThat(bank.status(paymentId)).isEqualTo("NEW");
            payments.handleTbankWebhook(bank.confirmed(paymentId, TERMINAL_PASSWORD));

            assertThat(link(changed.paymentLinkId())).containsEntry("status", "CONFIRMED");
            assertThat(orderStatus(order)).isEqualTo(STATUS_PAID);
            // The owner's reserve of an order is closed by the reconciliation job (once a minute on production).
            reserveReconciliation.reconcile();
            assertThat(reserve(ownerReserve)).containsEntry("status", "CONFIRMED");
            assertThat(num(reserve(ownerReserve), "confirmed_kopecks")).isEqualTo(3_000_00);
        } finally {
            settings.setString(AppSettingService.PAYMENTS_TBANK_RUNTIME_MODE, "TEST");
            settings.setBoolean(AppSettingService.PAYMENTS_TBANK_APPLY_CONFIRMED_PAYMENTS, false);
        }
    }

    // ================================================================================================
    // Steps
    // ================================================================================================

    private void login(Person person) {
        SecurityContextHolder.getContext().setAuthentication(person.auth());
    }

    /** The manager presses «Счёт»; the delivery worker then sends the frozen message to the client chat. */
    private void issue(CommonInvoiceSeed invoice) {
        login(world.manager);
        // The routing switch reads every gate fresh and fails closed: on a loaded machine one slow read answers
        // «временно недоступен» and nothing is issued. The manager presses «Счёт» again; so does the scenario.
        for (int attempt = 1; ; attempt++) {
            try {
                billing.sendInvoice(invoice.invoiceId(), true);
                break;
            } catch (org.springframework.web.server.ResponseStatusException refused) {
                if (attempt == 5 || refused.getReason() == null || !refused.getReason().contains("временно недоступен")) {
                    throw refused;
                }
                try {
                    Thread.sleep(400);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw refused;
                }
            }
        }
        deliverQueuedClientMessages();
    }

    /** One pass of the common-invoice delivery worker (its timer is off in this context); it runs without a user. */
    private void deliverQueuedClientMessages() {
        var user = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.clearContext();
        try {
            // The worker claims what is due by the database clock, while the message was queued by the JVM clock.
            // Here they are two machines (the test JVM and the database container): a message queued "now" can be
            // a moment in the future for the database. Wait for it as the periodic worker would, instead of racing.
            for (int pass = 0; pass < 25; pass++) {
                clientMessageWorker.tick();
                Integer dueSoon = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM common_invoice_message_queue
                        WHERE state IN ('QUEUED', 'RETRYABLE') AND next_attempt_at <= CURRENT_TIMESTAMP(6) + INTERVAL 3 SECOND
                        """, Integer.class);
                if (dueSoon == null || dueSoon == 0) {
                    break;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            if (user != null) {
                SecurityContextHolder.getContext().setAuthentication(user);
            }
        }
    }

    /** The «Выдать номер карты» request as the window sends it: fresh evidence token, optional named recipient. */
    private CommonInvoicePaymentRouteChangeRequest cardNumber(CommonInvoiceSeed invoice, Long recipientProfileId) {
        login(world.manager);
        var window = billing.commonInvoicePaymentRouteChangeContext(invoice.invoiceId());
        // An open bank link is changed through the bank handover; any other route directly.
        assertThat(window.canChange() || window.bankRecipientChangeAvailable()).as(window.blockReason()).isTrue();
        return new CommonInvoicePaymentRouteChangeRequest(CommonInvoicePaymentRouteChangeTarget.EMPLOYEE_REQUISITES, true,
                window.paymentEvidenceToken(), null, recipientProfileId);
    }

    /** Nobody can take the invoice, so it goes to the owner's bank link; the client opens the link but does not pay. */
    private CommonInvoiceSeed invoiceOnUnpaidTbankLink(Person specialist) {
        CommonInvoiceSeed invoice = world.commonInvoice("Связь",
                world.order(world.company("Кафе"), specialist, 3_000_00, STATUS_PUBLISHED));
        issue(invoice);
        SecurityContextHolder.clearContext();
        billing.initPublicPayment(invoice.token(), "client@scenario.test", true, true, true);
        assertThat(invoice(invoice)).containsEntry("payment_route_type", "BANK_LINK");
        assertThat(bankLinks(invoice)).hasSize(1);
        login(world.manager);
        return invoice;
    }

    private CommonManualPaymentRecipientCandidateResponse candidate(CommonInvoiceSeed invoice, Long profileId) {
        return billing.manualPaymentOptions(invoice.invoiceId()).candidates().stream()
                .filter(candidate -> profileId == null
                        ? candidate.recipientType() == ContractorRecipientType.OWNER && candidate.manualPaymentTaskId() == null
                        : profileId.equals(candidate.recipientProfileId()))
                .findFirst().orElseThrow();
    }

    private static CommonManualPaymentAttributionRowRequest row(String rowKey, CommonManualPaymentRecipientCandidateResponse candidate,
                                                                long amountKopecks) {
        return new CommonManualPaymentAttributionRowRequest(rowKey, candidate.key(), candidate.recipientType(),
                candidate.recipientProfileId(), amountKopecks);
    }

    private static CommonManualPaymentAttributionRequest receipt(String reason, CommonManualPaymentAttributionRowRequest... rows) {
        return new CommonManualPaymentAttributionRequest("scn-" + UUID.randomUUID().toString().substring(0, 24), true, true,
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS), reason, null, List.of(rows));
    }

    // ================================================================================================
    // Rows
    // ================================================================================================

    private Map<String, Object> invoice(CommonInvoiceSeed invoice) {
        return jdbc.queryForMap("SELECT * FROM common_invoices WHERE invoice_id = ?", invoice.invoiceId());
    }

    private Map<String, Object> reserve(Object allocationId) {
        assertThat(allocationId).as("contractor allocation id").isNotNull();
        return jdbc.queryForMap("SELECT * FROM contractor_payment_allocations WHERE id = ?", allocationId);
    }

    /** LIVE reserves of the invoice itself, in the order they were issued. */
    private List<Map<String, Object>> reserves(CommonInvoiceSeed invoice) {
        return jdbc.queryForList("""
                SELECT * FROM contractor_payment_allocations
                WHERE mode = 'LIVE' AND source_type = 'COMMON_INVOICE' AND source_id = ? ORDER BY attempt_no, id
                """, invoice.invoiceId());
    }

    /** LIVE credits of people who actually received the money of this invoice. */
    private List<Map<String, Object>> actualPaymentReserves(CommonInvoiceSeed invoice) {
        return jdbc.queryForList("""
                SELECT allocation.* FROM contractor_payment_allocations allocation
                JOIN contractor_actual_payment_attributions attribution ON attribution.id = allocation.source_id
                WHERE allocation.mode = 'LIVE' AND allocation.source_type = 'ACTUAL_PAYMENT'
                  AND attribution.source_kind = 'COMMON_INVOICE' AND attribution.common_invoice_id = ?
                ORDER BY allocation.id
                """, invoice.invoiceId());
    }

    private List<Map<String, Object>> receipts(CommonInvoiceSeed invoice) {
        return jdbc.queryForList("""
                SELECT * FROM contractor_actual_payment_attributions
                WHERE source_kind = 'COMMON_INVOICE' AND common_invoice_id = ? ORDER BY id
                """, invoice.invoiceId());
    }

    private List<Map<String, Object>> bankLinks(CommonInvoiceSeed invoice) {
        return jdbc.queryForList("SELECT * FROM common_invoice_payment_refs WHERE invoice_id = ? ORDER BY payment_ref_id", invoice.invoiceId());
    }

    private List<String> recipientChangeJobs(CommonInvoiceSeed invoice) {
        return jdbc.queryForList("SELECT state FROM bank_recipient_change WHERE target_kind = 'INVOICE' AND target_id = ? ORDER BY id",
                String.class, invoice.invoiceId());
    }

    /** States of client messages of the invoice that are not delivered yet. */
    private List<String> queuedClientMessages(CommonInvoiceSeed invoice) {
        return jdbc.queryForList("""
                SELECT state FROM common_invoice_message_queue WHERE invoice_id = ? AND state NOT IN ('SENT', 'SUPERSEDED') ORDER BY created_at
                """, String.class, invoice.invoiceId());
    }

    private List<String> paymentNoticeKinds(CommonInvoiceSeed invoice) {
        return jdbc.queryForList("SELECT notification_kind FROM common_invoice_payment_notification_outbox WHERE invoice_id = ?",
                String.class, invoice.invoiceId());
    }

    private Map<String, Object> recipientNotice(long deliveryId) {
        return jdbc.queryForMap("SELECT * FROM common_invoice_payment_notification_outbox WHERE delivery_id = ?", deliveryId);
    }

    /** Users who have an open system reminder of this source. */
    private List<Long> openReminders(String sourceType, long sourceId) {
        return jdbc.queryForList("""
                SELECT user_id FROM personal_reminders WHERE source_type = ? AND source_id = ? AND completed_at IS NULL ORDER BY user_id
                """, Long.class, sourceType, sourceId);
    }

    private Map<String, Object> currentLink(long orderId) {
        return jdbc.queryForMap("SELECT * FROM payment_links WHERE order_id = ? ORDER BY id DESC LIMIT 1", orderId);
    }

    private Map<String, Object> link(long linkId) {
        return jdbc.queryForMap("SELECT * FROM payment_links WHERE id = ?", linkId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("""
                SELECT status.order_status_title FROM orders JOIN order_statuses status ON status.order_status_id = orders.order_status
                WHERE orders.order_id = ?
                """, String.class, orderId);
    }

    /** The invoice is still issued to its recipient and nothing of a payment was written. */
    private void assertUnpaidAndUntouched(CommonInvoiceSeed invoice, long orderId) {
        Map<String, Object> row = invoice(invoice);
        assertThat(row).containsEntry("status", "INVOICED");
        assertThat(num(row, "paid_kopecks")).isZero();
        assertThat(row.get("paid_at")).isNull();
        assertThat(row.get("manual_confirmed_at")).isNull();
        assertThat(orderStatus(orderId)).isEqualTo(STATUS_INVOICED);
        assertThat(jdbc.queryForObject("SELECT paid FROM common_invoice_orders WHERE invoice_id = ?", Boolean.class, invoice.invoiceId())).isFalse();
        assertThat(reserves(invoice)).hasSize(1);
        assertThat(reserves(invoice).getFirst()).containsEntry("status", "RESERVED");
        assertThat(num(reserves(invoice).getFirst(), "confirmed_kopecks")).isZero();
        assertThat(receipts(invoice)).isEmpty();
        assertThat(actualPaymentReserves(invoice)).isEmpty();
        assertThat(paymentNoticeKinds(invoice)).isEmpty();
    }

    /** The call is refused with this HTTP status and for this reason (a part of the message the user sees). */
    private static void assertRejected(int httpStatus, String reasonPart, ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
            assertThat(failure.getStatusCode().value()).as(String.valueOf(failure.getReason())).isEqualTo(httpStatus);
            assertThat(failure.getReason()).contains(reasonPart);
        });
    }

    /** Digits of the transfer number of the person's payment profile, as seeded by the world. */
    private static String requisites(Person person) {
        return digits(PaymentScenarioWorld.transferNumber(person));
    }

    private static String digits(String text) {
        return text == null ? "" : text.replaceAll("\\D", "");
    }

    private static long num(Map<String, Object> row, String column) {
        Object value = row.get(column);
        assertThat(value).as(column).isNotNull();
        return value instanceof Boolean flag ? (flag ? 1 : 0) : ((Number) value).longValue();
    }
}

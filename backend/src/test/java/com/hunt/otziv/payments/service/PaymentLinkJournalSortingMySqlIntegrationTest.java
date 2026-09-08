package com.hunt.otziv.payments.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.AdminPaymentLinksPageResponse;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkArchiveRepository;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.security.credentials.CredentialCipher;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Executes the owner workflow against actual JPQL LIVE and JDBC ARCHIVE pages. */
@Testcontainers
class PaymentLinkJournalSortingMySqlIntegrationTest {
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = FROM.plusDays(2);
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("payment_journal_sorting").withUsername("root").withPassword("root");
    private static LocalContainerEntityManagerFactoryBean factory;
    private static EntityManager em;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static PaymentLinkRepository links;
    private static PaymentLinkArchiveRepository archived;
    private final Map<Long, LocalDateTime> matching = new LinkedHashMap<>();
    private PaymentLinkAdminBoardWorkflow board;

    @BeforeAll static void start() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("credentialCipher", mock(CredentialCipher.class));
        factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(ds);
        factory.setPackagesToScan("com.hunt.otziv"); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-only", "hibernate.show_sql", "false",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl",
                "hibernate.resource.beans.container", new SpringBeanContainer(beans)));
        factory.afterPropertiesSet();
        em = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        var manager = new JpaTransactionManager(factory.getObject()); manager.setDataSource(ds);
        transaction = new TransactionTemplate(manager); jdbc = new JdbcTemplate(ds);
        links = new JpaRepositoryFactory(em).getRepository(PaymentLinkRepository.class);
        archived = new PaymentLinkArchiveRepository(new NamedParameterJdbcTemplate(ds));
        jdbc.execute("CREATE TABLE archive_payment_links LIKE payment_links");
        jdbc.execute("""
                ALTER TABLE archive_payment_links
                  ADD company_title_snapshot VARCHAR(255), ADD filial_title_snapshot VARCHAR(255),
                  ADD archived_at DATETIME(6), ADD archive_reason VARCHAR(255)
                """);
    }

    @AfterAll static void stop() { if (factory != null) factory.destroy(); }

    @BeforeEach void seed() {
        jdbc.update("DELETE FROM archive_payment_links"); jdbc.update("DELETE FROM payment_links"); jdbc.update("DELETE FROM orders");
        matching.clear();
        var dates = new LinkedHashMap<Long, LocalDateTime>();
        transaction.executeWithoutResult(status -> {
            var order = new Order(); em.persist(order);
            for (int index = 0; index < 26; index++) {
                var link = new PaymentLink(); link.setOrder(order); link.setToken("sort-" + index);
                link.setDescription(index == 23 ? "another query" : "needle"); link.setAmountKopecks(100);
                link.setStatus(index == 25 ? PaymentLinkStatus.CREATED : PaymentLinkStatus.FAILED);
                link.setExpiresAt(TO.plusDays(30).atStartOfDay());
                em.persist(link);
                // Interleave dates and IDs; ties cross page boundaries in both directions.
                var created = FROM.plusDays(index == 24 ? 3 : index % 3).atTime(12, 0);
                dates.put(link.getId(), created);
                if (index < 23) matching.put(link.getId(), created);
            }
        });
        dates.forEach((id, created) -> jdbc.update("UPDATE payment_links SET created_at=? WHERE id=?", created, id));
        jdbc.update("INSERT INTO archive_payment_links SELECT pl.*, 'Company', 'Branch', NOW(), 'sorting test' FROM payment_links pl");
        var presenter = mock(PaymentLinkPresenter.class);
        when(presenter.normalize(nullable(String.class))).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            return value == null ? "" : value.trim();
        });
        when(presenter.toSummaryResponse(any())).thenCallRealMethod();
        when(presenter.toAdminResponse(any())).thenAnswer(invocation -> {
            var result = mock(AdminPaymentLinkResponse.class);
            when(result.id()).thenReturn(invocation.<PaymentLink>getArgument(0).getId());
            return result;
        });
        var archiveService = new PaymentLinkArchiveService(archived, mock(AppSettingService.class), new TbankPaymentProperties());
        var access = mock(ContractorPaymentTargetAccessPolicy.class);
        // Exercise the existing restricted-owner filter as well as sort/filter composition.
        when(access.excludePrivilegedTargets()).thenReturn(true);
        board = new PaymentLinkAdminBoardWorkflow(mock(PaymentLinkPreparationWorkflow.class), presenter, links, archiveService, access);
    }

    @ParameterizedTest
    @CsvSource({"LIVE,asc", "LIVE,desc", "ARCHIVE,asc", "ARCHIVE,desc"})
    void orderingAppliesBeforePaginationAndPreservesFiltersTotalsAndTies(String source, String direction) {
        var expected = expected(direction);
        var all = new ArrayList<Long>();
        for (int page = 0; page < 3; page++) {
            var response = read(page, source, direction);
            var pageIds = response.items().stream().map(AdminPaymentLinkResponse::id).toList();
            assertThat(pageIds).containsExactlyElementsOf(expected.subList(page * 10, Math.min(expected.size(), (page + 1) * 10)));
            assertThat(response.page()).isEqualTo(page);
            assertThat(response.totalElements()).isEqualTo(23);
            assertThat(response.totalPages()).isEqualTo(3);
            assertThat(response.summary().totalAmountKopecks()).isEqualTo(2300);
            assertThat(response.source()).isEqualTo(source);
            all.addAll(pageIds);
        }
        assertThat(all).doesNotHaveDuplicates().containsExactlyElementsOf(expected);
        assertThat(read(3, source, direction).items()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"LIVE", "ARCHIVE"})
    void oldClientsAndUnrecognizedDirectionKeepDescendingOrder(String source) {
        var expected = expected("desc").subList(0, 10);
        var legacy = transaction.execute(status -> board.adminLinks(0, 10, "failed", "needle", FROM, TO, source));
        assertThat(legacy.items()).extracting(AdminPaymentLinkResponse::id).containsExactlyElementsOf(expected);
        assertThat(read(0, source, null).items()).extracting(AdminPaymentLinkResponse::id).containsExactlyElementsOf(expected);
        assertThat(read(0, source, "asc; DROP TABLE payment_links").items()).extracting(AdminPaymentLinkResponse::id).containsExactlyElementsOf(expected);
        assertThat(read(0, source, " ASC ").items()).extracting(AdminPaymentLinkResponse::id)
                .containsExactlyElementsOf(expected("asc").subList(0, 10));
    }

    private AdminPaymentLinksPageResponse read(int page, String source, String direction) {
        return transaction.execute(status -> board.adminLinks(page, 10, "failed", "needle", FROM, TO, source, direction));
    }

    private List<Long> expected(String direction) {
        Comparator<Long> comparator = Comparator.<Long, LocalDateTime>comparing(matching::get).thenComparingLong(Long::longValue);
        return matching.keySet().stream().sorted("asc".equals(direction) ? comparator : comparator.reversed()).toList();
    }
}

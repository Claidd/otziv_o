package com.hunt.otziv.common_billing.repository;

import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import com.hunt.otziv.security.credentials.CredentialCipher;
import com.hunt.otziv.u_users.model.Manager;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Executes production count/list JPQL with actual entity mappings and MySQL. */
@Testcontainers
class CommonInvoiceManagerControlMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("common_invoice_manager_control")
            .withUsername("root").withPassword("fixture-password");
    private static LocalContainerEntityManagerFactoryBean factory;
    private static EntityManager em;
    private static TransactionTemplate tx;
    private static CommonInvoiceRepository invoices;

    @BeforeAll
    static void start() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("credentialCipher", mock(CredentialCipher.class));
        factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(ds);
        factory.setPackagesToScan("com.hunt.otziv"); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-only", "hibernate.show_sql", "false",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl",
                "hibernate.resource.beans.container", new SpringBeanContainer(beans)));
        factory.afterPropertiesSet();
        em = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        var manager = new JpaTransactionManager(factory.getObject()); manager.setDataSource(ds);
        tx = new TransactionTemplate(manager);
        invoices = new JpaRepositoryFactory(em).getRepository(CommonInvoiceRepository.class);
    }

    @AfterAll
    static void stop() { if (factory != null) factory.destroy(); }

    @Test
    void countAndExamplesAgreeOnInformationalDisabledTailsWithoutHidingRealProblems() {
        var manager = new Manager();
        List<Long> expected = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            em.persist(manager);
            var account = account(manager, false); // Disabled account alone never hides financial state.
            for (String prefix : List.of("disabled:", "empty:", "merged_into:", " MANUAL_FIX: ")) {
                invoice(account, CommonInvoiceStatus.DISABLED, prefix + " synthetic closed tail");
            }
            var paidTail = invoice(account, CommonInvoiceStatus.DISABLED, "disabled: paid position");
            position(paidTail, true);
            invoice(account, CommonInvoiceStatus.DISABLED, null);

            var unpaidTail = invoice(account, CommonInvoiceStatus.DISABLED, "disabled: unpaid position");
            position(unpaidTail, false); expected.add(unpaidTail.getId());
            var notification = invoice(account, CommonInvoiceStatus.DISABLED, "disabled: notification failed");
            notification.setPaymentSuccessNotificationError("delivery_failed"); expected.add(notification.getId());
            expected.add(invoice(account, CommonInvoiceStatus.DISABLED, "payment_cancel_failed_final").getId());
            expected.add(invoice(account, CommonInvoiceStatus.DISABLED, "manualXfix: real failure").getId());
            expected.add(invoice(account, CommonInvoiceStatus.DISABLED, "mergedXinto: real failure").getId());
            expected.add(invoice(account, CommonInvoiceStatus.PAID, "close_failed: synthetic").getId());
            expected.add(invoice(account, CommonInvoiceStatus.ARCHIVED, "payment_cancel_failed_final").getId());
            expected.add(invoice(account, CommonInvoiceStatus.READY, "empty: unexpected active state").getId());
            var partial = invoice(account, CommonInvoiceStatus.PARTIALLY_PAID, null);
            partial.setAmountKopecks(450000); partial.setPaidKopecks(150000); expected.add(partial.getId());

            var otherManager = new Manager(); em.persist(otherManager);
            invoice(account(otherManager, true), CommonInvoiceStatus.DISABLED, "payment_cancel_failed_final");
        });

        var now = LocalDateTime.now();
        var critical = Set.of(CommonInvoiceStatus.NEEDS_ATTENTION, CommonInvoiceStatus.UNPAID, CommonInvoiceStatus.BAN);
        var stale = Set.of(CommonInvoiceStatus.COLLECTING, CommonInvoiceStatus.READY, CommonInvoiceStatus.INVOICED,
                CommonInvoiceStatus.REMINDER, CommonInvoiceStatus.PARTIALLY_PAID);
        var paper = Set.of(CommonInvoiceStatus.INVOICED, CommonInvoiceStatus.REMINDER, CommonInvoiceStatus.PARTIALLY_PAID);
        long count = tx.execute(status -> invoices.countManagerControlInvoices(manager, critical, stale,
                CommonInvoiceStatus.PARTIALLY_PAID, CommonInvoiceStatus.COLLECTING, now.minusDays(3), now.minusHours(48),
                InvoicePaymentMode.OWNER_PAPER_INVOICE, paper, now.minusHours(24)));
        var selected = tx.execute(status -> invoices.findManagerControlInvoices(manager, critical, stale,
                CommonInvoiceStatus.PARTIALLY_PAID, CommonInvoiceStatus.COLLECTING, now.minusDays(3), now.minusHours(48),
                InvoicePaymentMode.OWNER_PAPER_INVOICE, paper, now.minusHours(24), PageRequest.of(0, 100))
                .stream().map(CommonInvoice::getId).toList());
        assertThat(count).isEqualTo(expected.size());
        assertThat(selected).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static CommonBillingAccount account(Manager manager, boolean enabled) {
        var account = new CommonBillingAccount(); account.setName("Synthetic account");
        account.setManager(manager); account.setEnabled(enabled); em.persist(account); return account;
    }

    private static CommonInvoice invoice(CommonBillingAccount account, CommonInvoiceStatus status, String error) {
        var invoice = new CommonInvoice(); invoice.setAccount(account); invoice.setStatus(status);
        invoice.setTitle("Synthetic invoice"); invoice.setToken(java.util.UUID.randomUUID().toString());
        invoice.setLastError(error); em.persist(invoice); return invoice;
    }

    private static void position(CommonInvoice invoice, boolean paid) {
        var order = new Order(); em.persist(order);
        var item = new CommonInvoiceOrder(); item.setInvoice(invoice); item.setOrder(order); item.setPaid(paid);
        em.persist(item);
    }
}

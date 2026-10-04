package com.hunt.otziv.p_products.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class PaymentAttentionOrderReaderMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("payment_attention").withUsername("root").withPassword("root");

    private JdbcTemplate jdbc;
    private PaymentAttentionOrderReaderService reader;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS orders");
        jdbc.execute("DROP TABLE IF EXISTS order_statuses");
        jdbc.execute("DROP TABLE IF EXISTS companies");
        jdbc.execute("DROP TABLE IF EXISTS managers");
        jdbc.execute("DROP TABLE IF EXISTS users");
        jdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, active BOOLEAN NOT NULL)");
        jdbc.execute("CREATE TABLE managers (manager_id BIGINT PRIMARY KEY, user_id BIGINT)");
        jdbc.execute("CREATE TABLE companies (company_id BIGINT PRIMARY KEY, company_title VARCHAR(120), "
                + "company_url_chat VARCHAR(500), company_manager BIGINT)");
        jdbc.execute("CREATE TABLE order_statuses (order_status_id BIGINT PRIMARY KEY, order_status_title VARCHAR(60))");
        jdbc.execute("CREATE TABLE orders (order_id BIGINT PRIMARY KEY, order_status BIGINT, "
                + "order_company BIGINT, order_manager BIGINT)");
        jdbc.update("INSERT INTO users VALUES (1, TRUE), (2, TRUE)");
        jdbc.update("INSERT INTO managers VALUES (11, 1), (12, 2)");
        jdbc.update("INSERT INTO companies VALUES (21, 'Компания', 'https://chat.example.test', 11)");
        jdbc.update("INSERT INTO order_statuses VALUES (1, 'Выставлен счет'), (2, 'Оплачено')");
        jdbc.update("INSERT INTO orders VALUES (31, 1, 21, 12)");
        reader = new PaymentAttentionOrderReaderService(jdbc);
    }

    @Test
    void usesOrderManagerAndStopsAfterPayment() {
        var contact = reader.awaitingPayment(31L).orElseThrow();
        assertThat(contact.managerUserId()).isEqualTo(2L);
        assertThat(contact.companyTitle()).isEqualTo("Компания");
        assertThat(contact.chatUrl()).isEqualTo("https://chat.example.test");

        jdbc.update("UPDATE orders SET order_status=2 WHERE order_id=31");
        assertThat(reader.awaitingPayment(31L)).isEmpty();
    }

    @Test
    void fallsBackToCompanyManagerAndExcludesInactiveRecipient() {
        jdbc.update("UPDATE orders SET order_manager=NULL WHERE order_id=31");
        assertThat(reader.awaitingPayment(31L).orElseThrow().managerUserId()).isEqualTo(1L);

        jdbc.update("UPDATE users SET active=FALSE WHERE id=1");
        assertThat(reader.awaitingPayment(31L)).isEmpty();
    }
}

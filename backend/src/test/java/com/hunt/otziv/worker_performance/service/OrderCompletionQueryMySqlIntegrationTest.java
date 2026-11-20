package com.hunt.otziv.worker_performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class OrderCompletionQueryMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("order_completions").withUsername("root").withPassword(UUID.randomUUID().toString());
    JdbcTemplate jdbc;
    NamedParameterJdbcTemplate named;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds); named = new NamedParameterJdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS business_audit_events");
        jdbc.execute("DROP TABLE IF EXISTS orders");
        jdbc.execute("CREATE TABLE orders(order_id BIGINT PRIMARY KEY,order_worker BIGINT,order_created DATE,order_waiting_for_client BOOLEAN)");
        jdbc.execute("CREATE TABLE business_audit_events(event_id BIGINT AUTO_INCREMENT PRIMARY KEY,order_id BIGINT,action VARCHAR(60),old_value VARCHAR(60),new_value VARCHAR(60),created_at DATETIME(6),KEY(order_id,created_at))");
        jdbc.update("INSERT INTO orders VALUES(21963,10,'2026-03-19',FALSE),(2,10,'2026-11-20',FALSE),(3,10,'2026-11-20',TRUE),(4,11,'2026-11-20',FALSE)");
    }
    void event(long id, String old, String next, String time) {
        jdbc.update("INSERT INTO business_audit_events(order_id,action,old_value,new_value,created_at) VALUES(?,'order_status_changed',?,?,?)",id,old,next,LocalDateTime.parse(time));
    }
    List<Map<String,Object>> day(String date) {
        var from = java.time.LocalDate.parse(date).atStartOfDay();
        return named.queryForList(OrderCompletionQuery.SQL,Map.of("workerIds",List.of(10L),"from",from,"to",from.plusDays(1)));
    }
    @Test void repeatedManagerHandoffDoesNotCreateANewCompletionOrLateDay() {
        event(21963,"Новый","В проверку","2026-11-11T18:17:58");
        event(21963,"Новый","В проверку","2026-11-20T20:05:08");
        event(21963,"На проверке","Архив","2026-11-20T20:05:17");
        assertThat(day("2026-11-11")).hasSize(1);
        assertThat(day("2026-11-20")).isEmpty();
    }
    @Test void realCorrectionStartsAnotherEpisodeAndDuplicateWithinItStillCountsOnce() {
        event(21963,"Новый","В проверку","2026-11-11T18:17:58");
        event(21963,"На проверке","Коррекция","2026-11-20T10:00:00");
        event(21963,"Коррекция","В проверку","2026-11-20T11:00:00");
        event(21963,"Коррекция","В проверку","2026-11-20T11:00:00");
        var rows = day("2026-11-20");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("opened_at")).isEqualTo(LocalDateTime.parse("2026-11-20T10:00:00"));
    }
    @Test void archiveWaitingAndOtherWorkersAreNotCompletedWriting() {
        event(2,"Новый","Архив","2026-11-20T11:00:00");
        event(3,"Новый","В проверку","2026-11-20T11:00:00");
        event(4,"Новый","В проверку","2026-11-20T11:00:00");
        assertThat(day("2026-11-20")).isEmpty();
        event(2,"Архив","Новый","2026-11-20T11:10:00");
        event(2,"Новый","В проверку","2026-11-20T11:20:00");
        assertThat(day("2026-11-20")).hasSize(1);
    }
}

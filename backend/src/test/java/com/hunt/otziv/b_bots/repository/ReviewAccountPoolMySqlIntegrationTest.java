package com.hunt.otziv.b_bots.repository;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class ReviewAccountPoolMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("account_pool").withUsername("root").withPassword(UUID.randomUUID().toString());
    private final LocalDate today = LocalDate.of(2026, 9, 27);
    private JdbcTemplate jdbc;
    private ReviewAccountPoolRepository repository;

    @BeforeEach void prepare() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        repository = new ReviewAccountPoolRepository(new NamedParameterJdbcTemplate(ds));
        jdbc.execute("DROP TABLE IF EXISTS bots,bots_status,reviews,bad_review_tasks,review_recovery_tasks,order_details,orders,filial,review_account_pool_alert_state");
        jdbc.execute("CREATE TABLE bots(bot_id BIGINT PRIMARY KEY,bot_city_id BIGINT,bot_active BOOLEAN,bot_status BIGINT,bot_fio VARCHAR(200),bot_counter INT,bot_login VARCHAR(200),bot_password VARCHAR(200),bot_cooldown_until DATE)");
        jdbc.execute("CREATE TABLE bots_status(bot_status_id BIGINT PRIMARY KEY,bot_status_title VARCHAR(100))");
        jdbc.execute("CREATE TABLE reviews(review_id BIGINT PRIMARY KEY,review_bot BIGINT,review_publish BOOLEAN,review_vigul BOOLEAN,review_filial BIGINT,review_order_details BIGINT)");
        jdbc.execute("CREATE TABLE bad_review_tasks(bad_review_task_bot BIGINT,bad_review_task_status VARCHAR(30))");
        jdbc.execute("CREATE TABLE review_recovery_tasks(review_recovery_task_bot BIGINT,review_recovery_task_status VARCHAR(30))");
        jdbc.execute("CREATE TABLE order_details(order_detail_id BIGINT PRIMARY KEY,order_detail_order BIGINT)");
        jdbc.execute("CREATE TABLE orders(order_id BIGINT PRIMARY KEY,order_filial BIGINT)");
        jdbc.execute("CREATE TABLE filial(filial_id BIGINT PRIMARY KEY,city_id BIGINT)");
        jdbc.execute("INSERT INTO bots_status VALUES(1,'Новый'),(2,'Средний')");
        jdbc.execute("INSERT INTO filial VALUES(5,5),(320,320),(326,326),(325,325)");
    }

    @Test void countsNamedWalkingAndPublicationStockAlongsideTemplateVariants() {
        bot(2, "Иван Петров", 0); bot(3, "Анна Сидорова", 1);
        bot(4, "Пётр Иванов", 2); bot(5, "Мария Иванова", 7);
        bot(6, "Впиши Имя Фамилию", 0); bot(7, "  ВПИШИТЕ ФАМИЛИЮ ИМЯ  ", 1);
        bot(8, "Впишите Имя Фамилию", 2);
        assertThat(repository.snapshot(today, 2)).isEqualTo(new ReviewAccountPoolRepository.Snapshot(4, 2, 2, 0, 0));
        assertThat(repository.snapshot(today, 3)).isEqualTo(new ReviewAccountPoolRepository.Snapshot(4, 1, 2, 0, 0));
        assertThat(repository.snapshot(today, 1).walking()).isEqualTo(3);
    }

    @Test void excludesBusyInvalidCoolingAndOtherCityAccounts() {
        for (long id = 1; id <= 18; id++) bot(id, "Иван Петров", 1);
        jdbc.execute("UPDATE bots SET bot_active=0 WHERE bot_id=2");
        jdbc.execute("UPDATE bots SET bot_status=2 WHERE bot_id=3");
        jdbc.execute("UPDATE bots SET bot_login='' WHERE bot_id=4");
        jdbc.execute("UPDATE bots SET bot_password=NULL WHERE bot_id=5");
        jdbc.execute("UPDATE bots SET bot_fio=' ' WHERE bot_id=6");
        jdbc.execute("UPDATE bots SET bot_fio='Нет доступных аккаунтов' WHERE bot_id=7");
        jdbc.execute("UPDATE bots SET bot_cooldown_until='2026-09-28' WHERE bot_id=8");
        jdbc.execute("UPDATE bots SET bot_city_id=320 WHERE bot_id=9");
        jdbc.execute("UPDATE bots SET bot_city_id=326 WHERE bot_id=10");
        jdbc.execute("UPDATE bots SET bot_counter=-1 WHERE bot_id=11");
        jdbc.execute("INSERT INTO reviews VALUES(1,12,0,0,5,NULL),(2,13,NULL,0,5,NULL),(3,16,1,1,5,NULL)");
        jdbc.execute("INSERT INTO bad_review_tasks VALUES(14,'NEW'),(17,'DONE')");
        jdbc.execute("INSERT INTO review_recovery_tasks VALUES(15,'PLANNED'),(18,'DONE')");
        jdbc.execute("UPDATE bots SET bot_cooldown_until='2026-09-27' WHERE bot_id=18");
        assertThat(repository.snapshot(today, 2).walking()).isEqualTo(3);
    }

    @Test void demandFollowsReviewStageExcludesSpecialCitiesAndResolvesOrderFilial() {
        jdbc.execute("INSERT INTO orders VALUES(1,5)");
        jdbc.execute("INSERT INTO order_details VALUES(1,1)");
        jdbc.execute("INSERT INTO reviews VALUES(1,1,0,0,5,NULL),(2,1,0,1,5,NULL),(3,1,0,1,320,NULL),(4,1,0,0,326,NULL),(5,1,0,0,325,NULL),(6,1,1,0,5,NULL),(7,99,0,0,5,NULL),(8,1,0,NULL,NULL,1),(9,1,0,0,NULL,NULL)");
        var snapshot = repository.snapshot(today, 2);
        assertThat(snapshot.walkingRequired()).isEqualTo(2);
        assertThat(snapshot.publicationRequired()).isEqualTo(1);
        assertThat(snapshot.deficit()).isEqualTo(3);
    }

    @Test void stockForOneStageCannotHideShortageForTheOther() {
        var snapshot = new ReviewAccountPoolRepository.Snapshot(0, 500, 0, 10, 5);
        assertThat(snapshot.deficit()).isEqualTo(10);
        assertThat(snapshot.coverage()).isEqualTo(33);
    }

    @Test void deficitMigrationPreservesExistingAlertState() throws Exception {
        jdbc.execute("CREATE TABLE review_account_pool_alert_state(state_id INT PRIMARY KEY,last_remaining_count INT)");
        jdbc.execute("INSERT INTO review_account_pool_alert_state VALUES(1,2832)");
        try (var input = getClass().getResourceAsStream("/db/migration/V1_10_324__account_pool_stage_deficit.sql")) {
            jdbc.execute(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertThat(jdbc.queryForObject("SELECT last_deficit_count FROM review_account_pool_alert_state WHERE state_id=1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_remaining_count FROM review_account_pool_alert_state WHERE state_id=1", Integer.class)).isEqualTo(2832);
    }

    private void bot(long id, String fio, int counter) {
        jdbc.update("INSERT INTO bots VALUES(?,325,1,1,?,?,?, 'test-password',NULL)", id, fio, counter, "account-" + id);
    }
}

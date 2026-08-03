package com.hunt.otziv.config.jwt.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LeadTokenReplayGuardMySqlIntegrationTest {

    private static final String MYSQL_IMAGE =
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withDatabaseName("lead_replay_contract")
            .withUsername("root")
            .withPassword("root");

    private JdbcTemplate jdbc;
    private LeadTokenReplayGuard firstReplica;
    private LeadTokenReplayGuard secondReplica;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS lead_integration_token_claims");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V1_10_212__durable_lead_token_replay_claims.sql"
        )).execute(dataSource);
        firstReplica = new LeadTokenReplayGuard(new JdbcTemplate(dataSource));
        secondReplica = new LeadTokenReplayGuard(new JdbcTemplate(dataSource));
    }

    @Test
    void claimSurvivesReplicaAndProcessBoundariesWithoutPersistingRawIdentifier() {
        String tokenId = "sensitive-jti-value";
        Instant validUntil = Instant.now().plusSeconds(300);

        assertThat(firstReplica.consume(tokenId, validUntil)).isTrue();
        assertThat(secondReplica.consume(tokenId, validUntil)).isFalse();

        byte[] stored = jdbc.queryForObject(
                "SELECT token_hash FROM lead_integration_token_claims",
                byte[].class
        );
        assertThat(stored).hasSize(32);
        assertThat(HexFormat.of().formatHex(stored)).doesNotContain(tokenId);
    }

    @Test
    void expiredClaimCanBeReplacedAndFailedRequestCanReleaseItsClaim() {
        jdbc.update(
                "INSERT INTO lead_integration_token_claims "
                        + "(token_hash, expires_at_epoch_seconds) VALUES (UNHEX(SHA2(?, 256)), ?)",
                "expired-token",
                Instant.now().minusSeconds(1).getEpochSecond()
        );

        assertThat(firstReplica.consume("expired-token", Instant.now().plusSeconds(300))).isTrue();
        firstReplica.release("expired-token");
        assertThat(secondReplica.consume("expired-token", Instant.now().plusSeconds(300))).isTrue();
    }

    @Test
    void refusesAlreadyExpiredToken() {
        assertThat(firstReplica.consume("expired-before-claim", Instant.now().minusSeconds(1))).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM lead_integration_token_claims",
                Integer.class
        )).isZero();
    }
}

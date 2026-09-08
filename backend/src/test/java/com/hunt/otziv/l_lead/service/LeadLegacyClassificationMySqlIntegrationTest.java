package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.l_lead.repository.LeadCommandRepository;
import jakarta.validation.Validation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class LeadLegacyClassificationMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("lead_legacy").withUsername("root").withPassword("root");
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private LeadLegacyClassificationService service;
    private final AtomicBoolean concurrentClassification = new AtomicBoolean();

    @BeforeEach void prepare() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of("lead_command_replay_audit", "lead_sync_queue", "lead_command_queue")) jdbc.execute("DROP TABLE IF EXISTS " + table);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1_2_9__lead_sync_queue.sql"),
                new ClassPathResource("db/migration/V1_2_91__lead_sync_queue.sql"),
                new ClassPathResource("db/migration/V1_2_92__lead_sync_queue.sql"),
                new ClassPathResource("db/migration/V1_10_293__lead_command_delivery.sql"),
                new ClassPathResource("db/migration/V1_10_298__lead_command_consumer_compatibility_fence.sql")).execute(dataSource);
        LeadCommandRepository target = new LeadCommandRepository(jdbc) {
            @Override public List<Legacy> legacyBatch(long after, long through, int limit) {
                var rows = super.legacyBatch(after, through, limit);
                // A competing committed classifier wins after the scan, before this writer's CAS.
                if (!rows.isEmpty() && concurrentClassification.compareAndSet(true, false)) {
                    new JdbcTemplate(dataSource).update("UPDATE lead_command_queue SET delivery_state='UNKNOWN' WHERE id=?", rows.getFirst().id());
                }
                return rows;
            }
        };
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        service = new LeadLegacyClassificationService((LeadCommandRepository) proxy.getProxy(),
                new LeadCommandCodec(Validation.buildDefaultValidatorFactory().getValidator()), 20);
    }

    @Test void completeReadOnlyTraversalResumesBeyond500AndFreezesItsHighWaterMark() {
        for (int i=0; i<1203; i++) insert("{}", 0, 0);
        var first = service.classify(true, 500, 0, null, null);
        insert("{}", 0, 0); // Not part of this run; the saved upper bound remains authoritative.
        List<Long> ids = new ArrayList<>(first.rows().stream().map(LeadLegacyClassificationService.Row::id).toList());
        var second = service.classify(true, 500, first.nextAfterId(), first.throughId(), null);
        var resumed = service.classify(true, 500, second.nextAfterId(), second.throughId(), null);
        ids.addAll(second.rows().stream().map(LeadLegacyClassificationService.Row::id).toList());
        ids.addAll(resumed.rows().stream().map(LeadLegacyClassificationService.Row::id).toList());
        assertThat(ids).hasSize(1203).doesNotHaveDuplicates();
        assertThat(resumed.complete()).isTrue(); assertThat(resumed.changed()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_command_queue WHERE delivery_state='LEGACY'", Integer.class)).isEqualTo(1204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_command_replay_audit", Integer.class)).isZero();
    }

    @Test void reasonsAreDistinctAndReportsContainOnlySafeMetadata() {
        insert("",0,0); insert("{ }",0,0); insert("{broken",0,0);
        insert("{\"telephoneLead\":\"79990000000\"}",2,0);
        insert("{\"telephoneLead\":\"79990000000\",\"cityLead\":\"Иркутск\",\"createDate\":\"2026-09-07\"}",0,20);
        var batch = service.classify(true, 100, 0, null, null);
        assertThat(batch.reasons()).containsKeys("LEAD_PAYLOAD_EMPTY", "LEAD_PAYLOAD_EMPTY_OBJECT", "LEAD_PAYLOAD_CORRUPT", "LEAD_PAYLOAD_VERSION_UNSUPPORTED", "LEGACY_ATTEMPTS_EXHAUSTED");
        assertThat(batch.toString()).doesNotContain("79990000000", "{broken");
    }

    @Test void mutationCountsCommittedCasOnlyAndReplayIsIdempotentAndAudited() {
        insert("{}",0,0); insert("{}",0,0); insert("{}",0,0);
        concurrentClassification.set(true);
        var batch = service.classify(false, 100, 0, null, "maintenance-operator");
        assertThat(batch.scanned()).isEqualTo(3); assertThat(batch.changed()).isEqualTo(2);
        assertThat(batch.conflicts()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_command_replay_audit", Integer.class)).isEqualTo(2);
        assertThat(service.classify(false, 100, 0, batch.throughId(), "maintenance-operator").changed()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_command_queue WHERE payload_json='{}'", Integer.class)).isEqualTo(3);
    }

    private void insert(String json, int version, int attempts) {
        jdbc.update("INSERT INTO lead_command_queue(lead_id,telephone_lead,payload_json,payload_version,retry_count) VALUES(1,'79990000000',?,?,?)", json, version, attempts);
    }
}

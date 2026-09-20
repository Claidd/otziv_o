package com.hunt.otziv.admin.repository;

import com.hunt.otziv.admin.service.BotDuplicateReportSender;
import com.hunt.otziv.t_telegrambot.api.TelegramAdminDocuments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class BotDuplicateReportMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("duplicate_reports").withUsername("root").withPassword("root");
    private JdbcTemplate jdbc;
    private BotDuplicateReportRepository repository;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS bot_duplicate_report_delivery");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V1_10_322__bot_duplicate_report_delivery.sql"));
        }
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(new BotDuplicateReportRepository(new NamedParameterJdbcTemplate(source)));
        proxy.addAdvice(advice);
        proxy.setProxyTargetClass(true);
        repository = (BotDuplicateReportRepository) proxy.getProxy();
    }

    @Test
    void rolledBackImportDoesNotLeaveOrExposeDocument() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            repository.enqueue(UUID.randomUUID().toString(), "duplicates.txt", "Не должно отправиться");
            assertThat(repository.claimNext()).isEmpty();
            throw new IllegalStateException("import failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count()).isZero();
    }

    @Test
    void retriesPartialDeliveryAfterRestartAndDeletesAllReportContentsWhenDone() {
        enqueue();
        TelegramAdminDocuments telegram = mock(TelegramAdminDocuments.class);
        when(telegram.canSendDocuments()).thenReturn(true);
        when(telegram.adminDocumentRecipients()).thenReturn(List.of(11L, 22L));
        when(telegram.sendDocumentOnceMessageId(eq(11L), any(), anyString())).thenReturn(Optional.of(90));
        when(telegram.sendDocumentOnceMessageId(eq(22L), any(), anyString())).thenReturn(Optional.empty());
        new BotDuplicateReportSender(repository, telegram).sendPending();
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT pending_chat_ids FROM bot_duplicate_report_delivery", String.class)).isEqualTo("22");
        assertThat(jdbc.queryForObject("SELECT report_text FROM bot_duplicate_report_delivery", String.class)).isEqualTo("Отчёт по дублям");
        assertThat(repository.claimNext()).isEmpty(); // Backoff prevents a hot retry loop.
        jdbc.update("UPDATE bot_duplicate_report_delivery SET next_attempt_at=CURRENT_TIMESTAMP(6)");
        when(telegram.sendDocumentOnceMessageId(eq(22L), any(), anyString())).thenReturn(Optional.of(91));
        new BotDuplicateReportSender(repository, telegram).sendPending();
        assertThat(count()).isZero();
        verify(telegram, times(1)).sendDocumentOnceMessageId(eq(11L), any(), anyString());
        verify(telegram, times(2)).sendDocumentOnceMessageId(eq(22L), any(), anyString());
    }

    @Test
    void staleWorkerCannotSendAcknowledgeOrDeleteReclaimedReport() {
        enqueue();
        var old = repository.claimNext().orElseThrow();
        assertThat(repository.claimNext()).isEmpty();
        jdbc.update("UPDATE bot_duplicate_report_delivery SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)");
        var current = repository.claimNext().orElseThrow();
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThat(repository.renewLease(old)).isFalse();
        assertThat(repository.savePendingRecipients(old, List.of())).isFalse();
        assertThat(repository.deleteDelivered(old)).isFalse();
        repository.retryLater(old);
        assertThat(repository.renewLease(current)).isTrue();
        assertThat(repository.deleteDelivered(current)).isFalse(); // No confirmed delivery yet.
        assertThat(repository.savePendingRecipients(current, List.of())).isTrue();
        assertThat(repository.deleteDelivered(current)).isTrue();
        assertThat(count()).isZero();
    }

    @Test
    void canCleanUpAfterCrashBetweenLastAcknowledgementAndDeletion() {
        enqueue();
        var claim = repository.claimNext().orElseThrow();
        repository.savePendingRecipients(claim, List.of());
        jdbc.update("UPDATE bot_duplicate_report_delivery SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)");
        var recovered = repository.claimNext().orElseThrow();
        assertThat(recovered.pendingRecipients()).isEmpty();
        assertThat(repository.deleteDelivered(recovered)).isTrue();
    }

    private void enqueue() {
        transaction.executeWithoutResult(status -> repository.enqueue(UUID.randomUUID().toString(), "duplicates.txt", "Отчёт по дублям"));
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM bot_duplicate_report_delivery", Integer.class);
    }
}

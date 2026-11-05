package com.hunt.otziv.performance;

import com.hunt.otziv.contractor_payments.model.*;
import com.hunt.otziv.contractor_payments.repository.*;
import com.hunt.otziv.contractor_payments.service.*;
import com.hunt.otziv.common.events.ContractorAccrualAdjustedEvent;
import com.hunt.otziv.u_users.model.User;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual Hibernate queries on the migrated schema; independent modes, dates and profiles. */
@SpringBootTest(properties = {"otziv.monitoring.enabled=false", "telegram.bot.registration-enabled=false", "spring.main.banner-mode=off"})
@ActiveProfiles("test")
@Import(FinancialScenarioBenchmark.CaptureConfiguration.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class InteractiveBatchQueriesMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(FinancialScenarioBenchmark.MYSQL_IMAGE)
            .withDatabaseName("otziv").withUsername("root").withPassword("batch-local-only")
            .withCommand("--restrict-fk-on-non-standard-key=OFF");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEventPublisher events;
    @Autowired EntityManager em;
    @Autowired ContractorRewardLedgerService ledger;
    @Autowired ContractorPaymentAccountingService accounting;
    @Autowired ContractorAdminFinancialReadRepository financialRead;
    @Autowired com.hunt.otziv.u_users.api.TeamDirectoryReader teamDirectory;
    @Autowired ContractorPaymentAllocationRepository allocations;
    @Autowired ContractorActualPaymentAttributionRepository attributions;
    @Autowired com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository paymentRefs;
    @Autowired com.hunt.otziv.u_users.repository.WorkerRepository workers;
    @Autowired com.hunt.otziv.u_users.repository.ManagerRepository managers;
    @Autowired com.hunt.otziv.u_users.service.WorkerService workerDirectory;
    @Autowired com.hunt.otziv.manager_daily_summary.repository.ManagerSiteActivityEventRepository activityPoints;
    @Autowired com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository messagePoints;
    static final LocalDate FROM = LocalDate.of(2026, 8, 1), TO = FROM.plusMonths(1);

    @Autowired com.hunt.otziv.archive.repository.ManagerArchiveRepository archive;

    /** PERF-23: the archive page carries the total of the same search pass; a page past the end falls back to the count. */
    @Test void archiveSearchPageCarriesTheSameTotalAsTheCount() {
        Long archived = jdbc.query("SELECT order_status_id FROM order_statuses WHERE order_status_title='Архив'",
                (rs, row) -> rs.getLong(1)).stream().findFirst().orElseGet(() -> {
                    jdbc.update("INSERT INTO order_statuses(order_status_title) VALUES('Архив')");
                    return jdbc.queryForObject("SELECT order_status_id FROM order_statuses WHERE order_status_title='Архив'", Long.class);
                });
        long base = 9_100_000L;
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO archive_orders(order_id,order_status,company_title_snapshot,archived_at,order_changed)
                    VALUES(?,?,?,?,?)""", base + i, archived, "Перфпоиск кафе " + i,
                    FROM.atStartOfDay().plusHours(i), FROM.plusDays(i));
        }
        jdbc.update("INSERT INTO archive_orders(order_id,order_status,company_title_snapshot,archived_at,order_changed) VALUES(?,?,?,?,?)",
                base + 10, archived, "Другая компания", FROM.atStartOfDay(), FROM);
        jdbc.update("INSERT INTO archive_order_details(order_detail_id,order_detail_order) VALUES(UUID_TO_BIN(UUID()),?)", base + 10);
        jdbc.update("""
                INSERT INTO archive_reviews(review_id,review_order_details,review_text)
                SELECT ?, order_detail_id, 'Большое спасибо, перфпоиск отзыв' FROM archive_order_details WHERE order_detail_order=?""",
                base, base + 10);
        var scope = com.hunt.otziv.archive.dto.ArchiveAccessScope.all();

        var first = archive.findOrdersPage(scope, "all", "перфпоиск", 0, 2, "desc");
        var second = archive.findOrdersPage(scope, "all", "перфпоиск", 1, 2, "desc");
        var pastEnd = archive.findOrdersPage(scope, "all", "перфпоиск", 5, 2, "desc");

        assertThat(first.total()).isEqualTo(4).isEqualTo(archive.countOrders(scope, "all", "перфпоиск"));
        assertThat(first.items()).extracting(com.hunt.otziv.archive.dto.ManagerArchiveOrderListItem::id)
                .containsExactly(base + 2, base + 1);
        assertThat(second.total()).isEqualTo(4);
        assertThat(second.items()).extracting(com.hunt.otziv.archive.dto.ManagerArchiveOrderListItem::id)
                .containsExactly(base + 10, base);
        assertThat(pastEnd.items()).isEmpty();
        assertThat(pastEnd.total()).isEqualTo(4);
        assertThat(archive.findOrdersPage(scope, "all", "нет-такого-перфпоиска", 0, 2, "desc").total()).isZero();
        assertThat(archive.findOrdersPage(scope, "all", "", 0, 5, "desc").total())
                .isEqualTo(archive.countOrders(scope, "all", ""));
    }

    /** PERF-19: reply statistics read only chats replied by the manager's staff that day, with their full lookback history. */
    @Test void replyHistoryKeepsWholeLookbackOfChatsRepliedThatDayOnly() {
        var manager = managers.findAll().getFirst();
        var dayFrom = FROM.atStartOfDay();
        var dayTo = dayFrom.plusDays(1);
        var from = dayFrom.minusDays(90);
        var whatsapp = com.hunt.otziv.client_chat_control.model.ClientChatPlatform.WHATSAPP;
        var telegram = com.hunt.otziv.client_chat_control.model.ClientChatPlatform.TELEGRAM;
        var client = com.hunt.otziv.client_chat_control.model.ClientChatSenderRole.CLIENT;
        var staff = com.hunt.otziv.client_chat_control.model.ClientChatSenderRole.STAFF;
        java.util.function.Function<Object[], com.hunt.otziv.client_chat_control.model.ClientChatMessage> message = values -> {
            var row = new com.hunt.otziv.client_chat_control.model.ClientChatMessage();
            row.setPlatform((com.hunt.otziv.client_chat_control.model.ClientChatPlatform) values[0]);
            row.setChatId((String) values[1]);
            row.setSenderRole((com.hunt.otziv.client_chat_control.model.ClientChatSenderRole) values[2]);
            row.setMessageAt((LocalDateTime) values[3]);
            row.setManager((Boolean) values[4] ? manager : null);
            row.setDirection(com.hunt.otziv.client_chat_control.model.ClientChatDirection.values()[0]);
            row.setExternalMessageId("reply-history-" + values[1] + "-" + values[3] + "-" + values[2]);
            row.setMessageText("text");
            em.persist(row);
            return row;
        };
        var expected = List.of(
                message.apply(new Object[]{whatsapp, "chat-a", client, from, true}),
                message.apply(new Object[]{whatsapp, "chat-a", client, dayFrom.minusDays(5), true}),
                message.apply(new Object[]{whatsapp, "chat-a", staff, dayFrom.plusHours(1), true}),
                message.apply(new Object[]{whatsapp, "chat-a", client, dayTo, true}));
        message.apply(new Object[]{whatsapp, "chat-a", client, from.minusNanos(1000), true});  // before lookback
        message.apply(new Object[]{whatsapp, "chat-a", client, dayTo.plusNanos(1000), true});  // after the day
        message.apply(new Object[]{whatsapp, "chat-a", client, dayFrom.plusHours(2), false});  // another manager
        message.apply(new Object[]{telegram, "chat-a", client, dayFrom.plusHours(1), true});   // same id, other platform
        message.apply(new Object[]{telegram, "chat-a", staff, dayFrom.minusNanos(1000), true}); // replied the day before
        message.apply(new Object[]{whatsapp, "chat-b", client, dayFrom.plusHours(3), true});
        message.apply(new Object[]{whatsapp, "chat-b", staff, dayTo, true});                    // reply after the day
        message.apply(new Object[]{whatsapp, "chat-c", client, dayFrom.plusHours(4), true});
        message.apply(new Object[]{whatsapp, "chat-c", staff, dayFrom.plusHours(5), false});    // replied by someone else
        em.flush(); em.clear();

        var history = messagePoints.findManagerHistoryOfChatsRepliedBetween(manager.getId(), dayFrom, dayTo, from, dayTo);

        assertThat(history.stream().map(com.hunt.otziv.client_chat_control.model.ClientChatMessage::getId).toList())
                .containsExactlyElementsOf(expected.stream().map(com.hunt.otziv.client_chat_control.model.ClientChatMessage::getId).toList());
        assertThat(messagePoints.findManagerHistoryOfChatsRepliedBetween(manager.getId(), dayTo, dayTo.plusDays(1), from, dayTo.plusDays(1)))
                .extracting(com.hunt.otziv.client_chat_control.model.ClientChatMessage::getChatId).containsOnly("chat-b");
    }

    @Test void constructorActivityProjectionsKeepActorScopeAndTimestampEdges() {
        var manager = managers.findAll().getFirst();
        var from = FROM.atStartOfDay();
        var to = from.plusDays(1);
        for (var at : List.of(from.minusNanos(1000), from, to.minusNanos(1000), to)) {
            var event = new com.hunt.otziv.manager_daily_summary.model.ManagerSiteActivityEvent();
            event.setUser(manager.getUser()); event.setManager(manager); event.setOccurredAt(at); event.setActivityType("HEARTBEAT");
            em.persist(event);
            var message = new com.hunt.otziv.client_chat_control.model.ClientChatMessage();
            message.setActorUser(manager.getUser()); message.setManager(null); message.setMessageAt(at);
            message.setPlatform(com.hunt.otziv.client_chat_control.model.ClientChatPlatform.values()[0]);
            message.setDirection(com.hunt.otziv.client_chat_control.model.ClientChatDirection.values()[0]);
            message.setSenderRole(com.hunt.otziv.client_chat_control.model.ClientChatSenderRole.STAFF);
            message.setChatId("activity-query-fixture"); message.setExternalMessageId(at.toString());
            em.persist(message);
        }
        em.flush(); em.clear();
        var events = activityPoints.pointsForManagers(List.of(manager.getId()), from, to);
        assertThat(events).hasSize(3).allSatisfy(row -> {
            assertThat(row.getManagerId()).isEqualTo(manager.getId());
            assertThat(row.getActivityType()).isEqualTo("HEARTBEAT");
        });
        assertThat(events.stream().map(row -> row.getOccurredAt()).toList()).containsExactlyInAnyOrder(from, to.minusNanos(1000), to);
        assertThat(messagePoints.staffPointsForManagers(List.of(manager.getId()), from, to).stream().map(row -> row.getMessageAt()).toList())
                .containsExactlyInAnyOrder(from, to.minusNanos(1000));
        assertThat(activityPoints.pointsForManagers(List.of(Long.MAX_VALUE), from, to)).isEmpty();
        assertThat(messagePoints.staffPointsForManagers(List.of(Long.MAX_VALUE), from, to)).isEmpty();
    }

    @Test void progressIdsPreserveActiveRoleAndManagerMembershipWithoutLoadingProfiles() {
        var all = workers.findAllWithUserAndImage();
        assertThat(all).isNotEmpty();
        assertThat(teamDirectory.allActive(com.hunt.otziv.u_users.api.TeamDirectoryReader.Role.WORKER))
                .extracting(com.hunt.otziv.u_users.api.TeamDirectoryReader.Member::id)
                .containsExactlyInAnyOrderElementsOf(all.stream().map(com.hunt.otziv.u_users.model.Worker::getId).toList());
        assertThat(workerDirectory.getActiveWorkerIds()).containsExactlyInAnyOrderElementsOf(
                all.stream().map(com.hunt.otziv.u_users.model.Worker::getId).toList());
        var visibleManagers = managers.findAll();
        for (var manager : visibleManagers) {
            assertThat(teamDirectory.forManagers(com.hunt.otziv.u_users.api.TeamDirectoryReader.Role.WORKER, List.of(manager.getId())))
                    .extracting(com.hunt.otziv.u_users.api.TeamDirectoryReader.Member::id)
                    .containsExactlyInAnyOrderElementsOf(workers.findAllToManager(manager).stream()
                            .map(com.hunt.otziv.u_users.model.Worker::getId).toList());
            assertThat(workerDirectory.getActiveWorkerIdsByManagerIds(List.of(manager.getId())))
                    .containsExactlyInAnyOrderElementsOf(workers.findAllToManager(manager).stream()
                            .map(com.hunt.otziv.u_users.model.Worker::getId).toList());
        }
        assertThat(workerDirectory.getActiveWorkerIdsByManagerIds(visibleManagers.stream()
                .map(com.hunt.otziv.u_users.model.Manager::getId).toList()))
                .containsExactlyInAnyOrderElementsOf(workers.findAllToManagerList(visibleManagers).stream()
                        .map(com.hunt.otziv.u_users.model.Worker::getId).toList());
        assertThat(workerDirectory.getActiveWorkerIdsByManagerIds(null)).isEmpty();
        assertThat(workerDirectory.getActiveWorkerIdsByManagerIds(List.of())).isEmpty();
        assertThat(workerDirectory.getActiveWorkerIdsByManagerIds(List.of(Long.MAX_VALUE))).isEmpty();
        var removed = all.getFirst();
        removed.getUser().setActive(false);
        em.flush();
        assertThat(workerDirectory.getActiveWorkerIds()).doesNotContain(removed.getId());
        assertThat(teamDirectory.allActive(com.hunt.otziv.u_users.api.TeamDirectoryReader.Role.WORKER))
                .extracting(com.hunt.otziv.u_users.api.TeamDirectoryReader.Member::id).doesNotContain(removed.getId());
        assertThat(teamDirectory.forManagers(com.hunt.otziv.u_users.api.TeamDirectoryReader.Role.WORKER, null)).isEmpty();
        assertThat(teamDirectory.forManagers(com.hunt.otziv.u_users.api.TeamDirectoryReader.Role.WORKER, List.of())).isEmpty();
    }

    @Test void firedWorkerDisappearsFromAdminManagerAndOwnerTeamDirectories() {
        var worker = workers.findAllWithUserAndImage().iterator().next();
        var manager = managers.findAll().getFirst();
        jdbc.update("INSERT IGNORE INTO managers_users (manager_id, user_id) VALUES (?, ?)",
                manager.getId(), worker.getUser().getId());

        var workerRole = com.hunt.otziv.u_users.api.TeamDirectoryReader.Role.WORKER;
        assertThat(teamDirectory.allActive(workerRole)).extracting(
                com.hunt.otziv.u_users.api.TeamDirectoryReader.Member::id).contains(worker.getId());
        assertThat(teamDirectory.forManagers(workerRole, List.of(manager.getId()))).extracting(
                com.hunt.otziv.u_users.api.TeamDirectoryReader.Member::id).contains(worker.getId());
        assertThat(workers.findAllToManagerList(List.of(manager))).extracting(
                com.hunt.otziv.u_users.model.Worker::getId).contains(worker.getId());

        worker.getUser().setActive(false);
        em.flush();
        em.clear();

        assertThat(teamDirectory.allActive(workerRole)).extracting(
                com.hunt.otziv.u_users.api.TeamDirectoryReader.Member::id).doesNotContain(worker.getId());
        assertThat(teamDirectory.forManagers(workerRole, List.of(manager.getId()))).extracting(
                com.hunt.otziv.u_users.api.TeamDirectoryReader.Member::id).doesNotContain(worker.getId());
        assertThat(workers.findAllToManagerList(List.of(manager))).extracting(
                com.hunt.otziv.u_users.model.Worker::getId).doesNotContain(worker.getId());
    }

    @Test void invoiceBatchFactsMatchLegacyEvidenceAndPrepaymentFilters() {
        var ids = new ArrayList<Long>();
        for (int index = 0; index < 3; index++) {
            var account = new com.hunt.otziv.common_billing.model.CommonBillingAccount(); account.setName("Batch invoice " + index); em.persist(account);
            var invoice = new com.hunt.otziv.common_billing.model.CommonInvoice(); invoice.setAccount(account);
            invoice.setToken("batch-invoice-" + index); invoice.setTitle("Batch invoice"); em.persist(invoice); ids.add(invoice.getId());
            if (index == 2) continue;
            for (String status : List.of("PREPAID", "APPLIED", "ARCHIVED")) {
                var ref = new com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef(); ref.setInvoice(invoice);
                ref.setAmountKopecks(100L + index); ref.setStatus(status); em.persist(ref);
            }
            for (var kind : ContractorActualPaymentSourceKind.values()) {
                var row = ContractorActualPaymentAttribution.create("batch-invoice-" + index + kind, kind, invoice.getId(),
                        index == 0 ? null : 900099L, null, null, null, null, ContractorAllocationMode.LIVE,
                        ContractorRecipientType.OWNER, null, null, "Owner", ContractorRecipientType.OWNER, null, null, "Owner",
                        null, null, 100, null, 0, FROM.atStartOfDay(), "Batch test", "batch-proof", null, "batch-test", null);
                em.persist(row);
            }
        }
        em.flush();
        var attributed = attributions.findSourcesWithLegacyAttribution(ContractorActualPaymentSourceKind.COMMON_INVOICE, ids);
        assertThat(attributed).containsExactly(ids.getFirst());
        var prepaid = paymentRefs.sumForInvoices(ids, "PREPAID");
        for (Long id : ids) {
            assertThat(attributed.contains(id)).isEqualTo(attributions.existsBySourceKindAndSourceIdAndEvidenceId(
                    ContractorActualPaymentSourceKind.COMMON_INVOICE, id, null));
            assertThat(prepaid.stream().filter(row -> row.getInvoiceId().equals(id)).mapToLong(row -> row.getAmount()).sum())
                    .isEqualTo(paymentRefs.sumAmountKopecksByInvoiceIdAndStatus(id, "PREPAID"));
        }
    }

    @Test void batchAmountsEqualSingleProfileCanonicalReadsIncludingOpeningBalancesReturnsAndModes() {
        var first = profile(900001L, 1200);
        var second = profile(900002L, -300);
        var empty = profile(900003L, 77);
        List<ContractorPaymentProfile> profiles = List.of(first, second, empty);
        long source = 910000;
        for (var profile : List.of(first, second)) {
            for (int day : List.of(-1, 0, 2, 31)) {
                var reward = new ContractorRewardLedgerEntry(); reward.setProfile(profile); reward.setSourceZpId(++source);
                reward.setOccurredOn(FROM.plusDays(day)); reward.setAmountKopecks(101 + day); reward.setActive(day != 2);
                em.persist(reward);
            }
            for (var mode : ContractorAllocationMode.values()) {
                for (var status : List.of(ContractorAllocationStatus.RESERVED, ContractorAllocationStatus.CLIENT_REPORTED,
                        ContractorAllocationStatus.PARTIALLY_CONFIRMED, ContractorAllocationStatus.CONFIRMED)) {
                    var allocation = new ContractorPaymentAllocation(); allocation.setMode(mode);
                    allocation.setSourceType(ContractorAllocationSourceType.values()[0]); allocation.setSourceId(++source);
                    allocation.setAttemptNo(1); allocation.setRecipientType(ContractorRecipientType.values()[0]);
                    allocation.setRecipientProfile(profile); allocation.setRecipientUserId(profile.getUser().getId());
                    allocation.setAmountKopecks(10000); allocation.setConfirmedKopecks(status == ContractorAllocationStatus.CONFIRMED ? 12000 : 4000);
                    allocation.setReturnedKopecks(500); allocation.setStatus(status); em.persist(allocation);
                    for (var type : List.of(ContractorAllocationEventType.CONFIRMED, ContractorAllocationEventType.SIMULATED_CONFIRMED,
                            ContractorAllocationEventType.RETURNED, ContractorAllocationEventType.RELEASED,
                            ContractorAllocationEventType.EXPIRED, ContractorAllocationEventType.CANCELED)) {
                        for (int day : List.of(-1, 0, 31)) {
                            var event = new ContractorPaymentAllocationEvent(); event.setAllocation(allocation); event.setEventType(type);
                            event.setAmountKopecks(100 + type.ordinal()); event.setEffectiveAt(FROM.plusDays(day).atStartOfDay());
                            event.setObservedAt(TO.atStartOfDay()); event.setExternalRef("batch-" + (++source)); event.setActor("batch-test");
                            em.persist(event);
                        }
                    }
                }
            }
        }
        em.flush();
        var accruals = ledger.totalsForProfiles(profiles, FROM, TO);
        for (var profile : profiles) {
            assertThat(accruals.get(profile.getId()).total()).isEqualTo(ledger.totalAccrued(profile));
            assertThat(accruals.get(profile.getId()).month()).isEqualTo(ledger.accruedInPeriod(profile, FROM, TO));
        }
        var ids = profiles.stream().map(ContractorPaymentProfile::getId).toList();
        for (var mode : ContractorAllocationMode.values()) {
            var totals = accounting.totalsForProfiles(ids, mode, FROM.atStartOfDay(), TO.atStartOfDay());
            var facts = financialRead.read(ids, mode, accounting.summaryEventTypes(mode), FROM, TO);
            assertThat(accounting.totalsFromRows(facts.events(), mode)).isEqualTo(totals);
            var statuses = EnumSet.of(ContractorAllocationStatus.RESERVED, ContractorAllocationStatus.CLIENT_REPORTED,
                    ContractorAllocationStatus.PARTIALLY_CONFIRMED);
            var exposure = allocations.sumOutstandingForProfiles(ids, mode, statuses);
            for (var profile : profiles) {
                var accrual = facts.accruals().getOrDefault(profile.getId(), new ContractorAdminFinancialReadRepository.Accrual(0, 0));
                assertThat(accrual.total() + profile.getOpeningBalanceKopecks()).isEqualTo(accruals.get(profile.getId()).total());
                assertThat(accrual.month()).isEqualTo(accruals.get(profile.getId()).month());
                for (var status : statuses) assertThat(facts.exposures().getOrDefault(profile.getId(), Map.of()).getOrDefault(status, 0L))
                        .isEqualTo(allocations.sumOutstandingExposure(profile.getId(), mode, Set.of(status)));
                var total = totals.getOrDefault(profile.getId(), ContractorPaymentAccountingService.PeriodTotals.empty());
                assertThat(total.confirmedTotal()).isEqualTo(accounting.confirmedGross(profile, mode));
                assertThat(total.confirmedMonth()).isEqualTo(accounting.confirmedGrossInPeriod(profile, mode, FROM.atStartOfDay(), TO.atStartOfDay()));
                assertThat(total.returnedTotal()).isEqualTo(accounting.returned(profile, mode));
                assertThat(total.returnedMonth()).isEqualTo(accounting.returnedInPeriod(profile, mode, FROM.atStartOfDay(), TO.atStartOfDay()));
                assertThat(total.closedTotal()).isEqualTo(accounting.closedWithoutPayment(profile, mode));
                assertThat(total.closedMonth()).isEqualTo(accounting.closedWithoutPaymentInPeriod(profile, mode, FROM.atStartOfDay(), TO.atStartOfDay()));
                for (var status : statuses) assertThat(exposure.stream().filter(row -> row.getProfileId().equals(profile.getId()) && row.getStatus() == status)
                        .mapToLong(ContractorPaymentAllocationRepository.ProfileExposure::getOutstanding).sum())
                        .isEqualTo(allocations.sumOutstandingExposure(profile.getId(), mode, Set.of(status)));
            }
        }
        // A changed opening balance is observed by the next read; this path has no cross-request cache.
        first.setOpeningBalanceKopecks(2200); em.flush();
        assertThat(ledger.totalsForProfiles(List.of(first), FROM, TO).get(first.getId()).total())
                .isEqualTo(accruals.get(first.getId()).total() + 1000);
    }

    @Test void bonusOnlyProfileAppearsInBatchBalancesAndCanonicalAnalyticsWithoutWorkCounts() {
        var profile = profile(900021L, 0);
        em.flush();
        insertAdjustment(profile.getId(), "BONUS", 900L, FROM.plusDays(2), "bonus-current");
        insertAdjustment(profile.getId(), "DEDUCTION", -350L, FROM.plusDays(3), "deduction-current");
        insertAdjustment(profile.getId(), "BONUS", 125L, FROM.minusDays(1), "bonus-previous");

        var facts = financialRead.read(List.of(profile.getId()), ContractorAllocationMode.LIVE,
                accounting.summaryEventTypes(ContractorAllocationMode.LIVE), FROM, TO);
        assertThat(facts.accruals().get(profile.getId()))
                .isEqualTo(new ContractorAdminFinancialReadRepository.Accrual(675L, 550L));
        assertThat(facts.adjustmentMonths().get(profile.getId()))
                .isEqualTo(new ContractorAdminFinancialReadRepository.AdjustmentMonths(900L, 350L));
        assertThat(ledger.totalAccrued(profile)).isEqualTo(675L);
        assertThat(ledger.accruedInPeriod(profile, FROM, TO)).isEqualTo(550L);

        var source = jdbc.queryForMap("""
                SELECT COALESCE(SUM(salary_sum), 0) AS salary_sum,
                       COUNT(DISTINCT source_zp_id) AS work_count,
                       COALESCE(SUM(salary_review_count), 0) AS review_count
                FROM analytics_salary_source
                WHERE user_id = ? AND metric_date >= ? AND metric_date < ?
                """, profile.getUser().getId(), FROM, TO);
        assertThat((BigDecimal) source.get("salary_sum")).isEqualByComparingTo("5.50");
        assertThat(((Number) source.get("work_count")).longValue()).isZero();
        assertThat(((Number) source.get("review_count")).longValue()).isZero();

        var daily = jdbc.queryForMap("""
                SELECT COALESCE(SUM(salary_sum), 0) AS salary_sum,
                       COALESCE(SUM(salary_entry_count), 0) AS work_count
                FROM analytics_salary_daily_source
                WHERE user_id = ? AND metric_date >= ? AND metric_date < ?
                """, profile.getUser().getId(), FROM, TO);
        assertThat((BigDecimal) daily.get("salary_sum")).isEqualByComparingTo("5.50");
        assertThat(((Number) daily.get("work_count")).longValue()).isZero();
    }

    @Test void accrualAnalyticsMarkerRollsBackWithTheLedgerTransaction() {
        LocalDate effectiveOn = LocalDate.of(2099, 12, 15);
        String key = "contractor-accrual-analytics:2099-12";
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_settings WHERE setting_key = ?", Integer.class, key)).isZero();

        events.publishEvent(new ContractorAccrualAdjustedEvent(this, effectiveOn));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_settings WHERE setting_key = ?", Integer.class, key)).isEqualTo(1);

        TestTransaction.flagForRollback();
        TestTransaction.end();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_settings WHERE setting_key = ?", Integer.class, key)).isZero();
    }

    private void insertAdjustment(long profileId, String bucket, long signedKopecks,
                                  LocalDate effectiveOn, String idempotencyKey) {
        jdbc.update("""
                INSERT INTO contractor_accrual_adjustments
                    (profile_id, kind, bucket, signed_amount_kopecks, effective_on, reference_month,
                     reason, created_by, created_at, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, 'Analytics batch fixture', 'batch-test', CURRENT_TIMESTAMP(6), ?)
                """, profileId, bucket, bucket, signedKopecks, effectiveOn,
                effectiveOn.withDayOfMonth(1), idempotencyKey);
    }

    @Test void currentFinancialFactsRespectActualRecipientModeDateAndCommittedSourceChanges() {
        var original = profile(900011L, 0);
        var actual = profile(900012L, 0);
        long source = 980000;
        for (var mode : ContractorAllocationMode.values()) {
            for (int day : List.of(-1, 0, 31)) {
                em.persist(ContractorActualPaymentAttribution.create("actual-batch-" + (++source),
                        ContractorActualPaymentSourceKind.PAYMENT_LINK, source, null, null, null, null, null, mode,
                        ContractorRecipientType.SPECIALIST, original.getId(), original.getUser().getId(), "Original",
                        ContractorRecipientType.SPECIALIST, actual.getId(), actual.getUser().getId(), "Actual",
                        null, null, 120 + day, null, 0, FROM.plusDays(day).atStartOfDay(),
                        "Test", "proof", null, "test", null));
            }
        }
        em.flush();
        for (var mode : ContractorAllocationMode.values()) {
            var facts = financialRead.read(List.of(original.getId(), actual.getId()), mode, accounting.summaryEventTypes(mode), FROM, TO);
            assertThat(facts.transfers()).containsOnlyKeys(actual.getId());
            assertThat(facts.transfers().get(actual.getId())).isEqualTo(new ContractorAdminFinancialReadRepository.ActualTransfers(1, 120));
            var legacy = attributions.summarizeProfileActualTransfersInPeriod(List.of(original.getId(), actual.getId()), mode, FROM.atStartOfDay(), TO.atStartOfDay());
            assertThat(legacy).hasSize(1);
            assertThat(legacy.getFirst().getTransferAmountKopecks()).isEqualTo(120);
        }
        var reward = new ContractorRewardLedgerEntry(); reward.setProfile(actual); reward.setSourceZpId(++source);
        reward.setOccurredOn(FROM); reward.setAmountKopecks(501); reward.setActive(true); em.persist(reward); em.flush();
        assertThat(financialRead.read(List.of(actual.getId()), ContractorAllocationMode.LIVE,
                accounting.summaryEventTypes(ContractorAllocationMode.LIVE), FROM, TO).accruals().get(actual.getId()).month()).isEqualTo(501);
        reward.setActive(false); em.flush();
        assertThat(financialRead.read(List.of(actual.getId()), ContractorAllocationMode.LIVE,
                accounting.summaryEventTypes(ContractorAllocationMode.LIVE), FROM, TO).accruals()).isEmpty();
    }

    private ContractorPaymentProfile profile(long userId, long opening) {
        jdbc.update("INSERT INTO users(id,username,password,fio,email,phone_number,active,create_time) VALUES (?,?, 'unused','Batch test',?,?,1,CURRENT_DATE)",
                userId, "batch-" + userId, "batch-" + userId + "@example.test", "+7000" + userId);
        var profile = new ContractorPaymentProfile(); profile.setUser(em.find(User.class, userId));
        profile.setRole(ContractorRole.SPECIALIST); profile.setOpeningBalanceKopecks(opening); em.persist(profile);
        return profile;
    }
}

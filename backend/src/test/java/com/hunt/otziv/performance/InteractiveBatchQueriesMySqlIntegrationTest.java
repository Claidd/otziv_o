package com.hunt.otziv.performance;

import com.hunt.otziv.contractor_payments.model.*;
import com.hunt.otziv.contractor_payments.repository.*;
import com.hunt.otziv.contractor_payments.service.*;
import com.hunt.otziv.u_users.model.User;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
    @Autowired EntityManager em;
    @Autowired ContractorRewardLedgerService ledger;
    @Autowired ContractorPaymentAccountingService accounting;
    @Autowired ContractorPaymentAllocationRepository allocations;
    @Autowired ContractorActualPaymentAttributionRepository attributions;
    @Autowired com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository paymentRefs;
    @Autowired com.hunt.otziv.u_users.repository.WorkerRepository workers;
    @Autowired com.hunt.otziv.u_users.repository.ManagerRepository managers;
    @Autowired com.hunt.otziv.u_users.service.WorkerService workerDirectory;
    @Autowired com.hunt.otziv.manager_daily_summary.repository.ManagerSiteActivityEventRepository activityPoints;
    @Autowired com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository messagePoints;
    static final LocalDate FROM = LocalDate.of(2026, 8, 1), TO = FROM.plusMonths(1);

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
        assertThat(workerDirectory.getActiveWorkerIds()).containsExactlyInAnyOrderElementsOf(
                all.stream().map(com.hunt.otziv.u_users.model.Worker::getId).toList());
        var visibleManagers = managers.findAll();
        for (var manager : visibleManagers) {
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
            var statuses = EnumSet.of(ContractorAllocationStatus.RESERVED, ContractorAllocationStatus.CLIENT_REPORTED,
                    ContractorAllocationStatus.PARTIALLY_CONFIRMED);
            var exposure = allocations.sumOutstandingForProfiles(ids, mode, statuses);
            for (var profile : profiles) {
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

    private ContractorPaymentProfile profile(long userId, long opening) {
        jdbc.update("INSERT INTO users(id,username,password,fio,email,phone_number,active,create_time) VALUES (?,?, 'unused','Batch test',?,?,1,CURRENT_DATE)",
                userId, "batch-" + userId, "batch-" + userId + "@example.test", "+7000" + userId);
        var profile = new ContractorPaymentProfile(); profile.setUser(em.find(User.class, userId));
        profile.setRole(ContractorRole.SPECIALIST); profile.setOpeningBalanceKopecks(opening); em.persist(profile);
        return profile;
    }
}

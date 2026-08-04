package com.hunt.otziv;

import com.hunt.otziv.archive.dto.ArchiveRunResult;
import com.hunt.otziv.archive.dto.ManagerArchiveOrderDetailsResponse;
import com.hunt.otziv.archive.dto.ManagerArchiveOrderListItem;
import com.hunt.otziv.archive.service.ManagerArchiveService;
import com.hunt.otziv.archive.service.OrderArchiveDryRunService;
import com.hunt.otziv.manager.dto.api.PageResponse;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.p_products.worker_access.repository.WorkerAssignmentMutationGuardRepository;
import com.hunt.otziv.p_products.worker_access.repository.WorkerNetworkViolationRepository;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.workload_shadow.notification.dto.WorkloadShadowDeliveryOutcome;
import com.hunt.otziv.workload_shadow.repository.WorkloadLiveReadinessRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowEventRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowMonitorRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowNotificationStore;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowRunRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferPreferenceRepository;
import com.hunt.otziv.workload_shadow.maintenance.service.WorkloadShadowMaintenanceService;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowProjectionService;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowRunService;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowTransferSimulationService;
import com.hunt.otziv.workload_shadow.service.WorkloadTransferExecutionService;
import com.hunt.otziv.workload_shadow.service.WorkloadTransferExecutionTransactionService;
import com.hunt.otziv.workload_shadow.service.WorkloadTransferOfferService;
import com.hunt.otziv.workload_shadow.service.WorkloadTransferRollbackService;
import com.hunt.otziv.workload_shadow.service.WorkloadTransferWorkflowService;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OtzivOApplicationTests {

	@Container
	static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
			.withDatabaseName("otziv")
			.withUsername("root")
			.withPassword("root")
			.withCommand("--restrict-fk-on-non-standard-key=OFF");

	@DynamicPropertySource
	static void registerMysqlProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", mysql::getJdbcUrl);
		registry.add("spring.datasource.username", mysql::getUsername);
		registry.add("spring.datasource.password", mysql::getPassword);
		registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
		registry.add("otziv.archive.orders.apply-enabled", () -> "true");
	}

	@Autowired
	private ReviewService reviewService;

	@Autowired
	private com.hunt.otziv.r_review.services.ReviewCityService reviewCityService;

	@Autowired
	private Flyway flyway;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PaymentLinkService paymentLinkService;

	@Autowired
	private com.hunt.otziv.r_review.repository.ReviewRepository reviewRepository;

	@Autowired
	private com.hunt.otziv.r_review.bot.service.ReviewBotAssignmentExclusionService botAssignmentExclusionService;

	@Autowired
	private com.hunt.otziv.b_bots.repository.BotsRepository botsRepository;

	@Autowired
	private com.hunt.otziv.u_users.repository.WorkerRepository workerRepository;

	@Autowired
	private OrderArchiveDryRunService orderArchiveDryRunService;

	@Autowired
	private ManagerArchiveService managerArchiveService;

	@Autowired
	private WorkloadShadowNotificationStore workloadShadowNotificationStore;

	@Autowired
	private WorkloadShadowMonitorRepository workloadShadowMonitorRepository;

	@Autowired
	private WorkloadShadowRunRepository workloadShadowRunRepository;

	@Autowired
	private WorkloadShadowRunService workloadShadowRunService;

	@Autowired
	private WorkloadShadowProjectionService workloadShadowProjectionService;

	@Autowired
	private WorkloadShadowTransferSimulationService workloadShadowTransferSimulationService;

	@Autowired
	private WorkloadTransferPreferenceRepository workloadTransferPreferenceRepository;

	@Autowired
	private WorkloadShadowMaintenanceService workloadShadowMaintenanceService;

	@Autowired
	private WorkloadTransferWorkflowService workloadTransferWorkflowService;

	@Autowired
	private WorkloadTransferOfferService workloadTransferOfferService;

	@Autowired
	private WorkloadTransferOfferRepository workloadTransferOfferRepository;

	@Autowired
	private WorkloadTransferExecutionService workloadTransferExecutionService;

	@Autowired
	private WorkloadTransferExecutionTransactionService
			workloadTransferExecutionTransactionService;

	@Autowired
	private WorkloadTransferRollbackService workloadTransferRollbackService;

	@Autowired
	private WorkloadLiveReadinessRepository workloadLiveReadinessRepository;

	@Autowired
	private WorkloadShadowEventRepository workloadShadowEventRepository;

	@Autowired
	private WorkerAssignmentMutationGuardRepository
			workerAssignmentMutationGuardRepository;

	@Autowired
	private WorkerNetworkViolationRepository workerNetworkViolationRepository;

	@Test
	void contextLoads() {
	}

	@Test
	void flywayMigrationsApplyOnMySql() {
		assertThat(flyway.info().applied()).isNotEmpty();
	}

	@Test
	void externalReviewCandidateIndexMatchesOldestFirstScanOrder() {
		var indexedColumns = jdbcTemplate.queryForList("""
			SELECT COLUMN_NAME
			FROM INFORMATION_SCHEMA.STATISTICS
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = 'reviews'
			  AND INDEX_NAME = 'idx_reviews_external_auto_candidates'
			ORDER BY SEQ_IN_INDEX
			""", String.class);

		assertThat(indexedColumns).containsExactly(
				"review_publish",
				"review_published_marked_at",
				"review_id",
				"review_external_confirm_status"
		);
	}

	@Test
	void workerRiskSlaDeliveryClaimSchemaIsComplete() {
		var columns = jdbcTemplate.queryForList("""
			SELECT COLUMN_NAME
			FROM INFORMATION_SCHEMA.COLUMNS
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = 'worker_risk_incidents'
			  AND COLUMN_NAME IN (
			      'row_version',
			      'sla_delivery_claim_token',
			      'sla_delivery_claimed_at',
			      'sla_delivery_claim_kind'
			  )
			ORDER BY COLUMN_NAME
			""", String.class);
		var indexedColumns = jdbcTemplate.queryForList("""
			SELECT COLUMN_NAME
			FROM INFORMATION_SCHEMA.STATISTICS
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = 'worker_risk_incidents'
			  AND INDEX_NAME = 'idx_worker_risk_sla_cursor'
			ORDER BY SEQ_IN_INDEX
			""", String.class);

		assertThat(columns).containsExactlyInAnyOrder(
				"row_version",
				"sla_delivery_claimed_at",
				"sla_delivery_claim_kind",
				"sla_delivery_claim_token"
		);
		assertThat(indexedColumns).containsExactly(
				"status",
				"response_due_at",
				"incident_id"
		);
	}

	@Test
	void workerNetworkViolationRepositoryExecutesEpisodeLifecycleOnMySql() {
		String username = "network_violation_" + UUID.randomUUID();
		LocalDateTime mainFirstSeen = LocalDateTime.of(
				2026,
				7,
				20,
				10,
				0
		);
		LocalDateTime mainLastSeen = mainFirstSeen.plusMinutes(5);
		LocalDateTime mainEpisodeSlot = mainFirstSeen.withMinute(0);
		LocalDateTime cutoff = LocalDateTime.of(2026, 7, 10, 12, 0);

		jdbcTemplate.update("""
			INSERT INTO users (
			    username,
			    password,
			    fio,
			    email,
			    active,
			    create_time
			)
			VALUES (?, 'password', 'Network Violation Test', ?, 1, ?)
			""", username, username + "@example.test", mainFirstSeen);
		Long userId = jdbcTemplate.queryForObject(
				"SELECT LAST_INSERT_ID()",
				Long.class
		);

		try {
			assertThat(workerNetworkViolationRepository.upsertEpisode(
					userId,
					username,
					"NON_CELLULAR_NETWORK",
					"publish",
					"ENFORCE",
					"BLOCKED",
					mainEpisodeSlot,
					mainFirstSeen,
					"Provider One",
					"192.0.2.0/24",
					"first evidence"
			)).isEqualTo(1);
			assertThat(workerNetworkViolationRepository.upsertEpisode(
					userId,
					username,
					"NON_CELLULAR_NETWORK",
					"publish",
					"ENFORCE",
					"BLOCKED",
					mainEpisodeSlot,
					mainLastSeen,
					"Provider Two",
					"198.51.100.0/24",
					"second evidence"
			)).isGreaterThan(0);

			assertThat(jdbcTemplate.queryForObject("""
				SELECT attempt_count
				FROM worker_network_violation_episodes
				WHERE worker_user_id = ?
				  AND reason_code = 'NON_CELLULAR_NETWORK'
				  AND scope_code = 'publish'
				""", Long.class, userId)).isEqualTo(2L);

			var rows = workerNetworkViolationRepository.findActiveForUsers(
					java.util.List.of(userId),
					LocalDateTime.of(2026, 7, 1, 0, 0),
					LocalDateTime.of(2026, 8, 1, 0, 0)
			);
			assertThat(rows).singleElement().satisfies(row -> {
				assertThat(row.getUserId()).isEqualTo(userId);
				assertThat(row.getFirstSeenAt()).isEqualTo(mainFirstSeen);
				assertThat(row.getLastSeenAt()).isEqualTo(mainLastSeen);
				assertThat(row.getAttemptCount()).isEqualTo(2);
				assertThat(row.getReason()).isEqualTo("NON_CELLULAR_NETWORK");
				assertThat(row.getScope()).isEqualTo("publish");
				assertThat(row.getProvider()).isEqualTo("Provider Two");
				assertThat(row.getClientEvidence()).isEqualTo("second evidence");
				assertThat(row.getAccessResult()).isEqualTo("BLOCKED");
			});

			workerNetworkViolationRepository.upsertEpisode(
					userId,
					username,
					"INVALIDATED_TEST",
					"publish",
					"AUDIT",
					"INVALIDATED",
					mainEpisodeSlot.plusHours(1),
					mainLastSeen.plusHours(1),
					null,
					null,
					null
			);
			assertThat(workerNetworkViolationRepository.findActiveForUsers(
					java.util.List.of(userId),
					LocalDateTime.of(2026, 7, 1, 0, 0),
					LocalDateTime.of(2026, 8, 1, 0, 0)
			)).singleElement()
					.satisfies(row -> assertThat(row.getReason())
							.isEqualTo("NON_CELLULAR_NETWORK"));

			workerNetworkViolationRepository.upsertEpisode(
					userId,
					username,
					"RETENTION_OLD",
					"cleanup",
					"AUDIT",
					"AUDIT_ALLOWED",
					cutoff.minusSeconds(1),
					cutoff.minusSeconds(1),
					null,
					null,
					null
			);
			workerNetworkViolationRepository.upsertEpisode(
					userId,
					username,
					"RETENTION_BOUNDARY",
					"cleanup",
					"AUDIT",
					"AUDIT_ALLOWED",
					cutoff,
					cutoff,
					null,
					null,
					null
			);

			assertThat(workerNetworkViolationRepository.deleteBefore(cutoff))
					.isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM worker_network_violation_episodes
				WHERE worker_user_id = ?
				  AND reason_code = 'RETENTION_OLD'
				""", Integer.class, userId)).isZero();
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM worker_network_violation_episodes
				WHERE worker_user_id = ?
				  AND reason_code = 'RETENTION_BOUNDARY'
				""", Integer.class, userId)).isEqualTo(1);
		} finally {
			jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
		}
	}

	@Test
	void workloadShadowNativeRepositoriesExecuteInBatchesOnMySql() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 27, 12, 0);
		LocalDateTime leaseUntil = now.plusMinutes(5);
		String deduplicationKey = "integration-" + UUID.randomUUID();
		jdbcTemplate.update("""
			INSERT INTO workload_shadow_events (
			    deduplication_key,
			    severity,
			    event_type,
			    title,
			    message,
			    target_group_type,
			    delivery_status,
			    first_seen_at,
			    last_seen_at,
			    active
			)
			VALUES (?, 'WARNING', 'INTEGRATION_TEST', 'Тест', 'Тест', 'ADMIN_OWNER_MONITORING',
			        'PENDING', ?, ?, 1)
			""", deduplicationKey, now, now);
		Long eventId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		assertThat(workloadShadowNotificationStore.findDueEventIds(now, 10))
				.contains(eventId);
		assertThat(workloadShadowNotificationStore.claim(
				java.util.List.of(eventId),
				now,
				leaseUntil
		)).isEqualTo(1);

		var claimed = workloadShadowNotificationStore.findClaimed(
				java.util.List.of(eventId),
				now,
				leaseUntil
		);
		assertThat(claimed).hasSize(1);
		assertThat(workloadShadowNotificationStore.applyDeliveryOutcomes(
				java.util.List.of(WorkloadShadowDeliveryOutcome.sent(
						claimed.getFirst().event(),
						now
				)),
				now,
				leaseUntil
		)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
			SELECT delivery_status
			FROM workload_shadow_events
			WHERE workload_shadow_event_id = ?
			""", String.class, eventId)).isEqualTo("SENT");

		jdbcTemplate.update("""
			INSERT INTO workload_shadow_events (
			    deduplication_key,
			    severity,
			    event_type,
			    title,
			    message,
			    target_group_type,
			    delivery_status,
			    first_seen_at,
			    last_seen_at,
			    active
			)
			VALUES (?, 'WARNING', 'MISSING_GROUP_TEST', 'Тест', 'Тест', 'ADMIN_OWNER_MONITORING',
			        'MISSING_GROUP_BINDING', ?, ?, 1)
			""", "missing-" + UUID.randomUUID(), now, now);
		Long missingGroupEventId =
				jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		assertThat(workloadShadowNotificationStore.healthData(
				now,
				now.minusMinutes(30)
		).missingGroupBindings()).isGreaterThanOrEqualTo(1);
		assertThat(workloadShadowNotificationStore.cancelInactiveDeliveries(now, 10))
				.isGreaterThanOrEqualTo(0);
		assertThat(workloadShadowNotificationStore.retryStaleProcessingEvents(now, 10))
				.isGreaterThanOrEqualTo(0);
		assertThat(workloadShadowNotificationStore.failStaleRuns(
				now.minusMinutes(30),
				now,
				10
		)).isGreaterThanOrEqualTo(0);
		jdbcTemplate.update("""
			UPDATE workload_shadow_events
			SET active = 0,
			    last_seen_at = ?
			WHERE workload_shadow_event_id = ?
			""", now.minusYears(2), missingGroupEventId);
		assertThat(workloadShadowNotificationStore.deleteTerminalInactiveEvents(
				now.minusYears(1),
				10
		)).isGreaterThanOrEqualTo(1);
		assertThat(countById(
				"workload_shadow_events",
				"workload_shadow_event_id",
				missingGroupEventId
		)).isZero();
		assertThat(workloadShadowNotificationStore.deleteTerminalRuns(
				now.minusYears(1),
				10
		)).isGreaterThanOrEqualTo(0);
		assertThat(workloadShadowNotificationStore.deleteFinalizedDaily(
				now.toLocalDate().minusYears(2),
				10
		)).isGreaterThanOrEqualTo(0);
		assertThat(workloadShadowNotificationStore.deleteLateBatches(
				now.toLocalDate().minusYears(2),
				10
		)).isGreaterThanOrEqualTo(0);

		assertThat(workloadShadowMonitorRepository.summaryTotals()).isNotNull();
		assertThat(workloadShadowMonitorRepository.managerSummaries()).isEmpty();
		assertThat(workloadShadowMonitorRepository.workers(null)).isEmpty();
		assertThat(workloadShadowMonitorRepository.transferCases(null)).isEmpty();
		assertThat(workloadShadowMonitorRepository.transferCandidates(
				java.util.List.of(-1L)
		)).isEmpty();
		assertThat(workloadShadowMonitorRepository.events(10)).isNotEmpty();
		assertThat(workloadShadowMonitorRepository.nagulEstimate()).isEmpty();

		Long runId = workloadShadowRunService.start(
				"INTEGRATION",
				"test",
				now
		);
		assertThat(runId).isNotNull();
		workloadShadowRunService.complete(
				runId,
				new WorkloadShadowRunService.RunResult(0, 0, 0, 0, 0),
				now,
				now.plusSeconds(1)
		);
		assertThat(workloadShadowRunRepository.latestRun()).isPresent();
		assertThat(workloadShadowRunRepository.lastSuccessfulFinishedAt())
				.contains(now.plusSeconds(1));

		String preferenceUsername = "shadow_pref_" + UUID.randomUUID();
		jdbcTemplate.update("""
			INSERT INTO users (
			    username,
			    password,
			    fio,
			    email,
			    phone_number,
			    active,
			    create_time
			)
			VALUES (?, 'password', 'Shadow Worker', ?, '+79000000999', 1, ?)
			""", preferenceUsername, preferenceUsername + "@example.test", now);
		Long preferenceUserId =
				jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbcTemplate.update(
				"INSERT INTO workers (user_id) VALUES (?)",
				preferenceUserId
		);
		Long preferenceWorkerId =
				jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		assertThat(workloadTransferPreferenceRepository.findByUsername(
				preferenceUsername
		)).isPresent();
		assertThat(workloadTransferPreferenceRepository.updatePreference(
				preferenceWorkerId,
				preferenceUsername,
				false,
				now
		)).isEqualTo(1);
		assertThat(workloadTransferPreferenceRepository.findByUsername(
				preferenceUsername
		)).get()
				.extracting(
						WorkloadTransferPreferenceRepository.PreferenceProjection
								::getAcceptsCompanyTransfers
				)
				.isEqualTo(false);

		jdbcTemplate.update(
				"UPDATE users SET worker_telegram_group_chat_id = -200 WHERE id = ?",
				preferenceUserId
		);
		String managerUsername = "shadow_manager_" + UUID.randomUUID();
		jdbcTemplate.update("""
			INSERT INTO users (
			    username,
			    password,
			    fio,
			    email,
			    active,
			    create_time
			)
			VALUES (?, 'password', 'Shadow Manager', ?, 1, ?)
			""", managerUsername, managerUsername + "@example.test", now);
		Long managerUserId =
				jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbcTemplate.update("""
			INSERT INTO managers (
			    user_id,
			    audit_telegram_group_chat_id
			)
			VALUES (?, -100)
			""", managerUserId);
		Long managerId =
				jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		String recipientUsername = "shadow_recipient_" + UUID.randomUUID();
		jdbcTemplate.update("""
			INSERT INTO users (
			    username,
			    password,
			    fio,
			    email,
			    active,
			    create_time,
			    worker_telegram_group_chat_id
			)
			VALUES (?, 'password', 'Shadow Recipient', ?, 1, ?, -201)
			""", recipientUsername, recipientUsername + "@example.test", now);
		Long recipientUserId =
				jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbcTemplate.update(
				"INSERT INTO workers (user_id) VALUES (?)",
				recipientUserId
		);
		Long recipientWorkerId =
				jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbcTemplate.update(
				"INSERT INTO workers_users (worker_id, user_id) VALUES (?, ?), (?, ?)",
				preferenceWorkerId,
				managerUserId,
				recipientWorkerId,
				managerUserId
		);

		jdbcTemplate.update("""
			INSERT INTO companies (
			    company_title,
			    company_active,
			    company_user,
			    company_manager,
			    create_date
			)
			VALUES ('Shadow Company', 1, ?, ?, ?)
			""", managerUserId, managerId, now.toLocalDate());
		Long companyId =
				jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbcTemplate.update(
				"INSERT INTO workers_companies (worker_id, company_id) VALUES (?, ?)",
				preferenceWorkerId,
				companyId
		);
		Long newStatusId = jdbcTemplate.queryForObject("""
			SELECT order_status_id
			FROM order_statuses
			WHERE order_status_title = 'Новый'
			ORDER BY order_status_id
			LIMIT 1
			""", Long.class);
		jdbcTemplate.update("""
			INSERT INTO orders (
			    order_created,
			    order_changed,
			    order_status,
			    order_company,
			    order_manager,
			    order_worker,
			    order_complete,
			    order_waiting_for_client,
			    order_status_changed_at
			)
			VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?)
			""",
				now.toLocalDate(),
				now.toLocalDate(),
				newStatusId,
				companyId,
				managerId,
				preferenceWorkerId,
				now
		);
		Long orderId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		String detailId = UUID.randomUUID().toString();
		jdbcTemplate.update("""
			INSERT INTO order_details (
			    order_detail_id,
			    order_detail_order,
			    order_detail_amount
			)
			VALUES (UUID_TO_BIN(?), ?, 1)
			""", detailId, orderId);
		jdbcTemplate.update("""
			INSERT INTO reviews (
			    review_text,
			    review_created,
			    review_created_at,
			    review_changed,
			    review_publish,
			    review_publish_date,
			    review_order_details,
			    review_worker,
			    review_vigul
			)
			VALUES ('Текст отзыва', ?, ?, ?, 0, ?, UUID_TO_BIN(?), ?, 0)
			""",
				now.toLocalDate(),
				now,
				now.toLocalDate(),
				now.toLocalDate(),
				detailId,
				preferenceWorkerId
		);

		Long shadowRunId = workloadShadowRunService.start(
				"INTEGRATION_GRAPH",
				"test",
				now
		);
		var projectionResult = workloadShadowProjectionService.recalculate(
				shadowRunId,
				now
		);
		assertThat(projectionResult.workerCount()).isEqualTo(2);
		jdbcTemplate.update("""
			UPDATE workload_shadow_worker_current
			SET failure_days = 4,
			    rating = 70,
			    last_day_reached_100 = 0,
			    recipient_eligible = 0,
			    worker_group_connected = 1
			WHERE worker_id = ?
			""", preferenceWorkerId);
		jdbcTemplate.update("""
			UPDATE workload_shadow_worker_current
			SET failure_days = 0,
			    hundred_percent_days = 10,
			    rating = 95,
			    last_day_reached_100 = 1,
			    accepts_company_transfers = 1,
			    recipient_eligible = 1,
			    worker_group_connected = 1
			WHERE worker_id = ?
			""", recipientWorkerId);

		var transferResult = workloadShadowTransferSimulationService.rebuild(
				shadowRunId,
				now
		);
		assertThat(transferResult.transferCaseCount()).isEqualTo(1);
		assertThat(workloadShadowMonitorRepository.managerSummaries()).singleElement()
				.satisfies(manager -> {
					assertThat(manager.getManagerId()).isEqualTo(managerId);
					assertThat(manager.getGroupConnected()).isIn(true, 1, 1L);
				});
		assertThat(workloadShadowMonitorRepository.workers(managerId)).hasSize(2);
		var cases = workloadShadowMonitorRepository.transferCases(managerId);
		assertThat(cases).hasSize(1);
		assertThat(workloadShadowMonitorRepository.transferCandidates(
				java.util.List.of(cases.getFirst().getId())
		)).singleElement()
				.satisfies(candidate ->
						assertThat(candidate.getWorkerId()).isEqualTo(recipientWorkerId));
		assertThat(workloadShadowMonitorRepository.events(50)).isNotEmpty();
		workloadShadowRunService.complete(
				shadowRunId,
				new WorkloadShadowRunService.RunResult(
						1,
						2,
						transferResult.transferCaseCount(),
						transferResult.eventCount(),
						0
				),
				now,
				now.plusSeconds(2)
		);
	}

	@Test
	@Transactional
	void workloadTransferLiveFlowDeclinesThenAppliesAndRollsBackWholePackageOnMySql() {
		java.util.Map<String, String> originalLiveSettings = jdbcTemplate.query("""
			SELECT setting_key, setting_value
			FROM app_settings
			WHERE setting_key LIKE 'workload.live.%'
			ORDER BY setting_key
			""", resultSet -> {
				java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
				while (resultSet.next()) {
					values.put(
							resultSet.getString("setting_key"),
							resultSet.getString("setting_value")
					);
				}
				return values;
			});
		assertThat(originalLiveSettings).isNotEmpty();

		try {
			LocalDateTime now = LocalDateTime.now(
					java.time.ZoneId.of("Asia/Irkutsk")
			).withNano(0);
			String marker = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
			String sourceUsername = "live_source_" + marker;

			jdbcTemplate.update("""
				INSERT INTO users (
				    username,
				    password,
				    fio,
				    email,
				    active,
				    create_time
				)
				VALUES (?, 'password', 'LIVE E2E Manager', ?, 1, ?)
				""",
					"live_manager_" + marker,
					"live_manager_" + marker + "@example.test",
					now
			);
			Long managerUserId =
					jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
			jdbcTemplate.update("""
				INSERT INTO managers (
				    user_id,
				    audit_telegram_group_chat_id
				)
				VALUES (?, ?)
				""", managerUserId, -7_000_000_000L - managerUserId);
			Long managerId =
					jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

			WorkloadTestWorker source = createWorkloadTestWorker(
					sourceUsername,
					"LIVE E2E Source",
					now
			);
			WorkloadTestWorker firstRecipient = createWorkloadTestWorker(
					"live_first_" + marker,
					"LIVE E2E First Recipient",
					now
			);
			WorkloadTestWorker secondRecipient = createWorkloadTestWorker(
					"live_second_" + marker,
					"LIVE E2E Second Recipient",
					now
			);
			long firstGroupChatId = -8_100_000_000L - firstRecipient.userId();
			long secondGroupChatId = -8_200_000_000L - secondRecipient.userId();
			long firstTelegramId = 8_100_000_000L + firstRecipient.userId();
			long secondTelegramId = 8_200_000_000L + secondRecipient.userId();
			jdbcTemplate.update("""
				UPDATE users
				SET worker_telegram_group_chat_id = ?,
				    telegram_chat_id = ?
				WHERE id = ?
				""", firstGroupChatId, firstTelegramId, firstRecipient.userId());
			jdbcTemplate.update("""
				UPDATE users
				SET worker_telegram_group_chat_id = ?,
				    telegram_chat_id = ?
				WHERE id = ?
				""", secondGroupChatId, secondTelegramId, secondRecipient.userId());
			jdbcTemplate.update("""
				UPDATE users
				SET worker_telegram_group_chat_id = ?
				WHERE id = ?
				""", -8_000_000_000L - source.userId(), source.userId());
			jdbcTemplate.update("""
				INSERT INTO workers_users (worker_id, user_id)
				VALUES (?, ?), (?, ?), (?, ?)
				""",
					source.workerId(), managerUserId,
					firstRecipient.workerId(), managerUserId,
					secondRecipient.workerId(), managerUserId
			);

			jdbcTemplate.update("""
				INSERT INTO companies (
				    company_title,
				    company_active,
				    company_user,
				    company_manager,
				    create_date
				)
				VALUES (?, 1, ?, ?, ?)
				""",
					"LIVE E2E Company " + marker,
					managerUserId,
					managerId,
					now.toLocalDate()
			);
			Long companyId =
					jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
			jdbcTemplate.update(
					"INSERT INTO workers_companies (worker_id, company_id) VALUES (?, ?)",
					source.workerId(),
					companyId
			);

			Long newStatusId = jdbcTemplate.queryForObject("""
				SELECT order_status_id
				FROM order_statuses
				WHERE order_status_title = 'Новый'
				ORDER BY order_status_id
				LIMIT 1
				""", Long.class);
			jdbcTemplate.update("""
				INSERT INTO orders (
				    order_created,
				    order_changed,
				    order_status,
				    order_company,
				    order_manager,
				    order_worker,
				    order_amount,
				    order_counter,
				    order_complete,
				    order_waiting_for_client,
				    order_status_changed_at
				)
				VALUES (?, ?, ?, ?, ?, ?, 2, 1, 0, 0, ?)
				""",
					now.toLocalDate(),
					now.toLocalDate(),
					newStatusId,
					companyId,
					managerId,
					source.workerId(),
					now
			);
			Long orderId =
					jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
			String detailId = UUID.randomUUID().toString();
			jdbcTemplate.update("""
				INSERT INTO order_details (
				    order_detail_id,
				    order_detail_order,
				    order_detail_amount
				)
				VALUES (UUID_TO_BIN(?), ?, 2)
				""", detailId, orderId);
			jdbcTemplate.update("""
				INSERT INTO reviews (
				    review_text,
				    review_created,
				    review_created_at,
				    review_changed,
				    review_publish,
				    review_publish_date,
				    review_order_details,
				    review_worker,
				    review_vigul,
				    review_text_ready_at
				)
				VALUES ('Текст отзыва', ?, ?, ?, 0, ?, UUID_TO_BIN(?), ?, 0, NULL)
				""",
					now.toLocalDate(),
					now,
					now.toLocalDate(),
					now.toLocalDate(),
					detailId,
					source.workerId()
			);
			Long firstReviewId =
					jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
			jdbcTemplate.update("""
				INSERT INTO reviews (
				    review_text,
				    review_created,
				    review_created_at,
				    review_changed,
				    review_publish,
				    review_publish_date,
				    review_order_details,
				    review_worker,
				    review_vigul,
				    review_text_ready_at
				)
				VALUES ('Готовый текст для LIVE E2E', ?, ?, ?, 0, ?, UUID_TO_BIN(?), ?, 1, ?)
				""",
					now.toLocalDate(),
					now,
					now.toLocalDate(),
					now.toLocalDate(),
					detailId,
					source.workerId(),
					now
			);
			Long secondReviewId =
					jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

			jdbcTemplate.update("""
				INSERT INTO bad_review_tasks (
				    bad_review_task_order,
				    bad_review_task_review,
				    bad_review_task_worker,
				    bad_review_task_status,
				    bad_review_task_scheduled_date,
				    bad_review_task_created,
				    bad_review_task_changed,
				    bad_review_task_created_at
				)
				VALUES (?, ?, ?, 'NEW', ?, ?, ?, ?)
				""",
					orderId,
					firstReviewId,
					source.workerId(),
					now.toLocalDate(),
					now.toLocalDate(),
					now.toLocalDate(),
					now
			);
			Long badTaskId =
					jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

			jdbcTemplate.update("""
				INSERT INTO review_recovery_batches (
				    review_recovery_batch_order,
				    review_recovery_batch_manager,
				    review_recovery_batch_status,
				    review_recovery_batch_created_by,
				    review_recovery_batch_created_at,
				    review_recovery_batch_updated_at
				)
				VALUES (?, ?, 'OPEN', ?, ?, ?)
				""", orderId, managerId, managerUserId, now, now);
			Long recoveryBatchId =
					jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
			jdbcTemplate.update("""
				INSERT INTO review_recovery_tasks (
				    review_recovery_task_batch,
				    review_recovery_task_order,
				    review_recovery_task_review,
				    review_recovery_task_worker,
				    review_recovery_task_manager,
				    review_recovery_task_status,
				    review_recovery_task_recovery_text,
				    review_recovery_task_scheduled_date,
				    review_recovery_task_created_by,
				    review_recovery_task_created_at,
				    review_recovery_task_updated_at
				)
				VALUES (?, ?, ?, ?, ?, 'PLANNED', 'Восстановленный текст LIVE E2E',
				        ?, ?, ?, ?)
				""",
					recoveryBatchId,
					orderId,
					secondReviewId,
					source.workerId(),
					managerId,
					now.toLocalDate(),
					managerUserId,
					now,
					now
			);
			Long recoveryTaskId =
					jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

			assertThat(workerAssignmentMutationGuardRepository.lockOwnedOrder(
					orderId,
					sourceUsername
			)).contains(orderId);
			assertThat(workerAssignmentMutationGuardRepository.lockOwnedReview(
					firstReviewId,
					sourceUsername
			)).contains(firstReviewId);
			assertThat(workerAssignmentMutationGuardRepository.lockOwnedBadTask(
					badTaskId,
					sourceUsername
			)).contains(badTaskId);
			assertThat(workerAssignmentMutationGuardRepository.lockOwnedRecoveryTask(
					recoveryTaskId,
					sourceUsername
			)).contains(recoveryTaskId);

			jdbcTemplate.update("""
				INSERT INTO workload_shadow_runs (
				    trigger_type,
				    status,
				    started_at,
				    instance_id
				)
				VALUES ('LIVE_E2E', 'RUNNING', ?, ?)
				""", now, "live-e2e-" + marker);
			Long shadowRunId =
					jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
			workloadShadowProjectionService.recalculate(shadowRunId, now);
			assertThat(jdbcTemplate.queryForList("""
				SELECT worker_id
				FROM workload_shadow_worker_current
				WHERE worker_id IN (?, ?, ?)
				ORDER BY worker_id
				""", Long.class,
					source.workerId(),
					firstRecipient.workerId(),
					secondRecipient.workerId()
			)).containsExactlyInAnyOrder(
					source.workerId(),
					firstRecipient.workerId(),
					secondRecipient.workerId()
			);
			jdbcTemplate.update("""
				UPDATE workload_shadow_worker_current
				SET failure_days = 4,
				    rating = 70,
				    last_day_reached_100 = 0,
				    recipient_eligible = 0,
				    accepts_company_transfers = 0,
				    worker_group_connected = 1,
				    diagnostic_status = 'OK'
				WHERE worker_id = ?
				""", source.workerId());
			jdbcTemplate.update("""
				UPDATE workload_shadow_worker_current
				SET failure_days = 0,
				    hundred_percent_days = 12,
				    rating = 98,
				    last_day_reached_100 = 1,
				    accepts_company_transfers = 1,
				    recipient_eligible = 1,
				    worker_group_connected = 1,
				    estimated_remaining_minutes = 5,
				    diagnostic_status = 'OK'
				WHERE worker_id = ?
				""", firstRecipient.workerId());
			jdbcTemplate.update("""
				UPDATE workload_shadow_worker_current
				SET failure_days = 0,
				    hundred_percent_days = 11,
				    rating = 96,
				    last_day_reached_100 = 1,
				    accepts_company_transfers = 1,
				    recipient_eligible = 1,
				    worker_group_connected = 1,
				    estimated_remaining_minutes = 10,
				    diagnostic_status = 'OK'
				WHERE worker_id = ?
				""", secondRecipient.workerId());

			var simulation = workloadShadowTransferSimulationService.rebuild(
					shadowRunId,
					now
			);
			assertThat(simulation.transferCaseCount()).isGreaterThanOrEqualTo(1);
			Long transferCaseId = jdbcTemplate.queryForObject("""
				SELECT workload_shadow_transfer_case_id
				FROM workload_shadow_transfer_cases
				WHERE source_worker_id = ?
				  AND company_id = ?
				  AND manager_id = ?
				  AND active = 1
				  AND status = 'SHADOW_PENDING'
				  AND graph_error_count = 0
				ORDER BY workload_shadow_transfer_case_id DESC
				LIMIT 1
				""", Long.class, source.workerId(), companyId, managerId);
			assertThat(transferCaseId).isNotNull();
			assertThat(jdbcTemplate.queryForList("""
				SELECT worker_id
				FROM workload_shadow_transfer_candidates
				WHERE transfer_case_id = ?
				ORDER BY sequence_number
				""", Long.class, transferCaseId)).containsExactly(
					firstRecipient.workerId(),
					secondRecipient.workerId()
			);

			java.util.Map<String, String> liveOverrides = new java.util.LinkedHashMap<>();
			liveOverrides.put("workload.live.mode", "CANARY");
			liveOverrides.put("workload.live.apply-enabled", "true");
			liveOverrides.put("workload.live.min-candidates-per-manager", "2");
			liveOverrides.put("workload.live.canary-manager-ids", managerId.toString());
			liveOverrides.put("workload.live.offer-timeout-minutes", "15");
			liveOverrides.put("workload.live.offer-start-time", "00:00");
			liveOverrides.put("workload.live.offer-end-time", "23:59:59");
			liveOverrides.put("workload.live.max-transfers-per-manager-day", "100");
			liveOverrides.put("workload.live.max-transfers-global-day", "500");
			liveOverrides.put("workload.live.rollback-window-minutes", "30");
			liveOverrides.put("workload.live.first-live-owner-confirmations", "100");
			for (var entry : liveOverrides.entrySet()) {
				assertThat(jdbcTemplate.update("""
					UPDATE app_settings
					SET setting_value = ?,
					    updated_at = CURRENT_TIMESTAMP(6)
					WHERE setting_key = ?
					""", entry.getValue(), entry.getKey())).isEqualTo(1);
			}
			Long liveSettingsRevision = jdbcTemplate.queryForObject("""
				SELECT CAST(TRIM(setting_value) AS UNSIGNED)
				FROM app_settings
				WHERE setting_key = 'workload.live.settings-revision'
				""", Long.class);
			assertThat(liveSettingsRevision).isNotNull();

			var workflowStage = workloadTransferWorkflowService
					.stageEligibleRecommendations();
			assertThat(workflowStage.enabled()).isTrue();
			assertThat(workflowStage.staged()).isEqualTo(1);
			Long workflowId = jdbcTemplate.queryForObject("""
				SELECT workload_transfer_workflow_id
				FROM workload_transfer_workflows
				WHERE shadow_case_id = ?
				  AND manager_id = ?
				  AND source_worker_id = ?
				  AND company_id = ?
				  AND active = 1
				""", Long.class,
					transferCaseId,
					managerId,
					source.workerId(),
					companyId
			);
			assertThat(workflowId).isNotNull();
			String liveFailureCode = "LIVE_E2E_SQL_CONTRACT";
			long monitoringChatId = -5_181_415_104L;
			assertThat(workloadShadowEventRepository.upsertLiveExecutionFailure(
					workflowId,
					liveFailureCode,
					"Первая проверочная ошибка",
					true,
					monitoringChatId,
					now,
					now.minusMinutes(30)
			)).isPositive();
			assertThat(workloadShadowEventRepository.upsertLiveExecutionFailure(
					workflowId,
					liveFailureCode,
					"Повторная проверочная ошибка",
					true,
					monitoringChatId,
					now.plusSeconds(1),
					now.minusMinutes(30)
			)).isPositive();
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM workload_shadow_events
				WHERE deduplication_key = CONCAT(
				        'LIVE_EXECUTION_FAILURE:',
				        ?,
				        ':',
				        ?
				      )
				  AND event_type = 'LIVE_EXECUTION_FAILURE'
				  AND manager_id = ?
				  AND worker_id = ?
				  AND company_id = ?
				  AND transfer_case_id = ?
				  AND target_group_type = 'ADMIN_OWNER_MONITORING'
				  AND target_group_chat_id = ?
				  AND delivery_status = 'PENDING'
				  AND occurrence_count = 2
				  AND active = 1
				""", Integer.class,
					workflowId,
					liveFailureCode,
					managerId,
					source.workerId(),
					companyId,
					transferCaseId,
					monitoringChatId
			)).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM workload_transfer_workflow_candidates
				WHERE workflow_id = ?
				""", Integer.class, workflowId)).isEqualTo(2);
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(DISTINCT current.manager_id)
				FROM workload_transfer_workflow_candidates candidate
				JOIN workload_shadow_worker_current current
				  ON current.worker_id = candidate.worker_id
				WHERE candidate.workflow_id = ?
				  AND current.manager_id = ?
				""", Integer.class, workflowId, managerId)).isEqualTo(1);
			assertThat(jdbcTemplate.queryForList("""
				SELECT candidate.worker_id
				FROM workload_transfer_workflow_candidates candidate
				JOIN workload_shadow_worker_current current
				  ON current.worker_id = candidate.worker_id
				WHERE candidate.workflow_id = ?
				  AND current.manager_id = ?
				ORDER BY candidate.sequence_number
			""", Long.class, workflowId, managerId)).containsExactly(
					firstRecipient.workerId(),
					secondRecipient.workerId()
			);
			assertThat(workloadTransferWorkflowService
					.stageEligibleRecommendations()
					.staged()).isZero();
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM workload_transfer_workflows
				WHERE source_worker_id = ?
				  AND company_id = ?
				  AND active = 1
				""", Integer.class,
					source.workerId(),
					companyId
			)).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM workload_transfer_workflow_candidates
				WHERE workflow_id = ?
				""", Integer.class, workflowId)).isEqualTo(2);

			var firstStage = workloadTransferOfferService.stageNextOffers();
			assertThat(firstStage.staged()).isEqualTo(1);
			WorkloadTestOffer firstOffer = findWorkloadTestOffer(workflowId, 1);
			assertThat(firstOffer.candidateWorkerId())
					.isEqualTo(firstRecipient.workerId());
			var firstClaim = workloadTransferOfferService.claimDueOffers();
			assertThat(firstClaim.offers())
					.extracting(WorkloadTransferOfferRepository.DeliveryProjection::getOfferId)
					.contains(firstOffer.offerId());
			workloadTransferOfferService.markDelivered(
					firstOffer.offerId(),
					firstClaim.processingToken(),
					91_001
			);
			assertThat(workloadTransferOfferRepository.decline(
					firstOffer.offerToken(),
					firstGroupChatId,
					91_001,
					firstTelegramId,
					managerId,
					liveSettingsRevision,
					LocalDateTime.now(java.time.ZoneId.of("Asia/Irkutsk"))
			)).isPositive();
			assertThat(jdbcTemplate.queryForObject("""
				SELECT status
				FROM workload_transfer_offers
				WHERE workload_transfer_offer_id = ?
				""", String.class, firstOffer.offerId())).isEqualTo("DECLINED");

			var secondStage = workloadTransferOfferService.stageNextOffers();
			assertThat(secondStage.staged()).isEqualTo(1);
			WorkloadTestOffer secondOffer = findWorkloadTestOffer(workflowId, 2);
			assertThat(secondOffer.candidateWorkerId())
					.isEqualTo(secondRecipient.workerId());
			var secondClaim = workloadTransferOfferService.claimDueOffers();
			assertThat(secondClaim.offers())
					.extracting(WorkloadTransferOfferRepository.DeliveryProjection::getOfferId)
					.contains(secondOffer.offerId());
			workloadTransferOfferService.markDelivered(
					secondOffer.offerId(),
					secondClaim.processingToken(),
					91_002
			);
			assertThat(workloadTransferOfferRepository.accept(
					secondOffer.offerToken(),
					secondGroupChatId,
					91_002,
					secondTelegramId,
					managerId,
					liveSettingsRevision,
					LocalDateTime.now(java.time.ZoneId.of("Asia/Irkutsk"))
			)).isPositive();
			assertThat(jdbcTemplate.queryForObject("""
				SELECT status
				FROM workload_transfer_workflows
				WHERE workload_transfer_workflow_id = ?
				""", String.class, workflowId))
					.isEqualTo("AWAITING_OWNER_CONFIRMATION");

			workloadTransferExecutionService.confirmByOwner(workflowId);
			assertThat(jdbcTemplate.queryForObject("""
				SELECT status
				FROM workload_transfer_workflows
				WHERE workload_transfer_workflow_id = ?
				""", String.class, workflowId)).isEqualTo("ACCEPTED");
			Long acceptedVersion = jdbcTemplate.queryForObject("""
				SELECT workflow_version
				FROM workload_transfer_workflows
				WHERE workload_transfer_workflow_id = ?
				  AND status = 'ACCEPTED'
				""", Long.class, workflowId);
			var applied = workloadTransferExecutionTransactionService.apply(
					workflowId,
					acceptedVersion
			);
			assertThat(applied.status()).isEqualTo("APPLIED");
			assertThat(applied.executionId()).isNotNull();
			long executionId = applied.executionId();

			assertThat(jdbcTemplate.queryForObject("""
				SELECT order_worker
				FROM orders
				WHERE order_id = ?
				""", Long.class, orderId)).isEqualTo(secondRecipient.workerId());
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM orders
				WHERE order_id = ?
				  AND order_amount = 2
				  AND order_counter = 1
				""", Integer.class, orderId)).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM reviews
				WHERE review_id IN (?, ?)
				  AND review_worker = ?
				""", Integer.class,
					firstReviewId,
					secondReviewId,
					secondRecipient.workerId()
			)).isEqualTo(2);
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM reviews
				WHERE review_id = ?
				  AND review_vigul = 1
				  AND review_text_ready_at = ?
				""", Integer.class, secondReviewId, now)).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject("""
				SELECT bad_review_task_worker
				FROM bad_review_tasks
				WHERE bad_review_task_id = ?
				""", Long.class, badTaskId)).isEqualTo(secondRecipient.workerId());
			assertThat(jdbcTemplate.queryForObject("""
				SELECT review_recovery_task_worker
				FROM review_recovery_tasks
				WHERE review_recovery_task_id = ?
				""", Long.class, recoveryTaskId))
					.isEqualTo(secondRecipient.workerId());
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM workload_transfer_assignment_audit
				WHERE execution_id = ?
				  AND entity_type IN ('ORDER', 'REVIEW', 'BAD_TASK', 'RECOVERY_TASK')
				""", Integer.class, executionId)).isEqualTo(5);
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM workers_companies
				WHERE company_id = ?
				  AND worker_id = ?
				""", Integer.class, companyId, source.workerId())).isZero();
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM workers_companies
				WHERE company_id = ?
				  AND worker_id = ?
				""", Integer.class, companyId, secondRecipient.workerId())).isEqualTo(1);

			var rollback = workloadTransferRollbackService.rollback(
					executionId,
					WorkloadTransferRollbackService.CONFIRMATION
			);
			assertThat(rollback.status()).isEqualTo("ROLLED_BACK");
			assertThat(jdbcTemplate.queryForObject("""
				SELECT order_worker
				FROM orders
				WHERE order_id = ?
				""", Long.class, orderId)).isEqualTo(source.workerId());
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM orders
				WHERE order_id = ?
				  AND order_amount = 2
				  AND order_counter = 1
				""", Integer.class, orderId)).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM reviews
				WHERE review_id IN (?, ?)
				  AND review_worker = ?
				""", Integer.class,
					firstReviewId,
					secondReviewId,
					source.workerId()
			)).isEqualTo(2);
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM reviews
				WHERE review_id = ?
				  AND review_vigul = 1
				  AND review_text_ready_at = ?
				""", Integer.class, secondReviewId, now)).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject("""
				SELECT bad_review_task_worker
				FROM bad_review_tasks
				WHERE bad_review_task_id = ?
				""", Long.class, badTaskId)).isEqualTo(source.workerId());
			assertThat(jdbcTemplate.queryForObject("""
				SELECT review_recovery_task_worker
				FROM review_recovery_tasks
				WHERE review_recovery_task_id = ?
				""", Long.class, recoveryTaskId)).isEqualTo(source.workerId());
			assertThat(jdbcTemplate.queryForObject("""
				SELECT status
				FROM workload_transfer_executions
				WHERE workload_transfer_execution_id = ?
				""", String.class, executionId)).isEqualTo("ROLLED_BACK");
			assertThat(jdbcTemplate.queryForObject("""
				SELECT status
				FROM workload_transfer_workflows
				WHERE workload_transfer_workflow_id = ?
				""", String.class, workflowId)).isEqualTo("ROLLED_BACK");
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM workers_companies
				WHERE company_id = ?
				  AND worker_id = ?
				""", Integer.class, companyId, source.workerId())).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM workers_companies
				WHERE company_id = ?
				  AND worker_id = ?
				""", Integer.class, companyId, secondRecipient.workerId())).isZero();

			LocalDateTime stableSince = LocalDateTime.of(2099, 1, 1, 0, 0);
			LocalDateTime checkedAt = stableSince.plusMinutes(60);
			jdbcTemplate.update("""
				INSERT INTO workload_shadow_runs (
				    trigger_type,
				    status,
				    started_at,
				    finished_at,
				    instance_id
				)
				VALUES (?, 'SUCCEEDED', ?, ?, ?),
				       (?, 'SUCCEEDED', ?, ?, ?),
				       (?, 'SUCCEEDED', ?, ?, ?)
				""",
					"LIVE_GAP_1_" + marker,
					stableSince.plusMinutes(9),
					stableSince.plusMinutes(10),
					"live-gap-1-" + marker,
					"LIVE_GAP_2_" + marker,
					stableSince.plusMinutes(34),
					stableSince.plusMinutes(35),
					"live-gap-2-" + marker,
					"LIVE_GAP_3_" + marker,
					stableSince.plusMinutes(49),
					stableSince.plusMinutes(50),
					"live-gap-3-" + marker
			);
			assertThat(workloadLiveReadinessRepository
					.maximumSuccessfulRunGapMinutes(stableSince, checkedAt))
					.isEqualTo(25L);
		} finally {
			for (var entry : originalLiveSettings.entrySet()) {
				assertThat(jdbcTemplate.update("""
					UPDATE app_settings
					SET setting_value = ?,
					    updated_at = CURRENT_TIMESTAMP(6)
					WHERE setting_key = ?
					""", entry.getValue(), entry.getKey())).isEqualTo(1);
			}
		}
	}

	@Test
	void workloadMaintenanceRepairAndRetentionDmlExecuteOnMySql() {
		LocalDateTime now = LocalDateTime.now();
		String staleTrigger = "MAINTENANCE_" + UUID.randomUUID().toString().substring(0, 8);
		jdbcTemplate.update("""
			INSERT INTO workload_shadow_runs (
			    trigger_type,
			    status,
			    started_at,
			    instance_id
			)
			VALUES (?, 'RUNNING', ?, 'maintenance-integration')
			""", staleTrigger, now.minusHours(2));
		Long staleRunId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		assertThat(workloadShadowMaintenanceService.repairStaleState().failedRuns())
				.isGreaterThanOrEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
			SELECT status
			FROM workload_shadow_runs
			WHERE workload_shadow_run_id = ?
			""", String.class, staleRunId)).isEqualTo("FAILED");
		assertThat(jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM workload_maintenance_status
			WHERE maintenance_task = 'REPAIR'
			  AND last_succeeded_at IS NOT NULL
			""", Integer.class)).isEqualTo(1);

		String oldTrigger = "RETENTION_" + UUID.randomUUID().toString().substring(0, 8);
		jdbcTemplate.update("""
			INSERT INTO workload_shadow_runs (
			    trigger_type,
			    status,
			    started_at,
			    finished_at,
			    instance_id
			)
			VALUES (?, 'SUCCEEDED', ?, ?, 'maintenance-integration')
			""", oldTrigger, now.minusYears(3), now.minusYears(2));
		Long oldRunId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		workloadShadowMaintenanceService.cleanupRetention();

		assertThat(countById(
				"workload_shadow_runs",
				"workload_shadow_run_id",
				oldRunId
		)).isZero();
		assertThat(jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM workload_maintenance_status
			WHERE maintenance_task = 'RETENTION'
			  AND last_succeeded_at IS NOT NULL
			""", Integer.class)).isEqualTo(1);
	}

	@Test
	void reviewBotAssignmentExclusionCleanupQueryExecutesOnMySql() {
		assertThat(botAssignmentExclusionService.clearPublishedBefore(java.time.LocalDateTime.now()))
				.isGreaterThanOrEqualTo(0);
	}

	@Test
	void reviewBoardKeywordQueriesExecute() {
		LocalDate today = LocalDate.now();

		reviewService.getAllReviewDTOAndDateToAdmin(today, 0, 10, "desc", "test");
		reviewService.getAllReviewDTOByOrderStatusToAdmin("Не оплачено", 0, 10, "desc", "test");
		reviewService.getAllReviewDTOAndDateToAdminToVigul(today.plusDays(60), 0, 10, "desc", "test");
		reviewCityService.getCitiesWithUnpublishedReviews();
		botsRepository.findAllAdminRows();
		workerRepository.findWorkerOptions();
	}

	@Test
	void telegramReviewReportAggregateExecutesAndMergesOwnershipPaths() {
		LocalDate reportDate = LocalDate.of(2026, 5, 22);
		LocalDate firstDayOfMonth = reportDate.withDayOfMonth(1);
		String fio = "Report Merge User";

		jdbcTemplate.update("""
			INSERT INTO users (username, password, fio, email, phone_number, active, create_time)
			VALUES ('report_worker', 'password', ?, 'report_worker@example.test', '+79000000001', 1, ?)
		""", fio, reportDate);
		Long workerUserId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbcTemplate.update("INSERT INTO workers (user_id) VALUES (?)", workerUserId);
		Long workerId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbcTemplate.update("""
			INSERT INTO users (username, password, fio, email, phone_number, active, create_time)
			VALUES ('report_manager', 'password', ?, 'report_manager@example.test', '+79000000002', 1, ?)
		""", fio, reportDate);
		Long managerUserId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbcTemplate.update("INSERT INTO managers (user_id) VALUES (?)", managerUserId);
		Long managerId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbcTemplate.update("""
			INSERT INTO bots (bot_login, bot_password, bot_fio, bot_counter, bot_active)
			VALUES ('report_bot', 'password', 'Report Bot', 1, 1)
		""");
		Long botId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbcTemplate.update("INSERT INTO orders (order_manager) VALUES (?)", managerId);
		Long orderId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		byte[] detailId = uuidBytes(UUID.fromString("00000000-0000-0000-0000-000000000099"));
		jdbcTemplate.update("""
			INSERT INTO order_details (order_detail_id, order_detail_order)
			VALUES (?, ?)
		""", detailId, orderId);

		jdbcTemplate.update("""
			INSERT INTO reviews (review_text, review_publish, review_publish_date, review_vigul, review_worker, review_bot)
			VALUES ('worker report review', 0, ?, 0, ?, ?)
			""", reportDate, workerId, botId);
		jdbcTemplate.update("""
			INSERT INTO reviews (review_text, review_publish, review_publish_date, review_vigul, review_worker, review_bot)
			VALUES ('worker publish report review', 0, ?, 1, ?, ?)
			""", reportDate, workerId, botId);
		jdbcTemplate.update("""
			INSERT INTO reviews (review_text, review_publish, review_publish_date, review_vigul, review_order_details, review_bot)
			VALUES ('manager report review', 0, ?, 0, ?, ?)
			""", reportDate, detailId, botId);
		jdbcTemplate.update("""
			INSERT INTO reviews (review_text, review_publish, review_publish_date, review_vigul, review_order_details, review_bot)
			VALUES ('manager publish report review', 0, ?, 1, ?, ?)
			""", reportDate, detailId, botId);

		var result = reviewService.getAllPublishAndVigul(firstDayOfMonth, reportDate);

		assertThat(result).containsKey(fio);
		assertThat(result.get(fio).getFirst()).isEqualTo(2L);
		assertThat(result.get(fio).getSecond()).isEqualTo(2L);
	}

	@Test
	void countUnpublishedNotArchiveIncludesOrphansAndExcludesArchiveOrders() {
		long before = reviewRepository.countUnpublishedNotArchive();

		jdbcTemplate.update("""
			INSERT INTO reviews (review_text, review_publish)
			VALUES ('orphan unpublished review', 0)
		""");

		jdbcTemplate.update("INSERT INTO order_statuses (order_status_title) VALUES ('Архив')");
		Long archiveStatusId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbcTemplate.update("INSERT INTO orders (order_status) VALUES (?)", archiveStatusId);
		Long archiveOrderId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbcTemplate.update("""
			INSERT INTO order_details (order_detail_id, order_detail_order)
			VALUES (UNHEX(REPLACE(UUID(), '-', '')), ?)
		""", archiveOrderId);
		byte[] archiveDetailId = jdbcTemplate.queryForObject("""
			SELECT order_detail_id
			FROM order_details
			WHERE order_detail_order = ?
			LIMIT 1
		""", byte[].class, archiveOrderId);
		jdbcTemplate.update("""
			INSERT INTO reviews (review_text, review_publish, review_order_details)
			VALUES ('archived order unpublished review', 0, ?)
		""", archiveDetailId);

		assertThat(reviewRepository.countUnpublishedNotArchive()).isEqualTo(before + 1);
	}

	@Test
	@Transactional
	void paymentLinkCreationReloadsOrderAfterBulkExpirationClearsPersistenceContext() {
		List<Long> paidStatusIds = jdbcTemplate.queryForList("""
			SELECT order_status_id
			FROM order_statuses
			WHERE order_status_title = 'Оплачено'
			ORDER BY order_status_id
			LIMIT 1
			""", Long.class);
		Long paidStatusId;
		if (paidStatusIds.isEmpty()) {
			jdbcTemplate.update("INSERT INTO order_statuses (order_status_title) VALUES ('Оплачено')");
			paidStatusId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		} else {
			paidStatusId = paidStatusIds.getFirst();
		}

		jdbcTemplate.update("""
			INSERT INTO orders (
			    order_created,
			    order_changed,
			    order_status,
			    order_amount,
			    order_counter,
			    order_sum,
			    order_complete,
			    order_waiting_for_client
			)
			VALUES (CURRENT_DATE(), CURRENT_DATE(), ?, 1, 1, 100.00, 0, 0)
			""", paidStatusId);
		Long orderId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbcTemplate.update("""
			INSERT INTO payment_links (
			    token,
			    order_id,
			    amount_kopecks,
			    description,
			    status,
			    payment_method,
			    expires_at
			)
			VALUES (?, ?, 10000, 'Bulk clear regression', 'WAITING_MANUAL_PAYMENT',
			        'MANUAL_MOBILE_BANK', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY))
			""", "bulk-clear-" + UUID.randomUUID(), orderId);

		ResponseStatusException exception = org.junit.jupiter.api.Assertions.assertThrows(
				ResponseStatusException.class,
				() -> paymentLinkService.createForOrder(orderId)
		);

		assertThat(exception.getStatusCode().value()).isEqualTo(409);
		assertThat(exception.getReason()).isEqualTo(
				"Заказ уже полностью оплачен. Повторный счет заблокирован."
		);
	}

	@Test
	void orderArchiveLiveRunCopiesAndDeletesSelectedRows() {
		LocalDate archiveDate = LocalDate.of(1900, 1, 1);
		jdbcTemplate.update("INSERT INTO order_statuses (order_status_title) VALUES ('Оплачено')");
		Long paidStatusId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		jdbcTemplate.update("""
			INSERT INTO orders (
			    order_created,
			    order_changed,
			    order_pay_day,
			    order_status,
			    order_amount,
			    order_counter,
			    order_sum,
			    order_complete,
			    order_waiting_for_client
			)
			VALUES (?, ?, ?, ?, 1, 1, 100.00, 1, 0)
		""", archiveDate, archiveDate, archiveDate, paidStatusId);
		Long orderId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

		byte[] detailId = uuidBytes(UUID.fromString("00000000-0000-0000-0000-000000000001"));
		jdbcTemplate.update("""
			INSERT INTO order_details (
			    order_detail_id,
			    order_detail_order,
			    order_detail_amount,
			    order_detail_price
			)
			VALUES (?, ?, 1, 100.00)
		""", detailId, orderId);
		jdbcTemplate.update("""
			INSERT INTO reviews (
			    review_text,
			    review_answer,
			    review_publish,
			    review_publish_date,
			    review_order_details
			)
			VALUES ('archive integration review', 'ok', 1, ?, ?)
		""", archiveDate, detailId);
		Long reviewId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbcTemplate.update("""
			INSERT INTO zp (
			    zp_fio,
			    zp_sum,
			    zp_user,
			    zp_profession,
			    zp_order,
			    zp_amount,
			    zp_date,
			    zp_active
			)
			VALUES ('Archive Test', 100.00, 1, 1, ?, 1, ?, 1)
		""", orderId, archiveDate);
		Long zpId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbcTemplate.update("""
			INSERT INTO payment_check (
			    check_title,
			    check_company,
			    check_order,
			    check_date,
			    check_sum,
			    check_active
			)
			VALUES ('archive integration check', 1, ?, ?, 100.00, 1)
		""", orderId, archiveDate);
		Long checkId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbcTemplate.update("""
			INSERT INTO analytics_monthly_total (
			    month_start,
			    scope_key,
			    scope_type,
			    period_closed
			)
			VALUES (?, 'ADMIN:ALL', 'ADMIN', 1)
			ON DUPLICATE KEY UPDATE period_closed = VALUES(period_closed)
		""", archiveDate.withDayOfMonth(1));

		ArchiveRunResult result = orderArchiveDryRunService.runArchive(60, 1, "integration-test", true);

		assertThat(result.selected().orders()).isEqualTo(1);
		assertThat(result.selected().orderDetails()).isEqualTo(1);
		assertThat(result.selected().reviews()).isEqualTo(1);
		assertThat(result.selected().zp()).isEqualTo(1);
		assertThat(result.selected().paymentCheck()).isEqualTo(1);
		assertThat(result.archived()).isEqualTo(result.selected());
		assertThat(result.deleted()).isEqualTo(result.selected());

		assertThat(countById("orders", "order_id", orderId)).isZero();
		assertThat(countById("order_details", "order_detail_id", detailId)).isZero();
		assertThat(countById("reviews", "review_id", reviewId)).isZero();
		assertThat(countById("zp", "zp_id", zpId)).isZero();
		assertThat(countById("payment_check", "check_id", checkId)).isZero();
		assertThat(countById("archive_orders", "order_id", orderId)).isEqualTo(1);
		assertThat(countById("archive_order_details", "order_detail_id", detailId)).isEqualTo(1);
		assertThat(countById("archive_reviews", "review_id", reviewId)).isEqualTo(1);
		assertThat(countById("archive_zp", "zp_id", zpId)).isEqualTo(1);
		assertThat(countById("archive_payment_check", "check_id", checkId)).isEqualTo(1);

		TestingAuthenticationToken adminAuth = new TestingAuthenticationToken("admin", "n/a", "ROLE_ADMIN");
		PageResponse<ManagerArchiveOrderListItem> archiveOrders = managerArchiveService.findOrders(
				"archive integration",
				"all",
				0,
				10,
				"desc",
				() -> "admin",
				adminAuth
		);
		assertThat(archiveOrders.content())
				.extracting(ManagerArchiveOrderListItem::id)
				.contains(orderId);

		ManagerArchiveOrderDetailsResponse archiveDetails = managerArchiveService.getOrder(orderId, () -> "admin", adminAuth);
		assertThat(archiveDetails.order().id()).isEqualTo(orderId);
		assertThat(archiveDetails.details()).hasSize(1);
		assertThat(archiveDetails.reviews()).hasSize(1);
		assertThat(archiveDetails.zp()).hasSize(1);
		assertThat(archiveDetails.paymentChecks()).hasSize(1);

		managerArchiveService.restoreOrder(orderId, "Архив", true, () -> "admin", adminAuth);
		assertThat(countById("orders", "order_id", orderId)).isEqualTo(1);
		assertThat(countById("order_details", "order_detail_id", detailId)).isEqualTo(1);
		assertThat(countById("reviews", "review_id", reviewId)).isEqualTo(1);
		assertThat(countById("zp", "zp_id", zpId)).isEqualTo(1);
		assertThat(countById("payment_check", "check_id", checkId)).isEqualTo(1);
		assertThat(countById("archive_orders", "order_id", orderId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT restored_at IS NOT NULL FROM archive_orders WHERE order_id = ?",
				Boolean.class,
				orderId
		)).isTrue();

		PageResponse<ManagerArchiveOrderListItem> restoredArchiveOrders = managerArchiveService.findOrders(
				"archive integration",
				"all",
				0,
				10,
				"desc",
				() -> "admin",
				adminAuth
		);
		assertThat(restoredArchiveOrders.content())
				.anySatisfy(order -> {
					assertThat(order.id()).isEqualTo(orderId);
					assertThat(order.source()).isEqualTo("live");
				});
	}

	@Test
	void managerArchiveSearchMatchesWordsAcrossVisibleArchiveFields() {
		LocalDate archiveDate = LocalDate.of(2026, 5, 10);
		jdbcTemplate.update("INSERT INTO order_statuses (order_status_title) VALUES ('Архив')");
		Long archiveStatusId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		Long orderId = 990_001L;

		jdbcTemplate.update("DELETE FROM archive_orders WHERE order_id = ?", orderId);
		jdbcTemplate.update("""
			INSERT INTO archive_orders (
			    order_id,
			    order_changed,
			    order_status,
			    order_amount,
			    order_counter,
			    order_sum,
			    company_title_snapshot,
			    company_phone_snapshot,
			    company_city_snapshot,
			    filial_title_snapshot,
			    manager_name_snapshot,
			    worker_name_snapshot,
			    archived_at,
			    archive_reason
			)
			VALUES (?, ?, ?, 5, 1, 1000.00, 'У дома', '+7 (904) 123-45-67', 'Иркутск', 'Улица Саянская, 4а', 'Анжелика Б.', 'Вика Ц.', ?, 'search-test')
		""", orderId, archiveDate, archiveStatusId, archiveDate.atStartOfDay());

		TestingAuthenticationToken adminAuth = new TestingAuthenticationToken("admin", "n/a", "ROLE_ADMIN");
		PageResponse<ManagerArchiveOrderListItem> archiveOrders = managerArchiveService.findOrders(
				"дома саянская",
				"archive",
				0,
				10,
				"desc",
				() -> "admin",
				adminAuth
		);

		assertThat(archiveOrders.content())
				.extracting(ManagerArchiveOrderListItem::id)
				.contains(orderId);
	}

	private WorkloadTestWorker createWorkloadTestWorker(
			String username,
			String fio,
			LocalDateTime createdAt
	) {
		jdbcTemplate.update("""
			INSERT INTO users (
			    username,
			    password,
			    fio,
			    email,
			    active,
			    create_time
			)
			VALUES (?, 'password', ?, ?, 1, ?)
			""", username, fio, username + "@example.test", createdAt);
		Long userId = jdbcTemplate.queryForObject(
				"SELECT LAST_INSERT_ID()",
				Long.class
		);
		jdbcTemplate.update(
				"INSERT INTO workers (user_id) VALUES (?)",
				userId
		);
		Long workerId = jdbcTemplate.queryForObject(
				"SELECT LAST_INSERT_ID()",
				Long.class
		);
		return new WorkloadTestWorker(userId, workerId);
	}

	private WorkloadTestOffer findWorkloadTestOffer(
			long workflowId,
			int sequenceNumber
	) {
		return jdbcTemplate.queryForObject("""
			SELECT workload_transfer_offer_id,
			       offer_token,
			       candidate_worker_id
			FROM workload_transfer_offers
			WHERE workflow_id = ?
			  AND sequence_number = ?
			""", (resultSet, rowNumber) -> new WorkloadTestOffer(
				resultSet.getLong("workload_transfer_offer_id"),
				resultSet.getString("offer_token"),
				resultSet.getLong("candidate_worker_id")
		), workflowId, sequenceNumber);
	}

	private byte[] uuidBytes(UUID uuid) {
		ByteBuffer buffer = ByteBuffer.allocate(16);
		buffer.putLong(uuid.getMostSignificantBits());
		buffer.putLong(uuid.getLeastSignificantBits());
		return buffer.array();
	}

	private int countById(String tableName, String idColumn, Object id) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM " + tableName + " WHERE " + idColumn + " = ?",
				Integer.class,
				id
		);
	}

	private record WorkloadTestWorker(long userId, long workerId) {
	}

	private record WorkloadTestOffer(
			long offerId,
			String offerToken,
			long candidateWorkerId
	) {
	}
}

package com.hunt.otziv;

import com.hunt.otziv.archive.dto.ArchiveRunResult;
import com.hunt.otziv.archive.dto.ManagerArchiveOrderDetailsResponse;
import com.hunt.otziv.archive.dto.ManagerArchiveOrderListItem;
import com.hunt.otziv.archive.service.ManagerArchiveService;
import com.hunt.otziv.archive.service.OrderArchiveDryRunService;
import com.hunt.otziv.manager.dto.api.PageResponse;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.workload_shadow.notification.WorkloadShadowDeliveryOutcome;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowMonitorRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowNotificationStore;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowRunRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferPreferenceRepository;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowProjectionService;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowRunService;
import com.hunt.otziv.workload_shadow.service.WorkloadShadowTransferSimulationService;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OtzivOApplicationTests {

	@Container
	static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
			.withDatabaseName("otziv")
			.withUsername("root")
			.withPassword("root");

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

	@Test
	void contextLoads() {
	}

	@Test
	void flywayMigrationsApplyOnMySql() {
		assertThat(flyway.info().applied()).isNotEmpty();
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
}

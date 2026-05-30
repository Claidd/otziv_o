package com.hunt.otziv;

import com.hunt.otziv.archive.dto.ArchiveRunResult;
import com.hunt.otziv.archive.dto.ManagerArchiveOrderDetailsResponse;
import com.hunt.otziv.archive.dto.ManagerArchiveOrderListItem;
import com.hunt.otziv.archive.service.ManagerArchiveService;
import com.hunt.otziv.archive.service.OrderArchiveDryRunService;
import com.hunt.otziv.manager.dto.api.PageResponse;
import com.hunt.otziv.r_review.services.ReviewService;
import java.nio.ByteBuffer;
import java.time.LocalDate;
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
	private com.hunt.otziv.b_bots.repository.BotsRepository botsRepository;

	@Autowired
	private com.hunt.otziv.u_users.repository.WorkerRepository workerRepository;

	@Autowired
	private OrderArchiveDryRunService orderArchiveDryRunService;

	@Autowired
	private ManagerArchiveService managerArchiveService;

	@Test
	void contextLoads() {
	}

	@Test
	void flywayMigrationsApplyOnMySql() {
		assertThat(flyway.info().applied()).isNotEmpty();
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
			INSERT INTO reviews (review_text, review_publish, review_publish_date, review_vigul, review_order_details, review_bot)
			VALUES ('manager report review', 0, ?, 0, ?, ?)
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

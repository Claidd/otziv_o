package com.hunt.otziv;

import com.hunt.otziv.r_review.services.ReviewService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

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
}

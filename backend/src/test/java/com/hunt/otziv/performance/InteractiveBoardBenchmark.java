package com.hunt.otziv.performance;

import com.hunt.otziv.manager.service.ManagerBoardService;
import com.hunt.otziv.p_products.controller.ApiWorkerBoardController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in: real service/security policy/SQL on synthetic disposable data. */
@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=8",
        "otziv.monitoring.enabled=false", "telegram.bot.registration-enabled=false",
        "spring.main.banner-mode=off"})
@ActiveProfiles("test")
@Import(FinancialScenarioBenchmark.CaptureConfiguration.class)
@Testcontainers
class InteractiveBoardBenchmark {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(FinancialScenarioBenchmark.MYSQL_IMAGE)
            .withDatabaseName("otziv").withUsername("root").withPassword("benchmark-local-only")
            .withCommand("--restrict-fk-on-non-standard-key=OFF");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ManagerBoardService managerBoard;
    @Autowired ApiWorkerBoardController workerBoard;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired com.hunt.otziv.config.metrics.InteractiveRequestMetricsFilter timingFilter;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    final TestingAuthenticationToken admin = new TestingAuthenticationToken("latency-admin", "unused", "ROLE_ADMIN");

    @Test void measuresCompaniesOrdersAndSpecialist() throws Exception {
        context.getBeansOfType(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class)
                .values().forEach(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler::shutdown);
        seed();
        Map<String, Callable<Object>> scenarios = new LinkedHashMap<>();
        scenarios.put("companies", () -> {
            var response = managerBoard.getBoard("companies", "Все", "", 0, 10, "desc", null, admin, admin);
            assertThat(response.companies().content()).hasSize(10);
            return response;
        });
        scenarios.put("orders", () -> {
            var response = managerBoard.getBoard("orders", "Все", "", 0, 10, "desc", null, admin, admin);
            assertThat(response.orders().content()).hasSize(10);
            return response;
        });
        scenarios.put("specialist", () -> {
            var response = workerBoard.getBoard("new", "", 0, 10, "desc", null, admin, admin);
            assertThat(response.orders().content()).hasSize(10);
            return response;
        });
        List<Map<String, Object>> results = new ArrayList<>();
        int iterations = Integer.getInteger("otziv.interactive.iterations", 30);
        for (var scenario : scenarios.entrySet()) {
            List<Double> times = new ArrayList<>();
            List<Long> queries = new ArrayList<>();
            List<Double> sqlTimes = new ArrayList<>();
            for (int i = -5; i < iterations; i++) {
                var sample = new FinancialScenarioBenchmark.Sample();
                SecurityContextHolder.getContext().setAuthentication(admin);
                FinancialScenarioBenchmark.CURRENT.set(sample);
                long start = System.nanoTime();
                try {
                    assertThat(scenario.getValue().call()).isNotNull();
                    if (i >= 0) {
                        times.add((System.nanoTime() - start) / 1e6);
                        queries.add(sample.calls);
                        sqlTimes.add(sample.sqlNanos / 1e6);
                        assertThat(sample.rollbacks).isZero();
                    }
                } finally {
                    FinancialScenarioBenchmark.CURRENT.remove();
                    SecurityContextHolder.clearContext();
                }
            }
            times.sort(Double::compare);
            results.add(Map.of("scenario", scenario.getKey(), "samples", times.size(),
                    "p50Ms", percentile(times, .5), "p95Ms", percentile(times, .95),
                    "queriesMin", Collections.min(queries), "queriesMax", Collections.max(queries),
                    "sqlMeanMs", sqlTimes.stream().mapToDouble(Double::doubleValue).average().orElseThrow()));
        }
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        for (var scenario : scenarios.entrySet()) {
            var request = new org.springframework.mock.web.MockHttpServletRequest("GET",
                    scenario.getKey().equals("specialist") ? "/api/worker/board" : "/api/manager/board");
            request.setParameter("section", scenario.getKey().equals("specialist") ? "new" : scenario.getKey());
            for (int i=0; i<10; i++) {
                SecurityContextHolder.getContext().setAuthentication(admin);
                try {
                    var response = new org.springframework.mock.web.MockHttpServletResponse();
                    timingFilter.doFilter(request, response, (req,res) -> {
                        try { res.getOutputStream().write(json.writeValueAsBytes(scenario.getValue().call())); }
                        catch (Exception failure) { throw new jakarta.servlet.ServletException(failure); }
                    });
                    assertThat(response.getContentAsByteArray()).isNotEmpty();
                } finally { SecurityContextHolder.clearContext(); }
            }
        }
        List<Map<String,Object>> segments = metrics.get("otziv.service.segment.duration").timers().stream()
                .map(timer -> Map.<String,Object>of("tags",timer.getId().getTags().toString(),"samples",timer.count(),
                        "meanMs",timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS),"maxMs",timer.max(java.util.concurrent.TimeUnit.MILLISECONDS)))
                .toList();
        var report = Map.of("measuredAt", Instant.now().toString(), "source",
                System.getProperty("otziv.interactive.source", "unspecified"), "results", results,
                "segments", segments,
                "fixture", "1000-companies/1000-orders/20-workers", "mysqlImage", FinancialScenarioBenchmark.MYSQL_IMAGE);
        Path path = Path.of("target/performance/interactive-board.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        verifyPreparedProgressAndIdentityScope();
        verifyCabinetAndTodayReadModels();
        verifyRecoveryBatchMatchesSingleOrderChecks();
    }

    void verifyRecoveryBatchMatchesSingleOrderChecks() {
        var gate = context.getBean(com.hunt.otziv.review_recovery.service.ReviewRecoveryGateService.class);
        jdbc.update("""
                INSERT INTO review_recovery_batches(review_recovery_batch_id,review_recovery_batch_order,
                    review_recovery_batch_status,review_recovery_batch_created_at,review_recovery_batch_updated_at)
                VALUES(900001,300001,'OPEN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
        jdbc.update("""
                INSERT INTO review_recovery_tasks(review_recovery_task_id,review_recovery_task_batch,
                    review_recovery_task_order,review_recovery_task_status,review_recovery_task_recovery_text,
                    review_recovery_task_scheduled_date,review_recovery_task_created_at,review_recovery_task_updated_at)
                VALUES(900001,900001,300001,'PLANNED','fixture',CURRENT_DATE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
                      (900002,900001,300002,'DONE','fixture',CURRENT_DATE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
        List<Long> ids = List.of(300001L,300002L,300003L);
        for (String batchStatus : List.of("OPEN", "COMPLETED", "ARCHIVED", "OPEN")) {
            jdbc.update("UPDATE review_recovery_batches SET review_recovery_batch_status=? WHERE review_recovery_batch_id=900001",batchStatus);
            Set<Long> expected = ids.stream().filter(gate::hasActiveRecoveryTasks).collect(java.util.stream.Collectors.toSet());
            assertThat(gate.activeRecoveryOrderIds(ids)).isEqualTo(expected);
            assertThat(expected).hasSize(batchStatus.equals("OPEN") ? 1 : 0);
        }
        jdbc.update("UPDATE review_recovery_tasks SET review_recovery_task_status='CANCELLED' WHERE review_recovery_task_id=900001");
        assertThat(gate.activeRecoveryOrderIds(ids)).isEmpty();
    }

    void verifyCabinetAndTodayReadModels() throws Exception {
        jdbc.update("INSERT INTO users_roles(user_id,role_id) SELECT 100000,id FROM roles WHERE name='ROLE_ADMIN'");
        jdbc.update("INSERT INTO users_roles(user_id,role_id) SELECT 100001,id FROM roles WHERE name='ROLE_MANAGER'");
        jdbc.update("INSERT INTO workers_users(user_id,worker_id) SELECT 100001,worker_id FROM workers WHERE worker_id BETWEEN 210001 AND 210020");
        jdbc.update("INSERT INTO managers_users(user_id,manager_id) SELECT user_id,200001 FROM workers WHERE worker_id BETWEEN 210001 AND 210020");
        var manager = new TestingAuthenticationToken("latency-1", "unused", "ROLE_MANAGER", "ROLE_WORKER");
        var cabinet = context.getBean(com.hunt.otziv.admin.controller.ApiCabinetController.class);
        var control = context.getBean(com.hunt.otziv.manager_control.service.ManagerControlService.class);
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Irkutsk"));
        context.getBean(com.hunt.otziv.manager_control.service.ManagerControlSnapshotJob.class).refresh();
        List<Map<String,Object>> results = new ArrayList<>();
        for (var identity : List.of(admin, manager)) {
            SecurityContextHolder.getContext().setAuthentication(identity);
            try {
                Map<String,Callable<Object>> reads = new LinkedHashMap<>();
                reads.put("profile-refresh", () -> cabinet.profile(identity, today, true));
                reads.put("profile-hit", () -> cabinet.profile(identity, today, false));
                reads.put("team-refresh", () -> cabinet.team(identity, identity, today, today.withDayOfMonth(1), true));
                reads.put("team-hit", () -> cabinet.team(identity, identity, today, today.withDayOfMonth(1), false));
                reads.put("today", () -> control.today(identity, identity));
                for (var read : reads.entrySet()) {
                    List<Double> times = new ArrayList<>();
                    long maximumQueries = 0;
                    for (int i = -3; i < 24; i++) {
                        var sample = new FinancialScenarioBenchmark.Sample();
                        FinancialScenarioBenchmark.CURRENT.set(sample);
                        long started = System.nanoTime();
                        Object response;
                        try { response = read.getValue().call(); }
                        finally { FinancialScenarioBenchmark.CURRENT.remove(); }
                        if (i >= 0) {
                            times.add((System.nanoTime()-started)/1e6);
                            maximumQueries = Math.max(maximumQueries, sample.calls);
                        }
                        assertThat(sample.rollbacks).isZero();
                        if (response instanceof com.hunt.otziv.admin.controller.ApiCabinetController.TeamResponse team) {
                            assertThat(team.workers().stream().map(worker -> worker.getId())
                                    .filter(id -> id>=210001 && id<=210020).toList()).hasSize(20);
                            if (identity==manager) assertThat(team.workers().size()).isEqualTo(20);
                            assertThat(team.canEditUsers()).isEqualTo(identity==admin);
                        } else if (response instanceof com.hunt.otziv.admin.controller.ApiCabinetController.CabinetProfileResponse profile) {
                            assertThat(profile.date()).isEqualTo(today);
                            assertThat(profile.user()).isNotNull();
                        } else if (response instanceof com.hunt.otziv.manager_control.dto.ManagerControlSummaryResponse summary) {
                            var managerIds = summary.managers().stream()
                                    .map(com.hunt.otziv.manager_control.dto.ManagerControlManagerResponse::managerId).toList();
                            assertThat(managerIds).contains(200001L);
                            if (identity==manager) assertThat(managerIds).containsExactly(200001L);
                            assertThat(summary.generatedAt()).isNotNull();
                        }
                    }
                    times.sort(Double::compare);
                    results.add(Map.of("scenario",read.getKey(),"role",identity==admin?"admin":"manager",
                            "samples",times.size(),"p95Ms",percentile(times,.95),"maxQueries",maximumQueries));
                }
            } finally { SecurityContextHolder.clearContext(); }
        }
        SecurityContextHolder.getContext().setAuthentication(manager);
        try {
            // A committed team reassignment must take effect even with a previously warm DTO.
            var before = cabinet.team(manager,manager,today,null,false);
            assertThat(before.workers()).hasSize(20);
            jdbc.update("DELETE FROM workers_users WHERE user_id=100001 AND worker_id=210020");
            jdbc.update("DELETE FROM managers_users WHERE user_id=100020 AND manager_id=200001");
            assertThat(cabinet.team(manager,manager,today,null,false).workers()).hasSize(19);
            // A historical date uses a distinct key and never borrows today's prepared result.
            assertThat(cabinet.profile(manager,today.minusDays(1),false).date()).isEqualTo(today.minusDays(1));
        } finally { SecurityContextHolder.clearContext(); }
        Files.writeString(Path.of("target/performance/interactive-cabinet.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }

    void verifyPreparedProgressAndIdentityScope() {
        var scope=context.getBean(com.hunt.otziv.u_users.api.CabinetCacheScope.class);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        String committed=scope.fingerprint();
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE users SET active=0 WHERE id=100001");
            assertThat(scope.fingerprint()).isNotEqualTo(committed);
            status.setRollbackOnly();
        });
        assertThat(scope.fingerprint()).isEqualTo(committed);
        var progress=context.getBean(com.hunt.otziv.worker_performance.service.StaffDailyProgressService.class);
        var today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Irkutsk"));
        assertThat(progress.refreshSnapshotBatch(210000,today)).isEqualTo(210020);
        var worker=new com.hunt.otziv.u_users.model.Worker(); worker.setId(210001L);
        var prepared=progress.workerProgressSnapshotByWorkers(List.of(worker),today).get(210001L);
        assertThat(prepared.calculatedAt()).isNotNull();
        jdbc.update("UPDATE worker_daily_performance SET updated_at=CURRENT_TIMESTAMP-INTERVAL 10 MINUTE WHERE worker_id=210001 AND progress_date=?",today);
        assertThat(progress.workerProgressSnapshotByWorkers(List.of(worker),today).get(210001L).updating()).isTrue();
        progress.refreshSnapshotBatch(210000,today);
        assertThat(jdbc.queryForObject("SELECT updated_at > CURRENT_TIMESTAMP-INTERVAL 1 MINUTE FROM worker_daily_performance WHERE worker_id=210001 AND progress_date=?",Boolean.class,today)).isTrue();
        progress.rebuildMonthlyAggregates(today.withDayOfMonth(1),false);
    }
    static double percentile(List<Double> values, double percentile) {
        return values.get(Math.min(values.size() - 1, (int)Math.ceil(values.size() * percentile) - 1));
    }
    void seed() {
        jdbc.update("INSERT INTO users(id,username,password,fio,email,phone_number,active,create_time) VALUES (100000,'latency-admin','unused','Benchmark','latency-admin@example.test','+70000000000',1,CURRENT_DATE)");
        jdbc.execute("CREATE TABLE latency_sequence(n INT PRIMARY KEY)");
        jdbc.batchUpdate("INSERT INTO latency_sequence VALUES (?)", java.util.stream.IntStream.rangeClosed(1,1000).mapToObj(i -> new Object[]{i}).toList());
        jdbc.update("INSERT INTO users(id,username,password,fio,email,phone_number,active,create_time) SELECT 100000+n,CONCAT('latency-',n),'unused',CONCAT('Benchmark ',n),CONCAT('latency-',n,'@example.test'),CONCAT('+700000',LPAD(n,5,'0')),1,CURRENT_DATE FROM latency_sequence WHERE n<=20");
        jdbc.update("INSERT INTO managers(manager_id,user_id) VALUES (200001,100001)");
        jdbc.update("INSERT INTO workers(worker_id,user_id) SELECT 210000+n,100000+n FROM latency_sequence WHERE n<=20");
        jdbc.update("INSERT INTO companies(company_id,company_title,company_phone,company_city,company_user,company_manager,company_status,update_status,create_date) SELECT 400000+n,CONCAT('Benchmark ',n),CONCAT('+710000',LPAD(n,5,'0')),'Benchmark',100000,200001,(SELECT company_status_id FROM company_status ORDER BY company_status_id LIMIT 1),CURRENT_DATE,CURRENT_DATE FROM latency_sequence");
        jdbc.update("INSERT INTO orders(order_id,order_created,order_changed,order_status,order_amount,order_counter,order_sum,order_complete,order_waiting_for_client,order_manager,order_company,order_worker) SELECT 300000+n,CURRENT_DATE,CURRENT_DATE,(SELECT order_status_id FROM order_statuses WHERE order_status_title='Новый' LIMIT 1),1,1,100,0,0,200001,400000+n,210001 FROM latency_sequence");
        jdbc.update("UPDATE companies SET company_active=1,company_counter_no_pay=0,company_counter_pay=0 WHERE company_id>400000");
        jdbc.update("INSERT INTO users_roles(user_id,role_id) SELECT 100000+n,(SELECT id FROM roles WHERE name='ROLE_WORKER' LIMIT 1) FROM latency_sequence WHERE n<=20");
    }
}

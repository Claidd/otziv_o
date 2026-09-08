package com.hunt.otziv.performance;

import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.manager_control.service.ManagerControlService;
import com.hunt.otziv.payments.service.PaymentLinkService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit benchmark target (-Dtest=FinancialScenarioBenchmark), never production data/provider traffic. */
@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=8", "spring.datasource.hikari.minimum-idle=2",
        "otziv.monitoring.enabled=false", "telegram.bot.registration-enabled=false", "spring.main.banner-mode=off"})
@ActiveProfiles("test")
@Import(FinancialScenarioBenchmark.CaptureConfiguration.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FinancialScenarioBenchmark {
    static final String MYSQL_IMAGE = "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383";
    @Container static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withDatabaseName("otziv").withUsername("root").withPassword("benchmark-local-only")
            .withCommand("--restrict-fk-on-non-standard-key=OFF");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @Autowired CommonBillingService billing;
    @Autowired PaymentLinkService payments;
    @Autowired ManagerControlService management;
    @Autowired org.springframework.context.ApplicationContext context;
    static final ThreadLocal<Sample> CURRENT = new ThreadLocal<>();
    static final Map<String, BoundQuery> QUERIES = new ConcurrentHashMap<>();
    static final TestingAuthenticationToken ADMIN = new TestingAuthenticationToken("benchmark-admin", "unused", "ROLE_ADMIN");
    static final class Sample { long calls; long sqlNanos; long commits; long rollbacks; }
    record Observation(double elapsedMs, long queries, double sqlMs, long commits, long rollbacks) {}
    record Binding(Method method, Object[] arguments) {}
    record BoundQuery(String sql, List<Binding> bindings) {}

    @Test void measuresActualServiceTransactionsWithHibernateAndMySql() throws Exception {
        context.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)
                .values().forEach(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor::destroy);
        context.getBeansOfType(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class)
                .values().forEach(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler::shutdown);
        seed();
        var actions = new LinkedHashMap<String, Callable<Object>>();
        actions.put("common-invoice-board", () -> {
            var result = billing.managerBoardPage("Все", "", null, null, "desc", 0, 20);
            assertThat(result.cards()).hasSize(20);
            assertThat(result.totalCards()).isEqualTo(1000);
            return result.cards().size();
        });
        actions.put("payment-admin-board", () -> {
            var result = payments.adminLinks(0, 20, "all", "", null, null, "LIVE");
            assertThat(result.items()).hasSize(20);
            assertThat(result.totalElements()).isEqualTo(10_000);
            return result.items().size();
        });
        actions.put("manager-control-details", () -> {
            var result = management.managerDetails(200001L, ADMIN, ADMIN);
            assertThat(result.managerId()).isEqualTo(200001L);
            assertThat(result.items()).hasSize(50);
            return result.items().size();
        });
        var results = new ArrayList<Map<String, Object>>();
        int iterations = Integer.getInteger("otziv.benchmark.iterations", 24);
        for (var scenario : actions.entrySet()) {
            for (int warmup = 0; warmup < 8; warmup++) measure(scenario.getValue());
            for (int concurrency : new int[]{1, 4, 8}) {
                var samples = Collections.synchronizedList(new ArrayList<Observation>());
                var start = new CountDownLatch(1);
                try (var workers = Executors.newFixedThreadPool(concurrency)) {
                    var tasks = new ArrayList<Callable<Void>>();
                    for (int worker = 0; worker < concurrency; worker++) tasks.add(() -> {
                        start.await();
                        for (int i = 0; i < iterations; i++) samples.add(measure(scenario.getValue()));
                        return null;
                    });
                    var submitted = tasks.stream().map(workers::submit).toList();
                    long began = System.nanoTime();
                    start.countDown();
                    for (var task : submitted) task.get();
                    double seconds = (System.nanoTime() - began) / 1_000_000_000.0;
                    var latencies = samples.stream().map(Observation::elapsedMs).sorted().toList();
                    var row = new LinkedHashMap<String, Object>();
                    row.put("scenario", scenario.getKey()); row.put("concurrency", concurrency);
                    row.put("samples", samples.size()); row.put("p50Ms", percentile(latencies, .50));
                    row.put("p95Ms", percentile(latencies, .95)); row.put("maxMs", latencies.getLast());
                    row.put("completedPerSecond", samples.size() / seconds);
                    row.put("queriesMin", samples.stream().mapToLong(Observation::queries).min().orElseThrow());
                    row.put("queriesMax", samples.stream().mapToLong(Observation::queries).max().orElseThrow());
                    row.put("sqlMeanMs", samples.stream().mapToDouble(Observation::sqlMs).average().orElseThrow());
                    row.put("commits", samples.stream().mapToLong(Observation::commits).sum());
                    row.put("rollbacks", samples.stream().mapToLong(Observation::rollbacks).sum());
                    assertThat((long) row.get("queriesMin")).isPositive();
                    assertThat((long) row.get("rollbacks")).isZero();
                    if (Boolean.getBoolean("otziv.benchmark.query-budgets")) {
                        long ceiling = switch (scenario.getKey()) {
                            case "common-invoice-board" -> 128;
                            case "payment-admin-board" -> 46;
                            case "manager-control-details" -> 58;
                            default -> throw new IllegalStateException("Unreviewed benchmark scenario");
                        };
                        assertThat((long) row.get("queriesMax")).as(scenario.getKey() + " SQL regression budget")
                                .isLessThanOrEqualTo(ceiling);
                    }
                    results.add(row);
                }
            }
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("schema", "otziv-scenario-benchmark-v1"); report.put("measuredAt", Instant.now().toString());
        report.put("sourceLabel", System.getProperty("otziv.benchmark.source", "unspecified"));
        report.put("harnessSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(Path.of("src/test/java/com/hunt/otziv/performance/FinancialScenarioBenchmark.java")))));
        report.put("mysqlImage", MYSQL_IMAGE); report.put("fixture", "v1:1000-invoices/10000-links/20-managers/50-current-items");
        report.put("jdbcPoolSize", 8); report.put("results", results); report.put("explain", explain());
        Path output = Path.of("target/performance/scenario-benchmark.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private static double percentile(List<Double> sorted, double p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * p) - 1));
    }
    private Observation measure(Callable<Object> action) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(ADMIN);
        Sample sample = new Sample(); CURRENT.set(sample);
        long start = System.nanoTime();
        try {
            assertThat(action.call()).isNotNull();
            return new Observation((System.nanoTime() - start) / 1_000_000.0, sample.calls,
                    sample.sqlNanos / 1_000_000.0, sample.commits, sample.rollbacks);
        } finally { CURRENT.remove(); SecurityContextHolder.clearContext(); }
    }

    private List<Map<String, String>> explain() throws Exception {
        var result = new ArrayList<Map<String, String>>();
        try (var connection = source.getConnection()) {
            for (var query : QUERIES.values().stream().sorted(java.util.Comparator.comparing(BoundQuery::sql)).toList()) {
                String sql = query.sql().stripLeading();
                if (!sql.toLowerCase(java.util.Locale.ROOT).startsWith("select")) continue;
                try (var statement = connection.prepareStatement("EXPLAIN FORMAT=JSON " + sql)) {
                    for (var binding : query.bindings()) invoke(binding.method(), statement, binding.arguments());
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        result.add(Map.of("sql", sql, "plan", rows.getString(1)));
                    }
                }
            }
        }
        assertThat(result).isNotEmpty();
        return result;
    }

    private void seed() {
        jdbc.execute("CREATE TABLE benchmark_sequence(n INT PRIMARY KEY)");
        var values = java.util.stream.IntStream.rangeClosed(1, 10_000).mapToObj(n -> new Object[]{n}).toList();
        jdbc.batchUpdate("INSERT INTO benchmark_sequence VALUES (?)", values);
        jdbc.update("INSERT INTO users(id,username,password,fio,email,phone_number,active,create_time) SELECT 100000+n,CONCAT('bench-',n),'unused',CONCAT('Benchmark ',n),CONCAT('bench-',n,'@example.test'),CONCAT('+700000',LPAD(n,5,'0')),1,CURRENT_DATE FROM benchmark_sequence WHERE n<=20");
        jdbc.update("INSERT INTO roles(name) SELECT 'ROLE_MANAGER' WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name='ROLE_MANAGER')");
        jdbc.update("INSERT INTO users_roles(user_id,role_id) SELECT 100000+n,(SELECT id FROM roles WHERE name='ROLE_MANAGER' ORDER BY id LIMIT 1) FROM benchmark_sequence WHERE n<=20");
        jdbc.update("INSERT INTO managers(manager_id,user_id) SELECT 200000+n,100000+n FROM benchmark_sequence WHERE n<=20");
        Long status = jdbc.queryForObject("SELECT order_status_id FROM order_statuses WHERE order_status_title='Не оплачено' LIMIT 1", Long.class);
        jdbc.update("INSERT INTO orders(order_id,order_created,order_changed,order_status,order_amount,order_counter,order_sum,order_complete,order_waiting_for_client,order_manager) SELECT 300000+n,CURRENT_DATE,CURRENT_DATE,?,1,1,100,0,0,200001 FROM benchmark_sequence", status);
        jdbc.update("INSERT INTO payment_links(token,order_id,amount_kopecks,description,status,payment_method,expires_at) SELECT CONCAT('bench-payment-',n),300000+n,10000,'Benchmark','EXPIRED','BANK_FORM',CURRENT_TIMESTAMP - INTERVAL 1 DAY FROM benchmark_sequence");
        jdbc.update("INSERT INTO common_billing_accounts(account_id,account_name,manager_id) SELECT 400000+n,CONCAT('Benchmark account ',n),200001 FROM benchmark_sequence WHERE n<=1000");
        jdbc.update("INSERT INTO common_invoices(invoice_id,account_id,token,title,status,amount_kopecks) SELECT 500000+n,400000+n,CONCAT('bench-invoice-',n),'Benchmark invoice','UNPAID',10000 FROM benchmark_sequence WHERE n<=1000");
        jdbc.update("INSERT INTO common_invoice_orders(invoice_id,order_id,amount_kopecks,ready,unpaid) SELECT 500000+n,300000+n,10000,1,1 FROM benchmark_sequence WHERE n<=1000");
        jdbc.update("INSERT INTO manager_daily_controls(daily_control_id,control_date,manager_id,manager_user_id,control_status) SELECT 600000+n,CURRENT_DATE,200000+n,100000+n,'NOT_STARTED' FROM benchmark_sequence WHERE n<=20");
        jdbc.update("INSERT INTO manager_daily_control_items(control_id,item_key,item_type,reason_code,label,item_count,severity,group_code,item_status) SELECT 600001,CONCAT('bench-',n),'PROBLEM','BENCHMARK',CONCAT('Benchmark item ',n),1,'WARNING','ACTION','OPEN' FROM benchmark_sequence WHERE n<=50");
        jdbc.update("INSERT INTO manager_daily_controls(control_date,manager_id,manager_user_id,control_status) SELECT CURRENT_DATE - INTERVAL n DAY,200001,100001,'NOT_STARTED' FROM benchmark_sequence WHERE n<=1000");
    }

    @TestConfiguration(proxyBeanMethods = false) static class CaptureConfiguration {
        @Bean static BeanFactoryPostProcessor stopSchedulers() {
            return factory -> {
                if (factory instanceof org.springframework.beans.factory.support.BeanDefinitionRegistry registry
                        && registry.containsBeanDefinition("org.springframework.context.annotation.internalScheduledAnnotationProcessor")) {
                    registry.removeBeanDefinition("org.springframework.context.annotation.internalScheduledAnnotationProcessor");
                }
            };
        }
        @Bean static BeanPostProcessor recordJdbc() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource datasource)) return bean;
                    return new DelegatingDataSource(datasource) {
                        @Override public Connection getConnection() throws java.sql.SQLException { return connection(super.getConnection()); }
                        @Override public Connection getConnection(String user, String password) throws java.sql.SQLException { return connection(super.getConnection(user, password)); }
                    };
                }
            };
        }
    }
    private static Connection connection(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
            Object result = invoke(method, delegate, arguments);
            Sample sample = CURRENT.get();
            if (sample != null && method.getName().equals("commit")) sample.commits++;
            if (sample != null && method.getName().equals("rollback")) sample.rollbacks++;
            if (result instanceof PreparedStatement statement && arguments != null && arguments[0] instanceof String sql) {
                var bindings = new LinkedHashMap<Integer, Binding>();
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, call, args) -> {
                    if (call.getName().startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index)
                        bindings.put(index, new Binding(call, args.clone()));
                    if (call.getName().equals("clearParameters")) bindings.clear();
                    Sample current = CURRENT.get();
                    boolean execute = current != null && call.getName().startsWith("execute");
                    long began = System.nanoTime();
                    try { return invoke(call, statement, args); }
                    finally { if (execute) { current.calls++; current.sqlNanos += System.nanoTime() - began;
                        QUERIES.putIfAbsent(sql, new BoundQuery(sql, List.copyOf(bindings.values()))); } }
                });
            }
            return result;
        });
    }
    private static Object invoke(Method method, Object target, Object[] arguments) throws Exception {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception exception) throw exception;
            if (error.getCause() instanceof Error fatal) throw fatal;
            throw error;
        }
    }
}

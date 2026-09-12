package com.hunt.otziv.performance;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Manual-only companion; default pilot is plan-only, explicit full iterations retain the parent measurement. */
class FinancialCtePlanEvidence extends FinancialScenarioBenchmark {
    @Override
    @Test
    void measuresActualServiceTransactionsWithHibernateAndMySql() throws Exception {
        String previousIterations = System.getProperty("otziv.benchmark.iterations");
        String previousSource = System.getProperty("otziv.benchmark.source");
        String evidenceSource = System.getProperty("otziv.benchmark.source",
                "plan-only:" + System.getProperty("otziv.cte.source", "unspecified"));
        int evidenceIterations = Integer.getInteger("otziv.benchmark.cte.iterations", 1);
        assertThat(evidenceIterations).isBetween(1, 1000);
        Path harness = Path.of("src/test/java/com/hunt/otziv/performance/FinancialScenarioBenchmark.java");
        String harnessHash = sha256(harness);
        assertThat(harnessHash).isEqualTo("50f9009d186b3ed062531d14bf3a348b099549fb4f91420dc3c6f1aae00e3984");
        try {
            System.setProperty("otziv.benchmark.iterations", Integer.toString(evidenceIterations));
            System.setProperty("otziv.benchmark.source", evidenceSource);
            QUERIES.clear();
            super.measuresActualServiceTransactionsWithHibernateAndMySql();
        } finally {
            restoreProperty("otziv.benchmark.iterations", previousIterations);
            restoreProperty("otziv.benchmark.source", previousSource);
        }
        assertThat(sha256(harness)).isEqualTo(harnessHash);
        List<BoundQuery> queries = QUERIES.values().stream()
                .filter(query -> query.sql().stripLeading().toLowerCase(Locale.ROOT).startsWith("with"))
                .sorted(Comparator.comparing(BoundQuery::sql)).toList();
        // The page and both counters now share one statement and one read snapshot.
        // Require every result branch in the actual captured query, then explain that query.
        assertThat(queries).as("One combined page and counter CTE captured from service calls").hasSize(1);
        assertThat(normalized(queries.getFirst().sql())).contains(
                "page_rows as (select invoice_id, updated_at from board_rows",
                "limit ? offset ?",
                "select 'card' as kind, invoice_id as value, updated_at as sort_at from page_rows",
                "union all select 'total', count(*), null from board_rows",
                "union all select 'linked', count(distinct item.order_id), null");
        List<Map<String, Object>> plans = new ArrayList<>();
        try (var connection = source.getConnection()) {
            for (BoundQuery query : queries) {
                try (var statement = connection.prepareStatement("EXPLAIN FORMAT=JSON " + query.sql())) {
                    for (Binding binding : query.bindings()) {
                        try {
                            binding.method().invoke(statement, binding.arguments());
                        } catch (InvocationTargetException exception) {
                            if (exception.getCause() instanceof Exception cause) throw cause;
                            throw exception;
                        }
                    }
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("sql", query.sql());
                        entry.put("parameterSetters", query.bindings().stream().map(binding -> {
                            Map<String, Object> values = new LinkedHashMap<>();
                            values.put("method", binding.method().getName());
                            values.put("arguments", java.util.Arrays.stream(binding.arguments())
                                    .map(argument -> argument == null ? null : String.valueOf(argument)).toList());
                            return values;
                        }).toList());
                        entry.put("plan", new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.getString(1)));
                        plans.add(entry);
                    }
                }
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "otziv-cte-plan-evidence-v1");
        report.put("sourceLabel", evidenceSource);
        report.put("iterationsPerWorker", evidenceIterations);
        report.put("capturedAt", Instant.now().toString());
        report.put("harnessSha256", harnessHash);
        report.put("companionSha256", sha256(Path.of("src/test/java/com/hunt/otziv/performance/FinancialCtePlanEvidence.java")));
        report.put("mysqlImage", MYSQL_IMAGE);
        report.put("fixture", "v1:1000-invoices/10000-links/20-managers/50-current-items");
        report.put("evidencePurpose", "Optimizer estimates for actual captured CTE SQL and bindings; not EXPLAIN ANALYZE or comparative timing evidence");
        report.put("plans", plans);
        Path output = Path.of("target/performance/cte-plans.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private static String normalized(String sql) {
        return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static void restoreProperty(String key, String previous) {
        if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
    }
}

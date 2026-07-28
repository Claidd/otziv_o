package com.hunt.otziv.workload_shadow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class WorkloadShadowInsertSelectUpsertContractTest {

    private static final Pattern INSERT_SELECT = Pattern.compile(
            "\\binsert\\s+into\\s+([a-z0-9_]+)\\s*\\((.*?)\\)\\s*select\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final String ON_DUPLICATE_KEY_UPDATE = "on duplicate key update";
    private static final List<Class<?>> RUNTIME_REPOSITORIES = List.of(
            WorkloadShadowEventRepository.class,
            WorkloadShadowMonitorRepository.class,
            WorkloadShadowProjectionRepository.class,
            WorkloadShadowRecalculationLockRepository.class,
            WorkloadShadowRunRepository.class,
            WorkloadShadowSettingsRepository.class,
            WorkloadShadowTransferRepository.class,
            WorkloadShadowWorkerDailyRepository.class,
            WorkloadTransferGraphRepository.class,
            WorkloadTransferPreferenceRepository.class
    );

    @Test
    void insertSelectUpsertsNeverReadAnUnqualifiedTargetColumn() {
        List<QueryContract> contracts = insertSelectUpserts();
        assertThat(contracts).isNotEmpty();

        for (QueryContract contract : contracts) {
            assertNoAmbiguousTargetRead(contract);
        }
    }

    @Test
    void dailySnapshotUpsertQualifiesTheExistingFinalizedRow() throws Exception {
        Method method = WorkloadShadowProjectionRepository.class.getDeclaredMethod(
                "upsertDailySnapshots",
                String.class,
                boolean.class,
                java.time.LocalDateTime.class
        );
        String sql = normalized(method.getAnnotation(Query.class).value());

        assertThat(sql).contains(
                "workload_shadow_worker_daily.finalized = true",
                "workload_shadow_worker_daily.worker_user_id",
                "workload_shadow_worker_daily.manager_id",
                "workload_shadow_worker_daily.completed_units",
                "workload_shadow_worker_daily.last_snapshot_at",
                "workload_shadow_worker_daily.finalized_at"
        );
    }

    private List<QueryContract> insertSelectUpserts() {
        List<QueryContract> result = new ArrayList<>();
        for (Class<?> repositoryType : RUNTIME_REPOSITORIES) {
            for (Method method : repositoryType.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null) {
                    continue;
                }
                String normalized = normalized(query.value());
                if (!normalized.contains("insert into ")
                        || !normalized.contains(" select ")
                        || !normalized.contains(ON_DUPLICATE_KEY_UPDATE)) {
                    continue;
                }
                result.add(new QueryContract(
                        repositoryType.getSimpleName() + "." + method.getName(),
                        query.value()
                ));
            }
        }
        return List.copyOf(result);
    }

    private void assertNoAmbiguousTargetRead(QueryContract contract) {
        String lowerSql = contract.sql().toLowerCase(Locale.ROOT);
        Matcher insert = INSERT_SELECT.matcher(lowerSql);
        assertThat(insert.find())
                .as(contract.name() + ": не удалось разобрать INSERT ... SELECT")
                .isTrue();

        String targetTable = insert.group(1);
        List<String> targetColumns = Arrays.stream(insert.group(2).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        int updateStart = lowerSql.indexOf(ON_DUPLICATE_KEY_UPDATE);
        assertThat(updateStart)
                .as(contract.name() + ": отсутствует ON DUPLICATE KEY UPDATE")
                .isGreaterThanOrEqualTo(0);

        String updateClause = lowerSql.substring(
                updateStart + ON_DUPLICATE_KEY_UPDATE.length()
        );
        String readsOnly = updateClause
                .replaceAll("(?i)values\\s*\\(\\s*[a-z0-9_]+\\s*\\)", "")
                .replaceAll(
                        "(?i)\\b" + Pattern.quote(targetTable) + "\\.[a-z0-9_]+\\b",
                        ""
                )
                .replaceAll("(?im)^\\s*[a-z0-9_]+\\s*=", "");

        for (String targetColumn : targetColumns) {
            Pattern bareReference = Pattern.compile(
                    "(?i)(?<![a-z0-9_.])"
                            + Pattern.quote(targetColumn)
                            + "(?![a-z0-9_])"
            );
            assertThat(bareReference.matcher(readsOnly).find())
                    .as(contract.name() + ": target-колонка " + targetColumn
                            + " читается без квалификатора и может конфликтовать "
                            + "с колонкой источника SELECT/JSON_TABLE")
                    .isFalse();
        }
    }

    private String normalized(String value) {
        return value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private record QueryContract(String name, String sql) {
    }
}

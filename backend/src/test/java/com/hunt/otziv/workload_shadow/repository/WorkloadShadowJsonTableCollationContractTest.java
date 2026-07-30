package com.hunt.otziv.workload_shadow.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class WorkloadShadowJsonTableCollationContractTest {

    private static final String WORKLOAD_TABLE_CHARACTER_DEFINITION =
            "character set utf8mb4 collate utf8mb4_unicode_ci";
    private static final String APP_SETTINGS_CHARACTER_DEFINITION =
            "character set utf8mb4 collate utf8mb4_0900_ai_ci";
    private static final Pattern JSON_CHARACTER_COLUMN = Pattern.compile(
            "\\b[a-z0-9_]+\\s+"
                    + "(?:varchar\\s*\\(\\s*\\d+\\s*\\)|char\\s*\\(\\s*\\d+\\s*\\)"
                    + "|longtext|mediumtext|text)\\s+"
                    + "(.*?)\\bpath\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final List<Class<?>> RUNTIME_REPOSITORIES = List.of(
            WorkloadShadowEventRepository.class,
            WorkloadShadowMonitorRepository.class,
            WorkloadShadowProjectionRepository.class,
            WorkloadShadowRecalculationLockRepository.class,
            WorkloadShadowRunRepository.class,
            WorkloadShadowSettingsRepository.class,
            WorkloadLiveSettingsRepository.class,
            WorkloadShadowTransferRepository.class,
            WorkloadShadowWorkerDailyRepository.class,
            WorkloadTransferOfferRepository.class,
            WorkloadTransferWorkflowRepository.class,
            WorkloadTransferGraphRepository.class,
            WorkloadTransferPreferenceRepository.class
    );

    @Test
    void everyJsonTableCharacterColumnUsesTheDatabaseCollation() {
        int checkedColumns = 0;
        for (Class<?> repositoryType : RUNTIME_REPOSITORIES) {
            for (Method method : repositoryType.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null || !normalized(query.value()).contains("json_table(")) {
                    continue;
                }
                Matcher column = JSON_CHARACTER_COLUMN.matcher(query.value());
                String requiredCharacterDefinition =
                        usesAppSettingsCollation(repositoryType)
                                ? APP_SETTINGS_CHARACTER_DEFINITION
                                : WORKLOAD_TABLE_CHARACTER_DEFINITION;
                while (column.find()) {
                    checkedColumns++;
                    assertThat(normalized(column.group(1)))
                            .as(repositoryType.getSimpleName() + "." + method.getName()
                                    + ": строковая JSON_TABLE-колонка должна использовать "
                                    + "ту же collation, что runtime-таблицы")
                            .contains(requiredCharacterDefinition);
                }
            }
        }
        assertThat(checkedColumns).isGreaterThan(0);
    }

    @Test
    void everyCurrentJsonStringJoinToATargetTableIsCovered() throws Exception {
        assertCollatedComparison(
                WorkloadShadowSettingsRepository.class.getDeclaredMethod(
                        "updateAllWithRevision",
                        String.class,
                        String.class,
                        String.class,
                        long.class
                ),
                "setting_key varchar(100) " + APP_SETTINGS_CHARACTER_DEFINITION,
                "requested_setting.setting_key = target_setting.setting_key"
        );
        assertCollatedComparison(
                WorkloadLiveSettingsRepository.class.getDeclaredMethod(
                        "updateAllWithRevision",
                        String.class,
                        String.class,
                        String.class,
                        long.class
                ),
                "setting_key varchar(100) " + APP_SETTINGS_CHARACTER_DEFINITION,
                "requested_setting.setting_key = target_setting.setting_key"
        );
        assertCollatedComparison(
                WorkloadShadowTransferRepository.class.getDeclaredMethod(
                        "deleteStaleCandidates",
                        String.class,
                        String.class
                ),
                "case_key varchar(160) " + WORKLOAD_TABLE_CHARACTER_DEFINITION,
                "case_row.case_key = transfer_case.case_key",
                "candidate_row.case_key = transfer_case.case_key"
        );
        assertCollatedComparison(
                WorkloadShadowTransferRepository.class.getDeclaredMethod(
                        "upsertCandidates",
                        String.class
                ),
                "case_key varchar(160) " + WORKLOAD_TABLE_CHARACTER_DEFINITION,
                "transfer_case.case_key = candidate_row.case_key"
        );
        assertCollatedComparison(
                WorkloadShadowTransferRepository.class.getDeclaredMethod(
                        "upsertEvents",
                        String.class,
                        LocalDateTime.class,
                        LocalDateTime.class
                ),
                "case_key varchar(160) " + WORKLOAD_TABLE_CHARACTER_DEFINITION,
                "transfer_case.case_key = event_row.case_key"
        );
    }

    private void assertCollatedComparison(
            Method method,
            String collatedColumn,
            String... comparisons
    ) {
        Query query = method.getAnnotation(Query.class);
        assertThat(query)
                .as(method.getDeclaringClass().getSimpleName() + "." + method.getName()
                        + " должен иметь @Query")
                .isNotNull();
        String sql = normalized(query.value());
        assertThat(sql).contains(collatedColumn);
        assertThat(sql).contains(comparisons);
    }

    private boolean usesAppSettingsCollation(Class<?> repositoryType) {
        return repositoryType == WorkloadShadowSettingsRepository.class
                || repositoryType == WorkloadLiveSettingsRepository.class;
    }

    private String normalized(String value) {
        return value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}

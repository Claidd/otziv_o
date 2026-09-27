package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowProjectionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class WorkloadShadowBlockedCutoffTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);
    private final WorkloadShadowProjectionRepository repository = mock(WorkloadShadowProjectionRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"39,2,22:38:38", "63,7,23:58:23"})
    void lateBlockedCardsCannotResetConfirmedHundredPercent(long completed, long blocked, String time) throws Exception {
        JsonNode snapshot = recalculate("NAGUL", DAY, DAY.atTime(LocalTime.parse(time)), completed, blocked, true);
        assertThat(snapshot.path("eligibleUnits").asLong()).isEqualTo(completed);
        assertThat(snapshot.path("completedUnits").asLong()).isEqualTo(completed);
        assertThat(snapshot.path("progressPercent").asDouble()).isEqualTo(100);
        assertThat(snapshot.path("externalBlockedUnits").asLong()).isZero();
        assertThat(snapshot.path("lateExcludedUnits").asLong()).isEqualTo(blocked);
        ArgumentCaptor<String> decisions = ArgumentCaptor.forClass(String.class);
        verify(repository).upsertDailyBatchDecisions(decisions.capture());
        JsonNode decision = mapper.readTree(decisions.getValue()).get(0);
        assertThat(decision.path("decisionCode").asText()).isEqualTo("LATE");
        assertThat(decision.path("decisionOrigin").asText()).isEqualTo("AFTER_CUTOFF");
    }

    @ParameterizedTest
    @CsvSource({"NAGUL,21:59:59,1,0", "NAGUL,22:00:00,0,1",
            "PUBLISH,21:59:59,1,0", "PUBLISH,22:00:00,0,1",
            "BAD,21:59:59,1,0", "BAD,22:00:00,0,1",
            "RECOVERY,21:59:59,1,0", "RECOVERY,22:00:00,0,1"})
    void everyAccountDependentStageRespectsExactCutoff(String stage, String time, long mandatory, long late) throws Exception {
        JsonNode snapshot = recalculate(stage, DAY, DAY.atTime(LocalTime.parse(time)), 10, 1, true);
        assertThat(snapshot.path("eligibleUnits").asLong()).isEqualTo(10 + mandatory);
        assertThat(snapshot.path("externalBlockedUnits").asLong()).isEqualTo(mandatory);
        assertThat(snapshot.path("lateExcludedUnits").asLong()).isEqualTo(late);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NAGUL", "PUBLISH", "BAD", "RECOVERY"})
    void lateCardsBecomeMandatoryNextDayEvenWithoutAnAccount(String stage) throws Exception {
        JsonNode snapshot = recalculate(stage, DAY.plusDays(1), DAY.atTime(23, 58), 10, 7, true);
        assertThat(snapshot.path("eligibleUnits").asLong()).isEqualTo(17);
        assertThat(snapshot.path("externalBlockedUnits").asLong()).isEqualTo(7);
        assertThat(snapshot.path("lateExcludedUnits").asLong()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NAGUL", "PUBLISH", "BAD", "RECOVERY"})
    void assigningAnAccountDoesNotMakeLateWorkMandatory(String stage) throws Exception {
        JsonNode snapshot = recalculate(stage, DAY, DAY.atTime(22, 38), 10, 2, false);
        assertThat(snapshot.path("eligibleUnits").asLong()).isEqualTo(10);
        assertThat(snapshot.path("externalBlockedUnits").asLong()).isZero();
        assertThat(snapshot.path("lateExcludedUnits").asLong()).isEqualTo(2);
    }

    private JsonNode recalculate(String stage, LocalDate day, LocalDateTime available,
                                 long completed, long units, boolean blocked) throws Exception {
        var settings = mock(WorkloadShadowSettingsResponse.class);
        var settingsService = mock(WorkloadShadowSettingsService.class);
        var appSettings = mock(AppSettingService.class);
        when(settingsService.current()).thenReturn(settings);
        when(settingsService.zone(settings)).thenReturn(ZoneId.of("Asia/Irkutsk"));
        when(settingsService.shiftStart(settings)).thenReturn(LocalTime.of(10, 0));
        when(settingsService.shiftEnd(settings)).thenReturn(LocalTime.of(22, 0));
        when(settings.shiftStart()).thenReturn("10:00");
        when(settings.lookbackDays()).thenReturn(30);
        when(appSettings.getInt(AppSettingService.NAGUL_LOOKAHEAD_DAYS, 14)).thenReturn(14);
        when(repository.findWorkers()).thenReturn(List.of(Map.of(
                "worker_id", 12L, "worker_user_id", 120L, "manager_id", 2L,
                "manager_link_count", 1, "worker_name", "Specialist",
                "worker_telegram_group_chat_id", -120L)));
        Map<String, Object> row = Map.of("worker_id", 12L, "company_id", 99L, "order_id", 100L,
                "batch_key", stage + ":1", "available_at", available, "units", units, "external_blocked", blocked);
        switch (stage) {
            case "NAGUL" -> when(repository.findNagulBatches(anyCollection(), any(), any(), any())).thenReturn(List.of(row));
            case "PUBLISH" -> when(repository.findPublishBatches(anyCollection(), any(), any(), any())).thenReturn(List.of(row));
            case "BAD" -> when(repository.findBadBatches(anyCollection(), any(), any())).thenReturn(List.of(row));
            case "RECOVERY" -> when(repository.findRecoveryBatches(anyCollection(), any(), any())).thenReturn(List.of(row));
            default -> throw new IllegalArgumentException(stage);
        }
        when(repository.findUnitCompletions(anyCollection(), any(), any(), any())).thenReturn(List.of(
                Map.of("worker_id", 12L, "action", "REVIEW_PUBLISH", "units", completed)));
        new WorkloadShadowProjectionService(repository, mapper, appSettings, settingsService)
                .recalculate(1L, day.atTime(23, 59, 50));
        ArgumentCaptor<String> snapshots = ArgumentCaptor.forClass(String.class);
        verify(repository).upsertDailySnapshots(snapshots.capture(), eq(true), eq(day.atTime(23, 59, 50)));
        return mapper.readTree(snapshots.getValue()).get(0);
    }
}

package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.workload_shadow.api.MandatoryAccountBlockers;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowProjectionRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** The notice list must be exactly what the day close adds to the denominator as blocked. */
class MandatoryAccountBlockersTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 6);
    private final WorkloadShadowProjectionRepository repository = mock(WorkloadShadowProjectionRepository.class);
    private final WorkloadShadowSettingsService settingsService = mock(WorkloadShadowSettingsService.class);

    @ParameterizedTest
    @CsvSource({
            "NAGUL,21:59:59,true,1", "NAGUL,22:00:00,true,0", "NAGUL,09:00:00,false,0",
            "PUBLISH,21:59:59,true,1", "PUBLISH,22:00:00,true,0",
            "BAD,10:00:00,true,1", "BAD,22:00:00,true,0",
            "RECOVERY,10:00:00,true,1", "RECOVERY,22:00:00,true,0", "RECOVERY,10:00:00,false,0"})
    void listsOnlyBlockedCardsThatTheDayCloseCounts(String stage, String availableAt, boolean blocked, int expected) {
        Map<String, Object> row = Map.of("worker_id", 6L, "company_id", 99L, "order_id", 100L,
                "batch_key", stage + ":1160", "available_at", DAY.atTime(LocalTime.parse(availableAt)),
                "units", 1L, "external_blocked", blocked);
        switch (stage) {
            case "NAGUL" -> when(repository.findNagulBatches(anyCollection(), any(), any(), any())).thenReturn(List.of(row));
            case "PUBLISH" -> when(repository.findPublishBatches(anyCollection(), any(), any(), any())).thenReturn(List.of(row));
            case "BAD" -> when(repository.findBadBatches(anyCollection(), any(), any())).thenReturn(List.of(row));
            case "RECOVERY" -> when(repository.findRecoveryBatches(anyCollection(), any(), any())).thenReturn(List.of(row));
            default -> throw new IllegalArgumentException(stage);
        }

        List<WorkloadShadowProjectionService.BlockedBatch> result =
                projection().mandatoryAccountBlockers(List.of(6L), DAY.atTime(10, 15));

        assertThat(result).hasSize(expected);
        if (expected == 1) {
            assertThat(result.getFirst()).isEqualTo(new WorkloadShadowProjectionService.BlockedBatch(
                    6L, stage, stage + ":1160", 99L, 100L));
        }
        // Orders never carry an account blocker; the notice does not pay for that query.
        verify(repository, never()).findOrderBatches(anyCollection(), any(), anyString());
    }

    @Test
    void emptyRequestReadsNothing() {
        assertThat(projection().mandatoryAccountBlockers(List.of(), DAY.atTime(10, 15))).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void enrichesTasksWithCompanyAndTheButtonTheSpecialistSees() {
        var projectionService = mock(WorkloadShadowProjectionService.class);
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(projectionService.mandatoryAccountBlockers(List.of(6L, 7L), DAY.atTime(10, 15))).thenReturn(List.of(
                new WorkloadShadowProjectionService.BlockedBatch(6L, "RECOVERY", "RECOVERY:1160", 5L, -44L),
                new WorkloadShadowProjectionService.BlockedBatch(6L, "RECOVERY", "RECOVERY:1161", 5L, 300L),
                new WorkloadShadowProjectionService.BlockedBatch(7L, "PUBLISH", "PUBLISH:77", null, 301L)));
        org.mockito.Mockito.doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            var rs = mock(java.sql.ResultSet.class);
            when(rs.getLong("company_id")).thenReturn(5L);
            when(rs.getString("company_title")).thenReturn("Цветы & Ко");
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(List.of(1160L));

        var service = new MandatoryAccountBlockerService(projectionService, settingsService, jdbc);
        Map<Long, List<MandatoryAccountBlockers.BlockedTask>> result =
                service.blockedTasks(List.of(6L, 7L), DAY.atTime(10, 15));

        assertThat(result.get(6L)).containsExactly(
                new MandatoryAccountBlockers.BlockedTask(6L, MandatoryAccountBlockers.Kind.RECOVERY, 1160L, 5L,
                        "Цветы & Ко", null, true),
                new MandatoryAccountBlockers.BlockedTask(6L, MandatoryAccountBlockers.Kind.RECOVERY, 1161L, 5L,
                        "Цветы & Ко", 300L, false));
        assertThat(result.get(7L)).containsExactly(new MandatoryAccountBlockers.BlockedTask(
                7L, MandatoryAccountBlockers.Kind.PUBLISH, 77L, null, null, 301L, false));
    }

    @Test
    void workloadDayFollowsSettingsAndObservationSwitch() {
        var settings = mock(WorkloadShadowSettingsResponse.class);
        when(settingsService.current()).thenReturn(settings);
        when(settingsService.zone(settings)).thenReturn(ZoneId.of("Asia/Irkutsk"));
        when(settingsService.shiftStart(settings)).thenReturn(LocalTime.of(10, 0));
        when(settingsService.shiftEnd(settings)).thenReturn(LocalTime.of(22, 0));
        when(settings.observationEnabled()).thenReturn(false);

        var day = new MandatoryAccountBlockerService(mock(WorkloadShadowProjectionService.class), settingsService,
                mock(NamedParameterJdbcTemplate.class)).currentDay();

        assertThat(day).isEqualTo(new MandatoryAccountBlockers.WorkloadDay(
                ZoneId.of("Asia/Irkutsk"), LocalTime.of(10, 0), LocalTime.of(22, 0), false));
    }

    private WorkloadShadowProjectionService projection() {
        var settings = mock(WorkloadShadowSettingsResponse.class);
        var appSettings = mock(AppSettingService.class);
        when(settingsService.current()).thenReturn(settings);
        when(settingsService.zone(settings)).thenReturn(ZoneId.of("Asia/Irkutsk"));
        when(settingsService.shiftEnd(settings)).thenReturn(LocalTime.of(22, 0));
        when(settings.shiftStart()).thenReturn("10:00");
        when(appSettings.getInt(AppSettingService.NAGUL_LOOKAHEAD_DAYS, 14)).thenReturn(14);
        return new WorkloadShadowProjectionService(repository, new ObjectMapper(), appSettings, settingsService);
    }
}

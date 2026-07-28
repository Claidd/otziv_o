package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsRequest;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowSettingsRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class WorkloadShadowSettingsServiceTest {

    @Mock private WorkloadShadowSettingsRepository repository;
    @Mock private BusinessAuditService businessAuditService;
    @Mock private AppSettingService appSettingService;

    private ObjectMapper objectMapper;
    private WorkloadShadowSettingsService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new WorkloadShadowSettingsService(
                repository,
                businessAuditService,
                objectMapper,
                appSettingService
        );
        lenient().when(repository.findAllByPrefix(WorkloadShadowSettingsService.PREFIX))
                .thenReturn(List.of());
    }

    @Test
    void currentLoadsTheWholeNamespaceWithOneRepositoryCallAndUsesDefaults() {
        WorkloadShadowSettingsResponse result = service.current();

        assertThat(result.mode()).isEqualTo("SHADOW");
        assertThat(result.applyEnabled()).isFalse();
        assertThat(result.observationEnabled()).isTrue();
        assertThat(result.groupNotificationsEnabled()).isFalse();
        assertThat(result.notificationGroupChatId()).isNull();
        assertThat(result.walkMinutesPerCard()).isEqualTo(4);
        assertThat(result.walkMinimumMinutesPerCard()).isEqualTo(3);
        assertThat(result.decisionRetentionDays()).isEqualTo(60);
        assertThat(result.revision()).isEqualTo(1);
        verify(repository).findAllByPrefix(WorkloadShadowSettingsService.PREFIX);
        verifyNoInteractions(businessAuditService, appSettingService);
    }

    @Test
    void currentIgnoresStoredApplyFlagAndEnforcesThreeMinuteWalkMinimum() {
        when(repository.findAllByPrefix(WorkloadShadowSettingsService.PREFIX))
                .thenReturn(rows(Map.of(
                        "apply-enabled", "true",
                        "walk-minimum-minutes-per-card", "2",
                        "walk-minutes-per-card", "1",
                        "settings-revision", "7"
                )));

        WorkloadShadowSettingsResponse result = service.current();

        assertThat(result.applyEnabled()).isFalse();
        assertThat(result.walkMinimumMinutesPerCard()).isEqualTo(3);
        assertThat(result.walkMinutesPerCard()).isEqualTo(3);
        assertThat(result.revision()).isEqualTo(7);
    }

    @Test
    void updateWritesAllSettingsInOneGuardedJsonQueryAndAudits() throws Exception {
        when(repository.findAllByPrefix(WorkloadShadowSettingsService.PREFIX))
                .thenReturn(List.of())
                .thenReturn(rows(Map.of(
                        "observation-enabled", "false",
                        "group-notifications-enabled", "false",
                        "business-zone", "UTC",
                        "settings-revision", "2"
                )));
        when(repository.updateAllWithRevision(
                anyString(),
                eq(WorkloadShadowSettingsService.PREFIX),
                eq(WorkloadShadowSettingsService.REVISION_KEY),
                eq(1L)
        )).thenReturn(44);

        WorkloadShadowSettingsResponse result = service.update(request(false, false, "UTC", 1L));

        assertThat(result.observationEnabled()).isFalse();
        assertThat(result.groupNotificationsEnabled()).isFalse();
        assertThat(result.businessZone()).isEqualTo("UTC");
        assertThat(result.revision()).isEqualTo(2);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(repository).updateAllWithRevision(
                json.capture(),
                eq(WorkloadShadowSettingsService.PREFIX),
                eq(WorkloadShadowSettingsService.REVISION_KEY),
                eq(1L)
        );
        JsonNode payload = objectMapper.readTree(json.getValue());
        assertThat(payload.size()).isEqualTo(44);
        assertThat(value(payload, "workload.shadow.apply-enabled")).isEqualTo("false");
        assertThat(value(payload, "workload.shadow.settings-revision")).isEqualTo("2");
        assertThat(value(payload, "workload.shadow.business-zone")).isEqualTo("UTC");
        assertThat(value(payload, "workload.shadow.notification-group-chat-id"))
                .isEmpty();

        verify(appSettingService).invalidateByPrefix(WorkloadShadowSettingsService.PREFIX);
        verify(businessAuditService).recordSafely(
                eq("UPDATE_WORKLOAD_SHADOW_SETTINGS"),
                eq("WORKLOAD_SHADOW_SETTINGS"),
                eq("global"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                any(WorkloadShadowSettingsResponse.class),
                eq(result),
                anyString()
        );
    }

    @Test
    void explicitStaleRevisionIsRejectedBeforeTheBatchUpdate() {
        when(repository.findAllByPrefix(WorkloadShadowSettingsService.PREFIX))
                .thenReturn(rows(Map.of("settings-revision", "5")));

        assertThatThrownBy(() -> service.update(request(true, false, "Asia/Irkutsk", 4L)))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT)
                );

        verify(repository, never()).updateAllWithRevision(
                anyString(),
                anyString(),
                anyString(),
                org.mockito.ArgumentMatchers.anyLong()
        );
        verifyNoInteractions(businessAuditService, appSettingService);
    }

    @Test
    void databaseRevisionRaceIsRejectedAtomically() {
        when(repository.updateAllWithRevision(
                anyString(),
                eq(WorkloadShadowSettingsService.PREFIX),
                eq(WorkloadShadowSettingsService.REVISION_KEY),
                eq(1L)
        )).thenReturn(0);

        assertThatThrownBy(() -> service.update(request(true, false, "Asia/Irkutsk", 1L)))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT)
                );

        verifyNoInteractions(businessAuditService, appSettingService);
    }

    @Test
    void partialSchemaUpdateFailsSoTheTransactionCanRollBack() {
        when(repository.updateAllWithRevision(
                anyString(),
                eq(WorkloadShadowSettingsService.PREFIX),
                eq(WorkloadShadowSettingsService.REVISION_KEY),
                eq(1L)
        )).thenReturn(43);

        assertThatThrownBy(() -> service.update(request(true, false, "Asia/Irkutsk", 1L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ожидалось 44")
                .hasMessageContaining("обновлено 43");

        verifyNoInteractions(businessAuditService, appSettingService);
    }

    @Test
    void combatModeCannotReachTheSettingsRepository() {
        assertThatThrownBy(() -> service.update(request(true, true, "Asia/Irkutsk", 1L)))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.PRECONDITION_FAILED)
                );

        verifyNoInteractions(repository, businessAuditService, appSettingService);
    }

    @Test
    void notificationsCannotUseAPersonalTelegramChat() {
        WorkloadShadowSettingsRequest source =
                request(true, false, "Asia/Irkutsk", 1L);
        WorkloadShadowSettingsRequest invalid = new WorkloadShadowSettingsRequest(
                source.mode(),
                source.applyEnabled(),
                source.observationEnabled(),
                true,
                794146111L,
                source.schedulerIntervalMinutes(),
                source.nearEndIntervalMinutes(),
                source.nearEndWindowMinutes(),
                source.businessZone(),
                source.shiftStart(),
                source.shiftEnd(),
                source.walkMinutesPerCard(),
                source.walkMinimumMinutesPerCard(),
                source.newMinutesPerCard(),
                source.correctionMinutesPerOrder(),
                source.publishMinutesPerCard(),
                source.recoveryMinutesPerTask(),
                source.badMinutesPerTask(),
                source.adaptiveEstimatesEnabled(),
                source.adaptiveMinimumSamples(),
                source.lookbackDays(),
                source.allowedFailureDays(),
                source.recipientMinimumRating(),
                source.recipientMinimumHundredPercentRate(),
                source.recipientMaximumFailureDays(),
                source.fourthFailurePercent(),
                source.fourthFailureMaxCompanies(),
                source.fifthFailurePercent(),
                source.fifthFailureMaxCompanies(),
                source.sixthFailurePercent(),
                source.sixthFailureMaxCompanies(),
                source.freezeEarnDays(),
                source.freezeMaxCredits(),
                source.alertCooldownMinutes(),
                source.runRetentionDays(),
                source.dailyRetentionDays(),
                source.eventRetentionDays(),
                source.decisionRetentionDays(),
                source.staleRunMinutes(),
                source.notificationBatchSize(),
                source.notificationMaxAttempts(),
                source.notificationLeaseMinutes(),
                source.notificationRetryBaseMinutes(),
                source.maintenanceBatchSize(),
                source.revision()
        );

        assertThatThrownBy(() -> service.update(invalid))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
                )
                .hasMessageContaining("отрицательный chat ID");
        verifyNoInteractions(repository, businessAuditService, appSettingService);
    }

    private WorkloadShadowSettingsRequest request(
            boolean observationEnabled,
            boolean applyEnabled,
            String zone,
            Long revision
    ) {
        return new WorkloadShadowSettingsRequest(
                "SHADOW",
                applyEnabled,
                observationEnabled,
                false,
                null,
                10,
                5,
                120,
                zone,
                "10:00",
                "23:00",
                4,
                3,
                5,
                10,
                3,
                10,
                10,
                true,
                30,
                30,
                3,
                85,
                80,
                2,
                15,
                1,
                25,
                2,
                30,
                3,
                14,
                2,
                60,
                30,
                400,
                90,
                60,
                30,
                10,
                8,
                5,
                1,
                1000,
                revision
        );
    }

    private List<WorkloadShadowSettingsRepository.SettingProjection> rows(
            Map<String, String> values
    ) {
        List<WorkloadShadowSettingsRepository.SettingProjection> result = new ArrayList<>();
        values.forEach((suffix, value) -> result.add(row(
                WorkloadShadowSettingsService.PREFIX + suffix,
                value
        )));
        return result;
    }

    private WorkloadShadowSettingsRepository.SettingProjection row(
            String settingKey,
            String settingValue
    ) {
        return new WorkloadShadowSettingsRepository.SettingProjection() {
            @Override
            public String getSettingKey() {
                return settingKey;
            }

            @Override
            public String getSettingValue() {
                return settingValue;
            }
        };
    }

    private String value(JsonNode payload, String key) {
        for (JsonNode setting : payload) {
            if (key.equals(setting.path("settingKey").asText())) {
                return setting.path("settingValue").asText();
            }
        }
        return null;
    }
}

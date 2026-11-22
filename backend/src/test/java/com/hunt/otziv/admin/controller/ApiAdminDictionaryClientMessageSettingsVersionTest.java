package com.hunt.otziv.admin.controller;

import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A form of the autoresponder opened before another change must not write its switches back: after an emergency stop
 * of sending to clients, saving a phrase from an old tab would otherwise turn sending on again.
 */
class ApiAdminDictionaryClientMessageSettingsVersionTest {

    private final AppSettingService settings = mock(AppSettingService.class);
    private final ScheduledClientMessageService scheduledMessages = mock(ScheduledClientMessageService.class);
    private final ApiAdminDictionaryController controller = mock(ApiAdminDictionaryController.class, CALLS_REAL_METHODS);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "appSettingService", settings);
        ReflectionTestUtils.setField(controller, "scheduledClientMessageService", scheduledMessages);
    }

    @Test
    void theVersionIsStableAndChangesWithAnyStoredValueButTheMonitorSwitch() {
        String first = controller.getClientMessageSettings().settingsVersion();
        assertThat(first).matches("[0-9a-f]{32}");
        assertThat(controller.getClientMessageSettings().settingsVersion()).isEqualTo(first);

        when(settings.getBoolean(eq(AppSettingService.CLIENT_MESSAGES_MONITOR_ENABLED), anyBoolean())).thenReturn(true);
        assertThat(controller.getClientMessageSettings().settingsVersion()).isEqualTo(first);

        when(settings.getBoolean(eq(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED), anyBoolean())).thenReturn(true);
        assertThat(controller.getClientMessageSettings().settingsVersion()).isNotEqualTo(first);
    }

    @Test
    void aStaleFormIsRefusedBeforeAnySettingIsWritten() throws Exception {
        ApiAdminDictionaryController.ClientMessageSettingsRequest stale = request("0123456789abcdef0123456789abcdef");

        assertThatThrownBy(() -> controller.updateClientMessageSettings(stale))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(settings, never()).setBoolean(anyString(), anyBoolean());
        verify(scheduledMessages, never()).releaseDryRunMessagesIfLiveEnabled();
    }

    @Test
    void anInvalidFieldRefusesTheWholeFormBeforeAnySettingIsWritten() throws Exception {
        String current = controller.getClientMessageSettings().settingsVersion();
        // Every interval is 0 here, below its minimum: nothing of the form may be stored, not even the valid switches.
        ApiAdminDictionaryController.ClientMessageSettingsRequest invalid = request(current, 0);

        assertThatThrownBy(() -> controller.updateClientMessageSettings(invalid))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getReason()).startsWith("Настройки не сохранены: ");
                });

        verify(settings, never()).setBoolean(anyString(), anyBoolean());
        verify(settings, never()).setInt(anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(settings, never()).setString(anyString(), anyString());
        verify(scheduledMessages, never()).releaseDryRunMessagesIfLiveEnabled();
    }

    /** All fields at their type defaults except the version and the required working windows. */
    private static ApiAdminDictionaryController.ClientMessageSettingsRequest request(String expectedVersion) throws Exception {
        return request(expectedVersion, null);
    }

    /** As above, with every optional number set to {@code optionalNumber}. */
    private static ApiAdminDictionaryController.ClientMessageSettingsRequest request(String expectedVersion, Integer optionalNumber) throws Exception {
        RecordComponent[] components = ApiAdminDictionaryController.ClientMessageSettingsRequest.class.getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int index = 0; index < components.length; index++) {
            Class<?> type = components[index].getType();
            types[index] = type;
            values[index] = type == boolean.class ? Boolean.FALSE : type == int.class ? Integer.valueOf(0)
                    : type == Integer.class ? optionalNumber : null;
            if ("businessWindows".equals(components[index].getName())) values[index] = "10:00-12:00";
            if ("expectedSettingsVersion".equals(components[index].getName())) values[index] = expectedVersion;
        }
        return ApiAdminDictionaryController.ClientMessageSettingsRequest.class.getDeclaredConstructor(types).newInstance(values);
    }
}

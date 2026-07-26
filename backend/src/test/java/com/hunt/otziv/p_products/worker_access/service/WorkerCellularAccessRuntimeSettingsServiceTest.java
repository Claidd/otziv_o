package com.hunt.otziv.p_products.worker_access.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.worker_access.config.WorkerCellularAccessProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerCellularAccessRuntimeSettingsServiceTest {

    @Test
    void loadsDatabaseOverridesOverEnvironmentFallbacks() {
        WorkerCellularAccessProperties properties = properties();
        AppSettingService appSettings = mock(AppSettingService.class);
        when(appSettings.getString(AppSettingService.WORKER_CELLULAR_ACCESS_MODE, "ENFORCE"))
                .thenReturn("AUDIT");
        when(appSettings.getStringAllowEmpty(
                AppSettingService.WORKER_CELLULAR_ACCESS_ENFORCED_REASONS,
                "NON_CELLULAR_NETWORK,VPN_PROXY_OR_DATACENTER"
        )).thenReturn("VPN_PROXY_OR_DATACENTER");
        when(appSettings.getBoolean(
                AppSettingService.WORKER_CELLULAR_ACCESS_ENFORCE_NATIVE_VIRTUAL_DEVICE,
                true
        )).thenReturn(false);

        WorkerCellularAccessRuntimeSettingsService service = new WorkerCellularAccessRuntimeSettingsService(
                properties,
                appSettings,
                mock(BusinessAuditService.class)
        );

        WorkerCellularAccessRuntimeSettingsService.AccessPolicy policy = service.currentPolicy();

        assertEquals(WorkerCellularAccessProperties.Mode.AUDIT, policy.mode());
        assertEquals(Set.of("VPN_PROXY_OR_DATACENTER"), policy.enforcedReasons());
        assertFalse(policy.enforceNativeVirtualDevice());
    }

    @Test
    void updatesPolicyImmediatelyAndPersistsOnlySupportedReasons() {
        WorkerCellularAccessProperties properties = properties();
        AppSettingService appSettings = mock(AppSettingService.class);
        when(appSettings.getString(AppSettingService.WORKER_CELLULAR_ACCESS_MODE, "ENFORCE"))
                .thenReturn("ENFORCE");
        when(appSettings.getStringAllowEmpty(
                AppSettingService.WORKER_CELLULAR_ACCESS_ENFORCED_REASONS,
                "NON_CELLULAR_NETWORK,VPN_PROXY_OR_DATACENTER"
        )).thenReturn("NON_CELLULAR_NETWORK,VPN_PROXY_OR_DATACENTER");
        when(appSettings.getBoolean(
                AppSettingService.WORKER_CELLULAR_ACCESS_ENFORCE_NATIVE_VIRTUAL_DEVICE,
                true
        )).thenReturn(true);
        BusinessAuditService audit = mock(BusinessAuditService.class);
        WorkerCellularAccessRuntimeSettingsService service = new WorkerCellularAccessRuntimeSettingsService(
                properties,
                appSettings,
                audit
        );

        WorkerCellularAccessRuntimeSettingsService.AccessPolicy updated = service.update(
                WorkerCellularAccessProperties.Mode.AUDIT,
                List.of("VPN_PROXY_OR_DATACENTER", "NON_CELLULAR_NETWORK"),
                false
        );

        assertEquals(WorkerCellularAccessProperties.Mode.AUDIT, service.currentPolicy().mode());
        assertEquals(
                List.of("NON_CELLULAR_NETWORK", "VPN_PROXY_OR_DATACENTER"),
                updated.enforcedReasons().stream().toList()
        );
        verify(appSettings).setString(AppSettingService.WORKER_CELLULAR_ACCESS_MODE, "AUDIT");
        verify(appSettings).setString(
                AppSettingService.WORKER_CELLULAR_ACCESS_ENFORCED_REASONS,
                "NON_CELLULAR_NETWORK,VPN_PROXY_OR_DATACENTER"
        );
        verify(appSettings).setBoolean(
                AppSettingService.WORKER_CELLULAR_ACCESS_ENFORCE_NATIVE_VIRTUAL_DEVICE,
                false
        );
        verify(audit).recordSafely(
                org.mockito.ArgumentMatchers.eq("UPDATE_WORKER_CELLULAR_ACCESS"),
                org.mockito.ArgumentMatchers.eq("WORKER_ACCESS_POLICY"),
                org.mockito.ArgumentMatchers.eq("global"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(updated),
                org.mockito.ArgumentMatchers.anyString()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.update(
                        WorkerCellularAccessProperties.Mode.ENFORCE,
                        List.of("NOT_A_REASON"),
                        true
                )
        );
    }

    private WorkerCellularAccessProperties properties() {
        WorkerCellularAccessProperties properties = new WorkerCellularAccessProperties();
        properties.setMode(WorkerCellularAccessProperties.Mode.ENFORCE);
        properties.setEnforcedReasons(Set.of("NON_CELLULAR_NETWORK", "VPN_PROXY_OR_DATACENTER"));
        properties.setEnforceNativeVirtualDevice(true);
        return properties;
    }
}

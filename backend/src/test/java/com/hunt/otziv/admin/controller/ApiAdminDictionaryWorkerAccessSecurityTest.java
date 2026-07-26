package com.hunt.otziv.admin.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiAdminDictionaryWorkerAccessSecurityTest {

    @Test
    void workerAccessSettingsEndpointsAreAdminOnly() throws Exception {
        assertAdminOnly(ApiAdminDictionaryController.class.getMethod("getWorkerCellularAccessSettings"));
        assertAdminOnly(ApiAdminDictionaryController.class.getMethod(
                "updateWorkerCellularAccessSettings",
                ApiAdminDictionaryController.WorkerCellularAccessSettingsRequest.class
        ));
    }

    private void assertAdminOnly(Method method) {
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertEquals("hasRole('ADMIN')", preAuthorize.value());
    }
}

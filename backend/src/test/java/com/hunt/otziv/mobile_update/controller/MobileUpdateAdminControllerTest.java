package com.hunt.otziv.mobile_update.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MobileUpdateAdminControllerTest {

    @Test
    void refusesNewApkUploadToLegacyChannel() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MobileUpdateAdminController()).build();
        MockMultipartFile apk = new MockMultipartFile(
                "apk", "otziv-v1.0.75.apk", "application/vnd.android.package-archive", new byte[] {1, 2, 3}
        );

        mvc.perform(multipart("/api/admin/mobile-update")
                        .file(apk)
                        .param("versionCode", "75")
                        .param("versionName", "1.0.75"))
                .andExpect(status().isGone())
                .andExpect(result -> {
                    ResponseStatusException failure = assertInstanceOf(
                            ResponseStatusException.class, result.getResolvedException()
                    );
                    assertTrue(failure.getReason().contains("/android-new/"));
                });
    }
}

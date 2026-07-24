package com.hunt.otziv.mobile_update;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobileUpdateServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void publishesAndLoadsCurrentReleaseWithSha256() throws Exception {
        MobileUpdateService service = service();

        MobileUpdateResponse published = service.publish(apk("release.apk"), 54, "1.0.54", 53, true, "Исправления");

        assertTrue(published.enabled());
        assertEquals(54, published.versionCode());
        assertEquals("1.0.54", published.versionName());
        assertEquals(53, published.minSupportedVersionCode());
        assertTrue(published.required());
        assertEquals("Исправления", published.notes());
        assertEquals(64, published.sha256().length());
        assertTrue(Files.isRegularFile(tempDirectory.resolve("release.json")));
        assertTrue(service.currentApk().exists());

        MobileUpdateResponse restored = service().current();
        assertEquals(published.versionCode(), restored.versionCode());
        assertEquals(published.sha256(), restored.sha256());
        assertNotNull(restored.publishedAt());
    }

    @Test
    void returnsDisabledResponseBeforeFirstPublication() {
        MobileUpdateResponse response = service().current();

        assertFalse(response.enabled());
        assertEquals(null, response.versionCode());
    }

    @Test
    void rejectsOlderVersionAndNonApkContent() {
        MobileUpdateService service = service();
        service.publish(apk("release.apk"), 54, "1.0.54", 0, false, "");

        assertThrows(
                ResponseStatusException.class,
                () -> service.publish(apk("older.apk"), 54, "1.0.54", 0, false, "")
        );
        assertThrows(
                ResponseStatusException.class,
                () -> service.publish(
                        new MockMultipartFile("apk", "broken.apk", "application/vnd.android.package-archive", "not-apk".getBytes()),
                        55,
                        "1.0.55",
                        0,
                        false,
                        ""
                )
        );
    }

    private MobileUpdateService service() {
        return new MobileUpdateService(JsonMapper.builder().findAndAddModules().build(), tempDirectory.toString());
    }

    private MockMultipartFile apk(String name) {
        return new MockMultipartFile(
                "apk",
                name,
                "application/vnd.android.package-archive",
                new byte[]{0x50, 0x4b, 0x03, 0x04, 0x01, 0x02, 0x03, 0x04}
        );
    }
}

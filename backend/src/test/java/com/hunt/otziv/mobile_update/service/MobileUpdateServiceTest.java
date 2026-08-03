package com.hunt.otziv.mobile_update.service;

import com.hunt.otziv.mobile_update.dto.MobileUpdateResponse;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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

    @Test
    void rejectsOrdinaryZipWithoutAndroidManifest() throws Exception {
        MobileUpdateService service = service();

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.publish(
                        zip("not-an-apk.apk", "readme.txt", "hello".getBytes()),
                        55,
                        "1.0.55",
                        0,
                        false,
                        ""
                )
        );

        assertEquals(400, failure.getStatusCode().value());
        assertTrue(failure.getReason().contains("AndroidManifest.xml"));
    }

    @Test
    void rejectsArchiveTraversalEntry() throws Exception {
        MobileUpdateService service = service();
        byte[] archive;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(new byte[] {3, 0, 8, 0});
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("../outside"));
            zip.write(1);
            zip.closeEntry();
            zip.finish();
            archive = output.toByteArray();
        }

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.publish(
                        new MockMultipartFile(
                                "apk",
                                "unsafe.apk",
                                "application/vnd.android.package-archive",
                                archive
                        ),
                        55,
                        "1.0.55",
                        0,
                        false,
                        ""
                )
        );

        assertEquals(400, failure.getStatusCode().value());
    }

    private MobileUpdateService service() {
        return new MobileUpdateService(JsonMapper.builder().findAndAddModules().build(), tempDirectory.toString());
    }

    private MockMultipartFile apk(String name) {
        try {
            return zip(name, "AndroidManifest.xml", new byte[] {3, 0, 8, 0});
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MockMultipartFile zip(String name, String entryName, byte[] contents) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(contents);
            zip.closeEntry();
        }
        return new MockMultipartFile(
                "apk",
                name,
                "application/vnd.android.package-archive",
                output.toByteArray()
        );
    }
}

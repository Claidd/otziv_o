package com.hunt.otziv.uploads.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileUploadGuardTest {

    @Test
    void acceptsImageByMagicBytesEvenWhenExtensionIsWrong() throws Exception {
        FileUploadGuard guard = guard();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "photo.txt",
                "text/plain",
                pngBytes(32, 24)
        );

        FileUploadGuard.ImageCheck result = guard.requireSupportedImage(file);

        assertEquals("png", result.extension());
        assertEquals(32, result.width());
        assertEquals(24, result.height());
    }

    @Test
    void rejectsFakeImageBytes() {
        FileUploadGuard guard = guard();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "photo.jpg",
                "image/jpeg",
                "not an image".getBytes(StandardCharsets.UTF_8)
        );

        assertThrows(ResponseStatusException.class, () -> guard.requireSupportedImage(file));
    }

    @Test
    void rejectsImportWithoutAllowedExtension() {
        FileUploadGuard guard = guard();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "leads.bin",
                "application/octet-stream",
                "phone\n79990000000\n".getBytes(StandardCharsets.UTF_8)
        );

        assertThrows(ResponseStatusException.class, () -> guard.requireSupportedImportFile(file));
    }

    @Test
    void rejectsTooManyImportRows() {
        FileUploadGuard guard = guard();

        assertThrows(ResponseStatusException.class, () -> guard.requireImportRowLimit(5001));
    }

    private FileUploadGuard guard() {
        return new FileUploadGuard(
                5 * 1024 * 1024,
                20_000_000,
                8000,
                8000,
                5 * 1024 * 1024,
                5000
        );
    }

    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.getGraphics().setColor(Color.WHITE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}

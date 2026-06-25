package com.hunt.otziv.s3.service;

import com.hunt.otziv.uploads.service.FileUploadGuard;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3UploadServiceImplTest {

    @Test
    void uploadFileGeneratesServerSideJpegKeyWithoutOriginalName() throws Exception {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        S3UploadServiceImpl service = new S3UploadServiceImpl(s3Client, guard());
        ReflectionTestUtils.setField(service, "bucket", "bucket");
        ReflectionTestUtils.setField(service, "region", "ru-1");
        ReflectionTestUtils.setField(service, "projectId", "project");
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://cdn.test");

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "../client secret.png",
                "image/png",
                pngBytes()
        );

        String url = service.uploadFile(file, "reviews", null, 123L);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        String key = captor.getValue().key();
        assertTrue(key.matches("reviews/123-[0-9a-f-]{36}\\.jpg"));
        assertFalse(key.contains("client secret"));
        assertTrue(url.matches("https://cdn\\.test/reviews/123-[0-9a-f-]{36}\\.jpg"));
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

    private byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        image.getGraphics().setColor(Color.WHITE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}

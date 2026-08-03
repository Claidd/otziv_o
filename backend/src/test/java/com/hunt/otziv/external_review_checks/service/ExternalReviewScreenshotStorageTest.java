package com.hunt.otziv.external_review_checks.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hunt.otziv.external_review_checks.config.ExternalReviewCheckProperties;
import com.hunt.otziv.s3.cleanup.service.S3ObjectCleanupQueue;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class ExternalReviewScreenshotStorageTest {

    private S3Client s3Client;
    private ExternalReviewCheckProperties properties;
    private ExternalReviewScreenshotStorage storage;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        properties = new ExternalReviewCheckProperties();
        properties.setScreenshotMaxBytes(1024);
        properties.setScreenshotUploadTimeout(Duration.ofSeconds(12));
        storage = new ExternalReviewScreenshotStorage(
                s3Client,
                properties,
                mock(S3ObjectCleanupQueue.class)
        );
        ReflectionTestUtils.setField(storage, "bucket", "screenshots");
        ReflectionTestUtils.setField(storage, "projectId", "project");
        ReflectionTestUtils.setField(storage, "publicBaseUrl", "https://cdn.example/");
    }

    @Test
    void rejectsOversizedPayloadBeforeBase64DecodeOrS3() {
        properties.setScreenshotMaxBytes(3);
        String encoded = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4});

        assertThatThrownBy(() -> storage.store(42L, 7L, encoded, "image/png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Screenshot payload exceeds configured limit");
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void rejectsDeclaredTypeThatDoesNotMatchSignature() {
        String encoded = Base64.getEncoder().encodeToString(pngSignature());

        assertThatThrownBy(() -> storage.store(42L, 7L, encoded, "image/jpeg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match JPEG");
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void detectsPngAndUploadsWithSanitizedMetadata() {
        String encoded = "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(pngSignature());

        ExternalReviewScreenshotStorage.StoredScreenshot stored =
                storage.store(42L, 7L, encoded, null);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("screenshots");
        assertThat(request.getValue().contentType()).isEqualTo("image/png");
        assertThat(request.getValue().key())
                .startsWith("external-review-checks/reviews/7/42-")
                .endsWith(".png");
        assertThat(request.getValue().overrideConfiguration()).isPresent();
        assertThat(request.getValue().overrideConfiguration().orElseThrow().apiCallTimeout())
                .contains(Duration.ofSeconds(12));
        assertThat(request.getValue().overrideConfiguration().orElseThrow().apiCallAttemptTimeout())
                .contains(Duration.ofSeconds(12));
        assertThat(stored.url()).isEqualTo("https://cdn.example/" + request.getValue().key());
    }

    @Test
    void bestEffortCleanupDeletesOnlyTheOpaqueStoredKey() {
        storage.deleteBestEffort(new ExternalReviewScreenshotStorage.StoredScreenshot(
                "external-review-checks/reviews/7/old.png",
                "https://cdn.example/old.png"
        ));

        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("screenshots");
        assertThat(request.getValue().key())
                .isEqualTo("external-review-checks/reviews/7/old.png");
        assertThat(request.getValue().overrideConfiguration()).isPresent();
    }

    private byte[] pngSignature() {
        return new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        };
    }
}

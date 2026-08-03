package com.hunt.otziv.performers.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.uploads.service.FileUploadGuard;
import com.hunt.otziv.s3.cleanup.service.S3ObjectCleanupQueue;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

class PerformerAssignmentScreenshotStorageTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void keepsOldObjectUntilCommitThenDeletesOnlyOwnedKey() throws Exception {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        PerformerAssignmentScreenshotStorage storage = storage(s3);
        beginTransactionSynchronization();

        String result = storage.store(
                image(),
                42L,
                PerformerAssignmentScreenshotStorage.ScreenshotKind.PERFORMER_PUBLICATION,
                "https://cdn.test/performer-assignments/publication/42-old.jpg"
        );

        assertThat(result).contains("/performer-assignments/publication/42-");
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        ArgumentCaptor<DeleteObjectRequest> deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(deleted.capture());
        assertThat(deleted.getValue().key())
                .isEqualTo("performer-assignments/publication/42-old.jpg");
    }

    @Test
    void transactionRollbackDeletesNewObjectAndPreservesOldObject() throws Exception {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        PerformerAssignmentScreenshotStorage storage = storage(s3);
        beginTransactionSynchronization();

        storage.store(
                image(),
                42L,
                PerformerAssignmentScreenshotStorage.ScreenshotKind.MANAGER_CONFIRMATION,
                "https://cdn.test/performer-assignments/manager-confirmation/42-old.jpg"
        );
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        ArgumentCaptor<PutObjectRequest> uploaded = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(uploaded.capture(), any(RequestBody.class));
        ArgumentCaptor<DeleteObjectRequest> deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(deleted.capture());
        assertThat(deleted.getValue().key()).isEqualTo(uploaded.getValue().key());
        assertThat(deleted.getValue().key()).doesNotEndWith("42-old.jpg");
    }

    @Test
    void refusesToDeleteScreenshotBelongingToAnotherAssignment() throws Exception {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        PerformerAssignmentScreenshotStorage storage = storage(s3);

        storage.store(
                image(),
                42L,
                PerformerAssignmentScreenshotStorage.ScreenshotKind.PERFORMER_PUBLICATION,
                "https://cdn.test/performer-assignments/publication/99-old.jpg"
        );

        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    private void beginTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private PerformerAssignmentScreenshotStorage storage(S3Client s3) {
        PerformerAssignmentScreenshotStorage storage = new PerformerAssignmentScreenshotStorage(
                s3,
                guard(),
                mock(S3ObjectCleanupQueue.class)
        );
        ReflectionTestUtils.setField(storage, "bucket", "screenshots");
        ReflectionTestUtils.setField(storage, "projectId", "project");
        ReflectionTestUtils.setField(storage, "publicBaseUrl", "https://cdn.test");
        return storage;
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

    private MockMultipartFile image() throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        image.getGraphics().setColor(Color.WHITE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return new MockMultipartFile("file", "evidence.png", "image/png", output.toByteArray());
    }
}

package com.hunt.otziv.notification_media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.notification_media.service.NotificationMediaStorageService.StoredNotificationImage;
import com.hunt.otziv.uploads.service.FileUploadGuard;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@ExtendWith(MockitoExtension.class)
class NotificationMediaStorageServiceTest {

    @Mock
    private S3Client s3Client;
    @Mock
    private FileUploadGuard fileUploadGuard;

    @Test
    void storesNotificationImageUnderDedicatedEventFolder() throws Exception {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(source, "png", bytes);
        byte[] png = bytes.toByteArray();
        MockMultipartFile file = new MockMultipartFile("file", "progress.png", "image/png", png);
        when(fileUploadGuard.requireSupportedImage(file))
                .thenReturn(new FileUploadGuard.ImageCheck(png, "png", 1, 1));

        NotificationMediaStorageService service =
                new NotificationMediaStorageService(s3Client, fileUploadGuard);
        ReflectionTestUtils.setField(service, "bucket", "cards");
        ReflectionTestUtils.setField(service, "projectId", "project");
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://cdn.example/");
        ReflectionTestUtils.setField(service, "rootFolder", "notification-media");

        StoredNotificationImage stored =
                service.store(file, NotificationMediaEventCatalog.WORKER_PROGRESS_GROWING.code());

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo("cards");
        assertThat(request.key()).startsWith("notification-media/worker_progress_growing/");
        assertThat(request.key()).endsWith(".png");
        assertThat(request.contentType()).isEqualTo("image/png");
        assertThat(stored.imageUrl()).isEqualTo("https://cdn.example/" + request.key());
    }
}

package com.hunt.otziv.s3.cleanup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService.Lease;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

class S3ObjectCleanupQueueTest {

    private NamedParameterJdbcTemplate jdbc;
    private S3Client s3;
    private SchedulerLeaseService leaseService;
    private S3ObjectCleanupQueue queue;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        s3 = mock(S3Client.class);
        leaseService = mock(SchedulerLeaseService.class);
        queue = new S3ObjectCleanupQueue(jdbc, s3, leaseService);
        ReflectionTestUtils.setField(queue, "configuredBatchSize", 25);
        ReflectionTestUtils.setField(queue, "leaseDuration", Duration.ofHours(1));
        ReflectionTestUtils.setField(queue, "deleteTimeout", Duration.ofSeconds(30));
    }

    @Test
    void enqueueIsIdempotentAndRejectsInvalidS3Keys() {
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        assertThat(queue.enqueueBestEffort("bucket", "reviews/42-file.jpg", "rollback")).isTrue();
        assertThat(queue.enqueueBestEffort("bucket", "", "rollback")).isFalse();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(MapSqlParameterSource.class));
        assertThat(sql.getValue())
                .contains("cleanup_reason = :reason")
                .doesNotContain("VALUES(");
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletesDueObjectThenRemovesDurableQueueRow() {
        byte[] identity = new byte[32];
        identity[0] = 7;
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        )).thenReturn(List.of(new S3ObjectCleanupQueue.CleanupItem(
                9L,
                identity,
                "bucket",
                "reviews/42-file.jpg",
                0
        )));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        assertThat(queue.processDueBatch()).isEqualTo(1);

        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("bucket");
        assertThat(request.getValue().key()).isEqualTo("reviews/42-file.jpg");
        assertThat(request.getValue().overrideConfiguration()).isPresent();
    }

    @Test
    void schedulerSkipsDatabaseScanWhenAnotherInstanceOwnsLease() {
        when(leaseService.tryAcquire(anyString(), any(Duration.class))).thenReturn(Optional.empty());

        queue.cleanupDueObjects();

        verify(jdbc, never()).query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        );
        verify(leaseService, never()).release(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void schedulerAlwaysReleasesLeaseAfterBatch() {
        Lease lease = new Lease("s3-object-cleanup", "owner", 2L);
        when(leaseService.tryAcquire(anyString(), any(Duration.class))).thenReturn(Optional.of(lease));
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)
        )).thenReturn(List.of());

        queue.cleanupDueObjects();

        verify(leaseService).release(lease);
    }
}

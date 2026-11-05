package com.hunt.otziv.s3.backup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.email.service.EmailService;
import com.hunt.otziv.s3.backup.config.BackupProperties;
import com.hunt.otziv.s3.backup.config.BackupS3Properties;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRetentionResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

class DatabaseBackupServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesFailClosedEncryptionAndOptionalEmailConfiguration() throws Exception {
        BackupProperties properties = validProperties();
        properties.setEncryptionKeyBase64("");

        assertThatThrownBy(() -> service(properties).validateAndPrepareConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encryption-key-base64");

        properties.setEncryptionKeyBase64(encodedKey());
        properties.getMail().setEnabled(true);
        assertThatThrownBy(() -> service(properties).validateAndPrepareConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backup.mail.to");
    }

    @Test
    void rejectsZeroPartSizeAndUnboundedOperationSettings() {
        BackupProperties properties = validProperties();
        properties.setPartSizeMb(0);

        assertThatThrownBy(() -> service(properties).validateAndPrepareConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("part-size-mb");

        properties.setPartSizeMb(10);
        properties.setDumpTimeout(Duration.ofDays(2));
        assertThatThrownBy(() -> service(properties).validateAndPrepareConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dump-timeout");
    }

    @Test
    void requiresMeasuredRestoreRtoForEveryEnabledBackup() {
        BackupProperties properties = validProperties();
        properties.setRestoreDrillRto(null);

        assertThatThrownBy(() -> service(properties).validateAndPrepareConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restore-drill-rto");
    }

    @Test
    void encryptsWithAuthenticatedAesGcmEnvelope() throws Exception {
        DatabaseBackupService service = service(validProperties());
        Path source = temporaryDirectory.resolve("dump.sql.gz");
        Path encrypted = temporaryDirectory.resolve("dump.sql.gz.enc");
        byte[] plaintext = "sensitive database contents".getBytes(StandardCharsets.UTF_8);
        byte[] key = Base64.getDecoder().decode(encodedKey());
        Files.write(source, plaintext);

        service.encryptAesGcm(source, encrypted, key);

        byte[] envelope = Files.readAllBytes(encrypted);
        assertThat(envelope).startsWith(DatabaseBackupService.ENCRYPTED_FILE_MAGIC);
        assertThat(envelope).isNotEqualTo(plaintext);
        assertThat(decrypt(envelope, key)).isEqualTo(plaintext);

        envelope[envelope.length - 1] ^= 1;
        assertThatThrownBy(() -> decrypt(envelope, key)).isInstanceOf(Exception.class);
    }

    @Test
    void chunkedEnvelopeAuthenticatesEveryChunkAndRejectsTruncation() throws Exception {
        DatabaseBackupService service = service(validProperties());
        Path source = temporaryDirectory.resolve("large.sql.gz");
        Path encrypted = temporaryDirectory.resolve("large.sql.gz.enc");
        byte[] plaintext = new byte[150_000];
        for (int index = 0; index < plaintext.length; index++) {
            plaintext[index] = (byte) (index * 31);
        }
        byte[] key = Base64.getDecoder().decode(encodedKey());
        Files.write(source, plaintext);

        service.encryptAesGcm(source, encrypted, key, 64 * 1024);

        byte[] envelope = Files.readAllBytes(encrypted);
        assertThat(decrypt(envelope, key)).isEqualTo(plaintext);

        byte[] middleTampered = envelope.clone();
        middleTampered[DatabaseBackupService.ENCRYPTED_FILE_HEADER_BYTES + 70_000] ^= 1;
        assertThatThrownBy(() -> decrypt(middleTampered, key)).isInstanceOf(Exception.class);

        byte[] truncated = java.util.Arrays.copyOf(
                envelope,
                DatabaseBackupService.ENCRYPTED_FILE_HEADER_BYTES
                        + (64 * 1024)
                        + DatabaseBackupService.GCM_TAG_BYTES
        );
        assertThatThrownBy(() -> decrypt(truncated, key))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("length");
    }

    @Test
    void independentlyAuthenticatesDownloadedClientSideEnvelope() throws Exception {
        DatabaseBackupService service = service(validProperties());
        Path source = temporaryDirectory.resolve("verify-source.sql.gz");
        Path encrypted = temporaryDirectory.resolve("verify-source.sql.gz.enc");
        byte[] key = Base64.getDecoder().decode(encodedKey());
        Files.write(source, new byte[180_000]);
        service.encryptAesGcm(source, encrypted, key, 64 * 1024);

        DatabaseBackupService.verifyEncryptedEnvelope(encrypted, key);

        byte[] tampered = Files.readAllBytes(encrypted);
        tampered[tampered.length - 1] ^= 1;
        Files.write(encrypted, tampered);
        assertThatThrownBy(() -> DatabaseBackupService.verifyEncryptedEnvelope(encrypted, key))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authentication failed");
    }

    @Test
    void splitsExactMultipleWithoutLeavingEmptyTrailingPart() throws Exception {
        DatabaseBackupService service = service(validProperties());
        Path source = temporaryDirectory.resolve("backup.enc");
        Files.write(source, new byte[] {1, 2, 3, 4});

        List<Path> parts = service.splitFile(source, 2);

        assertThat(parts).hasSize(2);
        assertThat(Files.readAllBytes(parts.get(0))).containsExactly(1, 2);
        assertThat(Files.readAllBytes(parts.get(1))).containsExactly(3, 4);
        assertThat(temporaryDirectory.resolve("backup.enc.part2")).doesNotExist();
    }

    @Test
    void encryptionNeverOverwritesOrDeletesAPreexistingOutput() throws Exception {
        DatabaseBackupService service = service(validProperties());
        Path source = temporaryDirectory.resolve("source.sql.gz");
        Path output = temporaryDirectory.resolve("existing.sql.gz.enc");
        byte[] marker = "owned-by-another-run".getBytes(StandardCharsets.UTF_8);
        Files.write(source, new byte[] {1, 2, 3});
        Files.write(output, marker);

        assertThatThrownBy(() -> service.encryptAesGcm(
                source,
                output,
                Base64.getDecoder().decode(encodedKey())
        )).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);

        assertThat(Files.readAllBytes(output)).isEqualTo(marker);
    }

    @Test
    void splitNeverDeletesAPreexistingPartCollision() throws Exception {
        DatabaseBackupService service = service(validProperties());
        Path source = temporaryDirectory.resolve("collision.enc");
        Path existingPart = temporaryDirectory.resolve("collision.enc.part0");
        byte[] marker = "owned-by-another-run".getBytes(StandardCharsets.UTF_8);
        Files.write(source, new byte[] {1, 2, 3, 4});
        Files.write(existingPart, marker);

        assertThatThrownBy(() -> service.splitFile(source, 2))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);

        assertThat(Files.readAllBytes(existingPart)).isEqualTo(marker);
    }

    @Test
    void stderrCaptureIsStrictlyBounded() throws Exception {
        DatabaseBackupService.BoundedOutputStream output =
                new DatabaseBackupService.BoundedOutputStream(4);

        output.write("abcdefgh".getBytes(StandardCharsets.UTF_8));

        assertThat(output.asUtf8()).isEqualTo("abcd");
        assertThat(output.wasTruncated()).isTrue();
    }

    @Test
    void headVerificationRequiresHashSizeAndConfiguredServerSideEncryption() {
        HeadObjectResponse valid = HeadObjectResponse.builder()
                .contentLength(42L)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .metadata(Map.of("sha256", "abc123"))
                .build();

        assertThat(DatabaseBackupService.verifyHeadResponse(valid, 42L, "abc123", true))
                .isEqualTo("AES256");

        HeadObjectResponse missingEncryption = valid.toBuilder().serverSideEncryption((String) null).build();
        assertThatThrownBy(() -> DatabaseBackupService.verifyHeadResponse(
                missingEncryption,
                42L,
                "abc123",
                true
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("server-side encryption");

        assertThat(DatabaseBackupService.verifyHeadResponse(
                missingEncryption,
                42L,
                "abc123",
                false
        )).isEqualTo("NONE_REPORTED");
        assertThat(DatabaseBackupService.verifyHeadResponse(valid, 42L, "abc123", false))
                .isEqualTo("AES256");

        HeadObjectResponse unsupportedEncryption = valid.toBuilder()
                .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                .build();
        assertThatThrownBy(() -> DatabaseBackupService.verifyHeadResponse(
                unsupportedEncryption,
                42L,
                "abc123",
                false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("unsupported");

        HeadObjectResponse wrongHash = valid.toBuilder().metadata(Map.of("sha256", "different")).build();
        assertThatThrownBy(() -> DatabaseBackupService.verifyHeadResponse(
                wrongHash,
                42L,
                "abc123",
                true
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("SHA-256");
    }

    @Test
    void objectRetentionVerificationUsesDedicatedResponseAndFailsClosed() {
        Instant expectedRetention = Instant.parse("2030-01-01T00:00:00Z");
        GetObjectRetentionResponse valid = GetObjectRetentionResponse.builder()
                .retention(ObjectLockRetention.builder()
                        .mode(ObjectLockRetentionMode.GOVERNANCE)
                        .retainUntilDate(expectedRetention)
                        .build())
                .build();

        DatabaseBackupService.verifyObjectRetention(
                valid,
                ObjectLockMode.GOVERNANCE,
                expectedRetention
        );

        assertThatThrownBy(() -> DatabaseBackupService.verifyObjectRetention(
                null,
                ObjectLockMode.GOVERNANCE,
                expectedRetention
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("no Object Lock retention");

        GetObjectRetentionResponse wrongMode = valid.toBuilder()
                .retention(valid.retention().toBuilder()
                        .mode(ObjectLockRetentionMode.COMPLIANCE)
                        .build())
                .build();
        assertThatThrownBy(() -> DatabaseBackupService.verifyObjectRetention(
                wrongMode,
                ObjectLockMode.GOVERNANCE,
                expectedRetention
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("mode");

        GetObjectRetentionResponse missingRetention = valid.toBuilder()
                .retention(ObjectLockRetention.builder()
                        .mode(ObjectLockRetentionMode.GOVERNANCE)
                        .build())
                .build();
        assertThat(DatabaseBackupService.verifyHeadResponse(
                HeadObjectResponse.builder()
                        .contentLength(42L)
                        .serverSideEncryption(ServerSideEncryption.AES256)
                        .metadata(Map.of("sha256", "abc123"))
                        .build(),
                42L,
                "abc123",
                true
        )).isEqualTo("AES256");
        assertThatThrownBy(() -> DatabaseBackupService.verifyObjectRetention(
                missingRetention,
                ObjectLockMode.GOVERNANCE,
                expectedRetention
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("retention");

        GetObjectRetentionResponse shortenedRetention = valid.toBuilder()
                .retention(valid.retention().toBuilder()
                        .retainUntilDate(expectedRetention.minusSeconds(2))
                        .build())
                .build();
        assertThatThrownBy(() -> DatabaseBackupService.verifyObjectRetention(
                shortenedRetention,
                ObjectLockMode.GOVERNANCE,
                expectedRetention
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("retention");
    }

    @Test
    void pinsEveryPostUploadVerificationRequestToTheExactUploadedVersion() {
        String versionId = "selectel-object-version-123";
        Duration timeout = Duration.ofMinutes(5);

        assertThat(DatabaseBackupService.buildHeadObjectRequest(
                "backup-bucket",
                "backup/key.enc",
                versionId,
                timeout
        ).versionId()).isEqualTo(versionId);
        assertThat(DatabaseBackupService.buildGetObjectRetentionRequest(
                "backup-bucket",
                "backup/key.enc",
                versionId,
                timeout
        ).versionId()).isEqualTo(versionId);
        assertThat(DatabaseBackupService.buildGetObjectRequest(
                "backup-bucket",
                "backup/key.enc",
                versionId,
                timeout
        ).versionId()).isEqualTo(versionId);
    }

    @Test
    void computesStreamingSha256() throws Exception {
        Path source = temporaryDirectory.resolve("hash-me.enc");
        Files.writeString(source, "verified backup", StandardCharsets.UTF_8);

        assertThat(DatabaseBackupService.sha256(source))
                .isEqualTo("033ea45728f0ba7ce7552bfc6ce49fff338e1269f45c72eded39cb3dc0371087");
    }

    @Test
    void writesMachineReadableVerifiedEvidenceWithoutCredentials() throws Exception {
        BackupProperties properties = validProperties();
        properties.setSourceCommit("abc123def456");
        properties.setRestoreDrillRto(Duration.ofSeconds(91));
        DatabaseBackupService service = service(properties);
        Path evidenceFile = temporaryDirectory.resolve("backup-evidence.jsonl");

        service.writeEvidence(
                evidenceFile,
                new DatabaseBackupService.VerifiedBackup(
                        DatabaseBackupService.DATABASE_MYSQL,
                        Instant.parse("2026-08-03T08:00:00Z"),
                        "independent-backup-bucket",
                        "backup/otziv/backup_run.enc",
                        "version-1",
                        "033ea45728f0ba7ce7552bfc6ce49fff338e1269f45c72eded39cb3dc0371087",
                        1234L,
                        "OTZIVDB2_AES_256_GCM",
                        true,
                        "AES256",
                        true,
                        ObjectLockMode.GOVERNANCE,
                        Instant.parse("2026-09-02T08:00:00Z"),
                        Duration.ofSeconds(12)
                ),
                new DatabaseBackupService.BackupCleanupResult(true, true, true, true, true)
        );

        String line = Files.readString(evidenceFile, StandardCharsets.UTF_8).trim();
        var json = new ObjectMapper().readTree(line);
        assertThat(json.path("schema").asText()).isEqualTo("otziv-backup-evidence-v1");
        assertThat(json.path("phase").asText()).isEqualTo("completed");
        assertThat(json.path("sourceCommit").asText()).isEqualTo("abc123def456");
        assertThat(json.path("objectVersionId").asText()).isEqualTo("version-1");
        assertThat(json.path("restoreDrillRtoSeconds").asLong()).isEqualTo(91L);
        assertThat(json.path("verification").path("download").asBoolean()).isTrue();
        assertThat(json.path("verification").path("clientSideEncryption").asText())
                .isEqualTo("OTZIVDB2_AES_256_GCM");
        assertThat(json.path("verification").path("clientSideEnvelopeVerified").asBoolean()).isTrue();
        assertThat(json.path("verification").path("serverSideEncryption").asText()).isEqualTo("AES256");
        assertThat(json.path("verification").path("serverSideEncryptionRequired").asBoolean()).isTrue();
        assertThat(json.path("temporaryFileCleanup").path("plaintextSqlDeleted").asBoolean()).isTrue();
        assertThat(json.path("temporaryFileCleanup").path("plaintextGzipDeleted").asBoolean()).isTrue();
        assertThat(json.path("temporaryFileCleanup").path("verificationDownloadDeleted").asBoolean()).isTrue();
        assertThat(json.path("temporaryFileCleanup").path("encryptedTempDeleted").asBoolean()).isTrue();
        assertThat(json.path("temporaryFileCleanup").path("encryptedPartsDeleted").asBoolean()).isTrue();
        assertThat(json.path("emailDelivery").path("enabled").asBoolean()).isFalse();
        assertThat(json.path("emailDelivery").path("attempted").asBoolean()).isFalse();
        assertThat(json.path("emailDelivery").path("succeeded").asBoolean()).isFalse();
        assertThat(json.path("emailDelivery").path("encryptedPartCount").asInt()).isZero();
        assertThat(line).doesNotContain("backup-secret", "secret", encodedKey());
    }

    @Test
    void remoteVerifiedEvidenceSuppressesDuplicateUploadBeforeEmailAndCleanupComplete() throws Exception {
        DatabaseBackupService service = service(validProperties());
        Path evidenceFile = temporaryDirectory.resolve("backup-evidence.jsonl");
        Instant verifiedAt = Instant.parse("2026-08-03T08:00:00Z");
        String requestId = "selectel-remote-verified-20260804";

        service.writeRemoteVerificationEvidence(
                evidenceFile,
                verifiedBackup(verifiedAt, "backup/otziv/backup_remote_verified.enc", "version-remote"),
                "manual",
                requestId,
                true
        );

        var json = new ObjectMapper().readTree(Files.readString(evidenceFile, StandardCharsets.UTF_8).trim());
        assertThat(json.path("phase").asText()).isEqualTo("remote-verified");
        assertThat(json.path("temporaryFileCleanup").path("plaintextSqlDeleted").asBoolean()).isTrue();
        assertThat(json.path("temporaryFileCleanup").path("plaintextGzipDeleted").asBoolean()).isTrue();
        assertThat(json.path("temporaryFileCleanup").path("verificationDownloadDeleted").asBoolean()).isTrue();
        assertThat(json.path("temporaryFileCleanup").path("encryptedTempDeleted").asBoolean()).isFalse();
        assertThat(json.path("temporaryFileCleanup").path("encryptedPartsDeleted").asBoolean()).isTrue();
        assertThat(json.path("emailDelivery").path("enabled").asBoolean()).isTrue();
        assertThat(json.path("emailDelivery").path("attempted").asBoolean()).isFalse();
        assertThat(json.path("emailDelivery").path("succeeded").asBoolean()).isFalse();

        DatabaseBackupService.BackupEvidenceSummary summary = service.readEvidenceSummary();
        assertThat(summary.latestVerifiedAt()).contains(verifiedAt);
        assertThat(summary.containsManualRequest(requestId)).isTrue();
    }

    @Test
    void recordsCompletedEmailFailureAndDoesNotInvalidateVerifiedRemoteBackup() throws Exception {
        DatabaseBackupService service = service(validProperties());
        Path evidenceFile = temporaryDirectory.resolve("backup-evidence.jsonl");
        Instant verifiedAt = Instant.parse("2026-08-03T08:00:00Z");
        String requestId = "selectel-email-failure-20260804";
        DatabaseBackupService.VerifiedBackup verifiedBackup = verifiedBackup(
                verifiedAt,
                "backup/otziv/backup_email_failure.enc",
                "version-email"
        );
        service.writeRemoteVerificationEvidence(evidenceFile, verifiedBackup, "manual", requestId, true);

        assertThatThrownBy(() -> service.recordVerifiedBackupAndReportMailFailure(
                evidenceFile,
                verifiedBackup,
                new DatabaseBackupService.BackupCleanupResult(true, true, true, true, true),
                "manual",
                requestId,
                DatabaseBackupService.BackupMailDeliveryResult.failed(4),
                new IllegalStateException("SMTP unavailable")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("will not be uploaded again")
                .hasRootCauseMessage("SMTP unavailable");

        List<String> lines = Files.readAllLines(evidenceFile, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(2);
        var completed = new ObjectMapper().readTree(lines.get(1));
        assertThat(completed.path("phase").asText()).isEqualTo("completed");
        assertThat(completed.path("temporaryFileCleanup").path("encryptedTempDeleted").asBoolean()).isTrue();
        assertThat(completed.path("emailDelivery").path("enabled").asBoolean()).isTrue();
        assertThat(completed.path("emailDelivery").path("attempted").asBoolean()).isTrue();
        assertThat(completed.path("emailDelivery").path("succeeded").asBoolean()).isFalse();
        assertThat(completed.path("emailDelivery").path("encryptedPartCount").asInt()).isEqualTo(4);

        DatabaseBackupService.BackupEvidenceSummary summary = service.readEvidenceSummary();
        assertThat(summary.latestVerifiedAt()).contains(verifiedAt);
        assertThat(summary.containsManualRequest(requestId)).isTrue();
    }

    @Test
    void readsOnlyVerifiedEvidenceAndTracksCompletedManualRequest() throws Exception {
        BackupProperties properties = validProperties();
        DatabaseBackupService service = service(properties);
        Path evidenceFile = temporaryDirectory.resolve("backup-evidence.jsonl");
        Instant verifiedAt = Instant.parse("2026-08-03T08:00:00Z");

        service.writeEvidence(
                evidenceFile,
                new DatabaseBackupService.VerifiedBackup(
                        DatabaseBackupService.DATABASE_MYSQL,
                        verifiedAt,
                        "independent-backup-bucket",
                        "backup/otziv/backup_manual.enc",
                        "version-2",
                        "033ea45728f0ba7ce7552bfc6ce49fff338e1269f45c72eded39cb3dc0371087",
                        1234L,
                        "OTZIVDB2_AES_256_GCM",
                        true,
                        "AES256",
                        true,
                        ObjectLockMode.GOVERNANCE,
                        Instant.parse("2026-09-02T08:00:00Z"),
                        Duration.ofSeconds(12)
                ),
                new DatabaseBackupService.BackupCleanupResult(true, true, true, true, true),
                "manual",
                "selectel-verification-20260804"
        );
        Files.writeString(
                evidenceFile,
                "{\"schema\":\"otziv-backup-evidence-v1\",\"timestampUtc\":\"2099-01-01T00:00:00Z\"}\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND
        );

        DatabaseBackupService.BackupEvidenceSummary summary = service.readEvidenceSummary();

        assertThat(summary.latestVerifiedAt()).contains(verifiedAt);
        assertThat(summary.containsManualRequest("selectel-verification-20260804")).isTrue();
    }

    @Test
    void readsVerifiedEvidenceWhenProviderReportsNoServerSideEncryption() throws Exception {
        BackupProperties properties = validProperties();
        DatabaseBackupService service = service(properties);
        Path evidenceFile = temporaryDirectory.resolve("backup-evidence.jsonl");
        Instant verifiedAt = Instant.parse("2026-08-03T08:00:00Z");
        service.writeEvidence(
                evidenceFile,
                new DatabaseBackupService.VerifiedBackup(
                        DatabaseBackupService.DATABASE_MYSQL,
                        verifiedAt,
                        "independent-backup-bucket",
                        "backup/otziv/backup_selectel.enc",
                        "version-3",
                        "033ea45728f0ba7ce7552bfc6ce49fff338e1269f45c72eded39cb3dc0371087",
                        1234L,
                        "OTZIVDB2_AES_256_GCM",
                        true,
                        "NONE_REPORTED",
                        false,
                        ObjectLockMode.GOVERNANCE,
                        Instant.parse("2026-09-02T08:00:00Z"),
                        Duration.ofSeconds(12)
                ),
                new DatabaseBackupService.BackupCleanupResult(true, true, true, true, true)
        );

        assertThat(service.readEvidenceSummary().latestVerifiedAt()).contains(verifiedAt);
    }

    @Test
    void readsLegacyAes256EvidenceButRejectsPartialNewEncryptionEvidence() throws Exception {
        BackupProperties properties = validProperties();
        DatabaseBackupService service = service(properties);
        Path evidenceFile = temporaryDirectory.resolve("backup-evidence.jsonl");
        service.writeEvidence(
                evidenceFile,
                new DatabaseBackupService.VerifiedBackup(
                        DatabaseBackupService.DATABASE_MYSQL,
                        Instant.parse("2026-08-03T08:00:00Z"),
                        "independent-backup-bucket",
                        "backup/otziv/backup_legacy.enc",
                        "version-4",
                        "033ea45728f0ba7ce7552bfc6ce49fff338e1269f45c72eded39cb3dc0371087",
                        1234L,
                        "OTZIVDB2_AES_256_GCM",
                        true,
                        "AES256",
                        true,
                        null,
                        null,
                        Duration.ofSeconds(12)
                ),
                new DatabaseBackupService.BackupCleanupResult(true, true, true, true, true)
        );
        ObjectMapper mapper = new ObjectMapper();
        var legacy = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                Files.readString(evidenceFile, StandardCharsets.UTF_8).trim()
        );
        var verification = (com.fasterxml.jackson.databind.node.ObjectNode) legacy.path("verification");
        verification.remove(List.of(
                "clientSideEncryption",
                "clientSideEnvelopeVerified",
                "serverSideEncryptionRequired"
        ));
        Files.writeString(evidenceFile, mapper.writeValueAsString(legacy) + "\n", StandardCharsets.UTF_8);

        assertThat(service.readEvidenceSummary().latestVerifiedAt())
                .contains(Instant.parse("2026-08-03T08:00:00Z"));

        verification.put("serverSideEncryptionRequired", false);
        Files.writeString(evidenceFile, mapper.writeValueAsString(legacy) + "\n", StandardCharsets.UTF_8);
        assertThat(service.readEvidenceSummary().latestVerifiedAt()).isEmpty();
    }

    @Test
    void removesOnlyStrictlyOwnedStaleTemporaryFilesAndKeepsEvidence() throws Exception {
        String runId = "2026-08-04_07-00-00-123_0123456789abcdef0123456789abcdef";
        List<Path> owned = List.of(
                temporaryDirectory.resolve("backup_" + runId + ".sql"),
                temporaryDirectory.resolve("backup_" + runId + ".sql.gz"),
                temporaryDirectory.resolve("backup_" + runId + ".sql.gz.enc"),
                temporaryDirectory.resolve("backup_" + runId + ".sql.gz.enc.part0"),
                temporaryDirectory.resolve(".verify_" + runId + ".sql.gz.enc")
        );
        for (Path path : owned) {
            Files.writeString(path, "sensitive");
        }
        Path evidence = temporaryDirectory.resolve("backup-evidence.jsonl");
        Path unrelated = temporaryDirectory.resolve("backup_user.sql");
        Files.writeString(evidence, "evidence");
        Files.writeString(unrelated, "keep");

        assertThat(DatabaseBackupService.cleanupStaleTemporaryFiles(temporaryDirectory, evidence))
                .isEqualTo(owned.size());
        assertThat(owned).allMatch(path -> !Files.exists(path));
        assertThat(evidence).exists();
        assertThat(unrelated).exists();
    }

    @Test
    void localWorkDirectoryLockPreventsOverlappingCleanupAndRuns() throws Exception {
        try (DatabaseBackupService.LocalBackupRunLock ignored =
                     DatabaseBackupService.acquireLocalBackupRunLock(temporaryDirectory)) {
            assertThatThrownBy(() -> DatabaseBackupService.acquireLocalBackupRunLock(temporaryDirectory))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already using");
        }
        try (DatabaseBackupService.LocalBackupRunLock ignored =
                     DatabaseBackupService.acquireLocalBackupRunLock(temporaryDirectory)) {
            assertThat(temporaryDirectory.resolve(".database-backup-run.lock")).exists();
        }
    }

    @Test
    void requiredCleanupFailsClosedWhenAnOwnedPathCannotBeDeleted() throws Exception {
        Path nonEmptyDirectory = temporaryDirectory.resolve("undeletable-backup-artifact");
        Files.createDirectory(nonEmptyDirectory);
        Files.writeString(nonEmptyDirectory.resolve("child"), "still present", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> DatabaseBackupService.deleteRequired(nonEmptyDirectory))
                .isInstanceOf(java.io.IOException.class);
        assertThat(nonEmptyDirectory).exists();
    }

    @Test
    void keycloakDatabaseIsBackedUpByDefaultAndFailsClosedWithoutItsConnection() throws Exception {
        assertThat(new BackupProperties().getKeycloak().isEnabled()).isTrue();

        BackupProperties properties = validPropertiesWithKeycloak();
        properties.getKeycloak().setDb(" ");
        assertThatThrownBy(() -> service(properties).validateAndPrepareConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backup.keycloak.db");

        properties.getKeycloak().setDb("keycloak");
        properties.getKeycloak().setPassword("");
        assertThatThrownBy(() -> service(properties).validateAndPrepareConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backup.keycloak.password");

        properties.getKeycloak().setPassword(credentialPlaceholder());
        properties.getKeycloak().setEvidenceFileName("BACKUP-EVIDENCE.jsonl");
        assertThatThrownBy(() -> service(properties).validateAndPrepareConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must differ from backup.evidence-file-name");

        properties.getKeycloak().setEvidenceFileName("../keycloak.jsonl");
        assertThatThrownBy(() -> service(properties).validateAndPrepareConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simple file name");

        properties.getKeycloak().setEvidenceFileName("keycloak-backup-evidence.jsonl");
        DatabaseBackupService.BackupRunConfiguration configuration =
                service(properties).validateAndPrepareConfiguration();
        assertThat(configuration.targets())
                .extracting(DatabaseBackupService.BackupTarget::database)
                .containsExactly(DatabaseBackupService.DATABASE_MYSQL, DatabaseBackupService.DATABASE_KEYCLOAK);
        assertThat(configuration.targets())
                .extracting(target -> target.evidenceFile().getFileName().toString())
                .containsExactly("backup-evidence.jsonl", "keycloak-backup-evidence.jsonl");
    }

    @Test
    void buildsPlainPortablePgDumpCommandWithoutTheCredential() {
        BackupProperties.Keycloak keycloak = validPropertiesWithKeycloak().getKeycloak();

        List<String> command = DatabaseBackupService.buildKeycloakDumpCommand(keycloak);

        assertThat(command).containsExactly(
                "/usr/lib/postgresql/17/bin/pg_dump",
                "--host=keycloak-postgres",
                "--port=5432",
                "--username=keycloak",
                "--dbname=keycloak",
                "--format=plain",
                "--encoding=UTF8",
                "--no-owner",
                "--no-privileges",
                "--no-password",
                "--lock-wait-timeout=60000"
        );
        assertThat(String.join(" ", command)).doesNotContain(credentialPlaceholder());
    }

    @Test
    void acceptsOnlyCompleteKeycloakDumpsOfTheKeycloakSchema() throws Exception {
        Path complete = temporaryDirectory.resolve("complete.sql");
        Files.writeString(complete, keycloakDump(), StandardCharsets.UTF_8);
        DatabaseBackupService.verifyPostgresDump(complete, DatabaseBackupService.REQUIRED_KEYCLOAK_TABLES);

        Path truncated = temporaryDirectory.resolve("truncated.sql");
        Files.writeString(
                truncated,
                keycloakDump().substring(0, keycloakDump().indexOf(DatabaseBackupService.POSTGRES_DUMP_COMPLETE_MARKER)),
                StandardCharsets.UTF_8
        );
        assertThatThrownBy(() -> DatabaseBackupService.verifyPostgresDump(
                truncated,
                DatabaseBackupService.REQUIRED_KEYCLOAK_TABLES
        )).hasMessageContaining("completion marker");

        Path trailingStatement = temporaryDirectory.resolve("trailing.sql");
        Files.writeString(trailingStatement, keycloakDump() + "DROP TABLE public.realm;\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> DatabaseBackupService.verifyPostgresDump(
                trailingStatement,
                DatabaseBackupService.REQUIRED_KEYCLOAK_TABLES
        )).hasMessageContaining("completion marker");

        Path otherDatabase = temporaryDirectory.resolve("other.sql");
        Files.writeString(otherDatabase, keycloakDump().replace("public.credential", "public.notes"), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> DatabaseBackupService.verifyPostgresDump(
                otherDatabase,
                DatabaseBackupService.REQUIRED_KEYCLOAK_TABLES
        )).hasMessageContaining("missing required tables: [credential]");
    }

    @Test
    void keycloakEvidenceIsJournaledSeparatelyAndCombinedConservatively() throws Exception {
        DatabaseBackupService service = service(validPropertiesWithKeycloak());
        Path mysqlEvidence = temporaryDirectory.resolve("backup-evidence.jsonl");
        Path keycloakEvidence = temporaryDirectory.resolve("keycloak-backup-evidence.jsonl");
        Instant mysqlVerifiedAt = Instant.parse("2026-11-04T23:00:00Z");
        Instant keycloakVerifiedAt = Instant.parse("2026-11-04T23:05:00Z");

        service.writeEvidence(
                mysqlEvidence,
                verifiedBackup(mysqlVerifiedAt, "backup/otziv/backup_run.enc", "version-m"),
                new DatabaseBackupService.BackupCleanupResult(true, true, true, true, true),
                "manual",
                "both-databases-1"
        );
        assertThat(service.readEvidenceSummary().latestVerifiedAt()).isEmpty();
        assertThat(service.readEvidenceSummary().containsManualRequest("both-databases-1")).isFalse();

        // A Keycloak receipt appended to the MySQL journal must not count for either database.
        service.writeEvidence(
                mysqlEvidence,
                keycloakVerifiedBackup(keycloakVerifiedAt),
                new DatabaseBackupService.BackupCleanupResult(true, true, true, true, true),
                "manual",
                "both-databases-1"
        );
        assertThat(service.readTargetEvidenceSummary(DatabaseBackupService.DATABASE_MYSQL).latestVerifiedAt())
                .contains(mysqlVerifiedAt);
        assertThat(service.readEvidenceSummary().latestVerifiedAt()).isEmpty();

        service.writeEvidence(
                keycloakEvidence,
                keycloakVerifiedBackup(keycloakVerifiedAt),
                new DatabaseBackupService.BackupCleanupResult(true, true, true, true, true),
                "manual",
                "both-databases-1"
        );
        var keycloakLine = new ObjectMapper().readTree(Files.readString(keycloakEvidence, StandardCharsets.UTF_8).trim());
        assertThat(keycloakLine.path("database").asText()).isEqualTo("keycloak");
        assertThat(keycloakLine.path("objectKey").asText()).contains("keycloak_");

        DatabaseBackupService.BackupEvidenceSummary combined = service.readEvidenceSummary();
        assertThat(combined.latestVerifiedAt()).contains(mysqlVerifiedAt);
        assertThat(combined.containsManualRequest("both-databases-1")).isTrue();
        assertThat(service.readTargetEvidenceSummary(DatabaseBackupService.DATABASE_KEYCLOAK).latestVerifiedAt())
                .contains(keycloakVerifiedAt);
    }

    @Test
    void removesStaleKeycloakTemporaryFilesAndKeepsBothJournals() throws Exception {
        String runId = "2026-11-05_07-00-00-123_0123456789abcdef0123456789abcdef";
        List<Path> owned = List.of(
                temporaryDirectory.resolve("keycloak_" + runId + ".sql"),
                temporaryDirectory.resolve("keycloak_" + runId + ".sql.gz"),
                temporaryDirectory.resolve("keycloak_" + runId + ".sql.gz.enc"),
                temporaryDirectory.resolve("keycloak_" + runId + ".sql.gz.enc.part1"),
                temporaryDirectory.resolve(".verify_keycloak_" + runId + ".sql.gz.enc")
        );
        for (Path path : owned) {
            Files.writeString(path, "sensitive");
        }
        Path mysqlEvidence = temporaryDirectory.resolve("backup-evidence.jsonl");
        Path keycloakEvidence = temporaryDirectory.resolve("keycloak-backup-evidence.jsonl");
        Path unrelated = temporaryDirectory.resolve("keycloak_export.sql");
        Files.writeString(mysqlEvidence, "evidence");
        Files.writeString(keycloakEvidence, "evidence");
        Files.writeString(unrelated, "keep");

        assertThat(DatabaseBackupService.cleanupStaleTemporaryFiles(temporaryDirectory, mysqlEvidence, keycloakEvidence))
                .isEqualTo(owned.size());
        assertThat(owned).allMatch(path -> !Files.exists(path));
        assertThat(mysqlEvidence).exists();
        assertThat(keycloakEvidence).exists();
        assertThat(unrelated).exists();
    }

    @Test
    void backsUpBothDatabasesIntoSeparateVerifiedObjectsAndJournals() throws Exception {
        BackupProperties properties = validPropertiesWithKeycloak();
        Path tools = Files.createDirectory(temporaryDirectory.resolve("tools"));
        properties.getMysql().setDumpBinary(fakeDumpBinary(tools, "mysqldump", mysqlDump()).toString());
        properties.getKeycloak().setDumpBinary(fakeDumpBinary(tools, "pg_dump", keycloakDump()).toString());
        RecordingS3Client s3 = new RecordingS3Client();
        DatabaseBackupService service = service(properties, s3);

        service.runManualBackup("restore-drill-20261105");

        assertThat(s3.objects.keySet()).hasSize(2);
        String mysqlKey = s3.onlyKeyStartingWith("backup/otziv-prod/backup_");
        String keycloakKey = s3.onlyKeyStartingWith("backup/otziv-prod/keycloak_");
        assertThat(s3.metadata.get(mysqlKey)).containsEntry("database", "mysql");
        assertThat(s3.metadata.get(keycloakKey)).containsEntry("database", "keycloak");
        byte[] key = Base64.getDecoder().decode(encodedKey());
        assertThat(gunzip(decrypt(s3.objects.get(keycloakKey), key))).isEqualTo(keycloakDump());
        assertThat(gunzip(decrypt(s3.objects.get(mysqlKey), key))).isEqualTo(mysqlDump());

        List<String> keycloakEvidence = Files.readAllLines(
                temporaryDirectory.resolve("keycloak-backup-evidence.jsonl"),
                StandardCharsets.UTF_8
        );
        assertThat(keycloakEvidence).hasSize(2);
        for (String line : keycloakEvidence) {
            var json = new ObjectMapper().readTree(line);
            assertThat(json.path("database").asText()).isEqualTo("keycloak");
            assertThat(json.path("objectKey").asText()).isEqualTo(keycloakKey);
            assertThat(json.path("verification").path("download").asBoolean()).isTrue();
            assertThat(json.path("verification").path("clientSideEnvelopeVerified").asBoolean()).isTrue();
            assertThat(line).doesNotContain(credentialPlaceholder(), encodedKey());
        }
        assertThat(Files.readAllLines(temporaryDirectory.resolve("backup-evidence.jsonl"), StandardCharsets.UTF_8))
                .hasSize(2)
                .allMatch(line -> line.contains("\"database\":\"mysql\"") && line.contains(mysqlKey));
        assertThat(service.readEvidenceSummary().containsManualRequest("restore-drill-20261105")).isTrue();
        assertThat(workDirectoryFiles()).containsExactlyInAnyOrder(
                ".database-backup-run.lock",
                "backup-evidence.jsonl",
                "keycloak-backup-evidence.jsonl",
                "tools"
        );

        service.runManualBackup("restore-drill-20261105");
        assertThat(s3.objects).hasSize(2);
    }

    @Test
    void keycloakFailureKeepsTheMysqlBackupAndCatchUpRetriesOnlyKeycloak() throws Exception {
        BackupProperties properties = validPropertiesWithKeycloak();
        Path tools = Files.createDirectory(temporaryDirectory.resolve("tools"));
        properties.getMysql().setDumpBinary(fakeDumpBinary(tools, "mysqldump", mysqlDump()).toString());
        String incomplete = keycloakDump().substring(
                0,
                keycloakDump().indexOf(DatabaseBackupService.POSTGRES_DUMP_COMPLETE_MARKER)
        );
        properties.getKeycloak().setDumpBinary(fakeDumpBinary(tools, "pg_dump", incomplete).toString());
        RecordingS3Client s3 = new RecordingS3Client();
        DatabaseBackupService service = service(properties, s3);
        Instant missedOccurrence = Instant.now().minus(Duration.ofHours(1));

        assertThatThrownBy(service::runDailyBackup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database=keycloak")
                .hasRootCauseMessage("pg_dump output does not end with its completion marker");

        s3.onlyKeyStartingWith("backup/otziv-prod/backup_");
        assertThat(s3.objects).hasSize(1);
        assertThat(service.readTargetEvidenceSummary(DatabaseBackupService.DATABASE_MYSQL).latestVerifiedAt())
                .isPresent();
        assertThat(service.readEvidenceSummary().latestVerifiedAt()).isEmpty();
        assertThat(workDirectoryFiles()).containsExactlyInAnyOrder(
                ".database-backup-run.lock",
                "backup-evidence.jsonl",
                "tools"
        );

        Files.writeString(tools.resolve("pg_dump.sql"), keycloakDump(), StandardCharsets.UTF_8);
        service.runCatchUpBackup(missedOccurrence);

        assertThat(s3.objects).hasSize(2);
        s3.onlyKeyStartingWith("backup/otziv-prod/keycloak_");
        assertThat(service.readEvidenceSummary().latestVerifiedAt()).isPresent();

        service.runCatchUpBackup(missedOccurrence);
        assertThat(s3.objects).hasSize(2);
    }

    private DatabaseBackupService service(BackupProperties properties) {
        return service(properties, mock(S3Client.class));
    }

    private DatabaseBackupService service(BackupProperties properties, S3Client s3Client) {
        BackupS3Properties s3 = new BackupS3Properties();
        s3.setBucket("backup-bucket");
        s3.setProjectId("otziv-prod");
        return new DatabaseBackupService(
                properties,
                s3,
                s3Client,
                mock(EmailService.class),
                new ObjectMapper()
        );
    }

    private List<String> workDirectoryFiles() throws java.io.IOException {
        try (var files = Files.list(temporaryDirectory)) {
            return files.map(path -> path.getFileName().toString()).toList();
        }
    }

    /** A stand-in dump tool that prints a fixed SQL file and ignores its arguments. */
    private static Path fakeDumpBinary(Path tools, String name, String sql) throws java.io.IOException {
        Path output = tools.resolve(name + ".sql");
        Files.writeString(output, sql, StandardCharsets.UTF_8);
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Path script = tools.resolve(name + ".cmd");
            Files.writeString(script, "@type \"" + output + "\"\r\n@exit /b 0\r\n", StandardCharsets.UTF_8);
            return script;
        }
        Path script = tools.resolve(name + ".sh");
        Files.writeString(script, "#!/bin/sh\ncat '" + output + "'\n", StandardCharsets.UTF_8);
        assertThat(script.toFile().setExecutable(true)).isTrue();
        return script;
    }

    private static String mysqlDump() {
        return "-- MySQL dump\nCREATE TABLE `users` (`id` bigint NOT NULL);\n-- Dump completed\n";
    }

    private static String keycloakDump() {
        return """
                --
                -- PostgreSQL database dump
                --

                \\restrict 7Hk2

                SET statement_timeout = 0;
                CREATE TABLE public.credential (
                    id character varying(36) NOT NULL
                );
                CREATE TABLE public.realm (
                    id character varying(36) NOT NULL
                );
                CREATE TABLE public.user_entity (
                    id character varying(36) NOT NULL
                );
                COPY public.realm (id) FROM stdin;
                otziv
                \\.

                --
                -- PostgreSQL database dump complete
                --

                \\unrestrict 7Hk2

                """;
    }

    private static String gunzip(byte[] compressed) throws java.io.IOException {
        try (var input = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private DatabaseBackupService.VerifiedBackup keycloakVerifiedBackup(Instant verifiedAt) {
        return new DatabaseBackupService.VerifiedBackup(
                DatabaseBackupService.DATABASE_KEYCLOAK,
                verifiedAt,
                "independent-backup-bucket",
                "backup/otziv/keycloak_run.enc",
                "version-k",
                "033ea45728f0ba7ce7552bfc6ce49fff338e1269f45c72eded39cb3dc0371087",
                1234L,
                "OTZIVDB2_AES_256_GCM",
                true,
                "AES256",
                true,
                ObjectLockMode.GOVERNANCE,
                Instant.parse("2026-12-04T08:00:00Z"),
                Duration.ofSeconds(3)
        );
    }

    /** Versioned in-memory bucket that answers exactly the calls the backup verification makes. */
    private static final class RecordingS3Client implements S3Client {
        private final Map<String, byte[]> objects = new java.util.LinkedHashMap<>();
        private final Map<String, Map<String, String>> metadata = new java.util.LinkedHashMap<>();

        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {
        }

        @Override
        public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
            try (var input = body.contentStreamProvider().newStream()) {
                objects.put(request.key(), input.readAllBytes());
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
            metadata.put(request.key(), request.metadata());
            return PutObjectResponse.builder().versionId("version-" + objects.size()).build();
        }

        @Override
        public HeadObjectResponse headObject(HeadObjectRequest request) {
            return HeadObjectResponse.builder()
                    .contentLength((long) objects.get(request.key()).length)
                    .metadata(metadata.get(request.key()))
                    .serverSideEncryption(ServerSideEncryption.AES256)
                    .build();
        }

        @Override
        public <T> T getObject(GetObjectRequest request, ResponseTransformer<GetObjectResponse, T> transformer) {
            try {
                return transformer.transform(
                        GetObjectResponse.builder().build(),
                        AbortableInputStream.create(new java.io.ByteArrayInputStream(objects.get(request.key())))
                );
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        private String onlyKeyStartingWith(String prefix) {
            List<String> keys = objects.keySet().stream().filter(key -> key.startsWith(prefix)).toList();
            assertThat(keys).hasSize(1);
            return keys.get(0);
        }
    }

    private BackupProperties validProperties() {
        BackupProperties properties = new BackupProperties();
        properties.setWorkDir(temporaryDirectory.toString());
        properties.setPartSizeMb(10);
        properties.setEncryptionKeyBase64(encodedKey());
        properties.setDumpTimeout(Duration.ofMinutes(5));
        properties.setUploadTimeout(Duration.ofMinutes(5));
        properties.setRestoreDrillRto(Duration.ofMinutes(10));
        properties.setMaxStderrBytes(4096);
        properties.getMysql().setHost("mysql");
        properties.getMysql().setPort(3306);
        properties.getMysql().setDumpBinary("mysqldump");
        properties.getMysql().setDb("otziv");
        properties.getMysql().setUser("backup");
        properties.getMysql().setPassword("secret");
        // MySQL-only by default here; the Keycloak tests opt in explicitly.
        properties.getKeycloak().setEnabled(false);
        return properties;
    }

    private BackupProperties validPropertiesWithKeycloak() {
        BackupProperties properties = validProperties();
        BackupProperties.Keycloak keycloak = properties.getKeycloak();
        keycloak.setEnabled(true);
        keycloak.setDb("keycloak");
        keycloak.setUser("keycloak");
        keycloak.setPassword(credentialPlaceholder());
        return properties;
    }

    private static String credentialPlaceholder() {
        return "kc-" + "local-test-value";
    }

    private DatabaseBackupService.VerifiedBackup verifiedBackup(
            Instant verifiedAt,
            String objectKey,
            String versionId
    ) {
        return new DatabaseBackupService.VerifiedBackup(
                DatabaseBackupService.DATABASE_MYSQL,
                verifiedAt,
                "independent-backup-bucket",
                objectKey,
                versionId,
                "033ea45728f0ba7ce7552bfc6ce49fff338e1269f45c72eded39cb3dc0371087",
                1234L,
                "OTZIVDB2_AES_256_GCM",
                true,
                "AES256",
                true,
                ObjectLockMode.GOVERNANCE,
                Instant.parse("2026-09-02T08:00:00Z"),
                Duration.ofSeconds(12)
        );
    }

    private String encodedKey() {
        byte[] key = new byte[DatabaseBackupService.AES_KEY_BYTES];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) (index + 1);
        }
        return Base64.getEncoder().encodeToString(key);
    }

    private byte[] decrypt(byte[] envelope, byte[] key) throws Exception {
        if (envelope.length < DatabaseBackupService.ENCRYPTED_FILE_HEADER_BYTES) {
            throw new IllegalArgumentException("Envelope length is shorter than its header");
        }
        byte[] header = java.util.Arrays.copyOfRange(
                envelope,
                0,
                DatabaseBackupService.ENCRYPTED_FILE_HEADER_BYTES
        );
        ByteBuffer headerBuffer = ByteBuffer.wrap(header);
        byte[] magic = new byte[DatabaseBackupService.ENCRYPTED_FILE_MAGIC.length];
        headerBuffer.get(magic);
        if (!java.util.Arrays.equals(magic, DatabaseBackupService.ENCRYPTED_FILE_MAGIC)) {
            throw new IllegalArgumentException("Envelope magic is invalid");
        }
        int chunkSize = headerBuffer.getInt();
        long plaintextLength = headerBuffer.getLong();
        byte[] noncePrefix = new byte[DatabaseBackupService.NONCE_PREFIX_BYTES];
        headerBuffer.get(noncePrefix);
        long chunkCount = ((plaintextLength - 1) / chunkSize) + 1;
        long expectedLength = DatabaseBackupService.ENCRYPTED_FILE_HEADER_BYTES
                + plaintextLength
                + (chunkCount * DatabaseBackupService.GCM_TAG_BYTES);
        if (envelope.length != expectedLength) {
            throw new IllegalArgumentException("Envelope length does not match its authenticated header");
        }

        int offset = DatabaseBackupService.ENCRYPTED_FILE_HEADER_BYTES;
        long remaining = plaintextLength;
        ByteArrayOutputStream plaintext = new ByteArrayOutputStream((int) plaintextLength);
        for (long chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int plaintextBytes = (int) Math.min((long) chunkSize, remaining);
            int encryptedBytes = plaintextBytes + DatabaseBackupService.GCM_TAG_BYTES;
            byte[] ciphertextAndTag = java.util.Arrays.copyOfRange(
                    envelope,
                    offset,
                    offset + encryptedBytes
            );
            byte[] nonce = ByteBuffer.allocate(DatabaseBackupService.GCM_IV_BYTES)
                    .put(noncePrefix)
                    .putInt((int) chunkIndex)
                    .array();
            byte[] aad = ByteBuffer.allocate(header.length + Integer.BYTES + Integer.BYTES)
                    .put(header)
                    .putInt((int) chunkIndex)
                    .putInt(plaintextBytes)
                    .array();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(DatabaseBackupService.GCM_TAG_BITS, nonce)
            );
            cipher.updateAAD(aad);
            plaintext.write(cipher.doFinal(ciphertextAndTag));
            offset += encryptedBytes;
            remaining -= plaintextBytes;
        }
        return plaintext.toByteArray();
    }
}

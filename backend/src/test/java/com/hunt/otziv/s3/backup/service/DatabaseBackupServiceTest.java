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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRetentionResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
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

    private DatabaseBackupService service(BackupProperties properties) {
        BackupS3Properties s3 = new BackupS3Properties();
        s3.setBucket("backup-bucket");
        s3.setProjectId("otziv-prod");
        return new DatabaseBackupService(
                properties,
                s3,
                mock(S3Client.class),
                mock(EmailService.class),
                new ObjectMapper()
        );
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
        return properties;
    }

    private DatabaseBackupService.VerifiedBackup verifiedBackup(
            Instant verifiedAt,
            String objectKey,
            String versionId
    ) {
        return new DatabaseBackupService.VerifiedBackup(
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

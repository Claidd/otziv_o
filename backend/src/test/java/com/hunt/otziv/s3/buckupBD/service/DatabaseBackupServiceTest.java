package com.hunt.otziv.s3.buckupBD.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.email.service.EmailService;
import com.hunt.otziv.s3.buckupBD.config.BackupProperties;
import com.hunt.otziv.s3.buckupBD.config.BackupS3Properties;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
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
    void headVerificationRequiresHashSizeAndServerSideEncryption() {
        HeadObjectResponse valid = HeadObjectResponse.builder()
                .contentLength(42L)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .metadata(Map.of("sha256", "abc123"))
                .build();

        DatabaseBackupService.verifyHeadResponse(valid, 42L, "abc123", null, null);

        HeadObjectResponse missingEncryption = valid.toBuilder().serverSideEncryption((String) null).build();
        assertThatThrownBy(() -> DatabaseBackupService.verifyHeadResponse(
                missingEncryption,
                42L,
                "abc123",
                null,
                null
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("server-side encryption");

        HeadObjectResponse wrongHash = valid.toBuilder().metadata(Map.of("sha256", "different")).build();
        assertThatThrownBy(() -> DatabaseBackupService.verifyHeadResponse(
                wrongHash,
                42L,
                "abc123",
                null,
                null
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("SHA-256");
    }

    @Test
    void headVerificationFailsClosedWhenObjectLockWasNotApplied() {
        Instant expectedRetention = Instant.parse("2030-01-01T00:00:00Z");
        HeadObjectResponse missingRetention = HeadObjectResponse.builder()
                .contentLength(42L)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .metadata(Map.of("sha256", "abc123"))
                .objectLockMode(ObjectLockMode.GOVERNANCE)
                .build();

        assertThatThrownBy(() -> DatabaseBackupService.verifyHeadResponse(
                missingRetention,
                42L,
                "abc123",
                ObjectLockMode.GOVERNANCE,
                expectedRetention
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("retention");
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
                        "033ea45728f0ba7ce7552bfc6ce49fff338e1269f45c72eded39cb3dc0371087",
                        1234L,
                        ObjectLockMode.GOVERNANCE,
                        Instant.parse("2026-09-02T08:00:00Z"),
                        Duration.ofSeconds(12)
                ),
                new DatabaseBackupService.BackupCleanupResult(true, true, true, true, true)
        );

        String line = Files.readString(evidenceFile, StandardCharsets.UTF_8).trim();
        var json = new ObjectMapper().readTree(line);
        assertThat(json.path("schema").asText()).isEqualTo("otziv-backup-evidence-v1");
        assertThat(json.path("sourceCommit").asText()).isEqualTo("abc123def456");
        assertThat(json.path("restoreDrillRtoSeconds").asLong()).isEqualTo(91L);
        assertThat(json.path("verification").path("download").asBoolean()).isTrue();
        assertThat(json.path("temporaryFileCleanup").path("plaintextSqlDeleted").asBoolean()).isTrue();
        assertThat(json.path("temporaryFileCleanup").path("plaintextGzipDeleted").asBoolean()).isTrue();
        assertThat(json.path("temporaryFileCleanup").path("verificationDownloadDeleted").asBoolean()).isTrue();
        assertThat(json.path("temporaryFileCleanup").path("encryptedTempDeleted").asBoolean()).isTrue();
        assertThat(json.path("temporaryFileCleanup").path("encryptedPartsDeleted").asBoolean()).isTrue();
        assertThat(line).doesNotContain("backup-secret", "secret", encodedKey());
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

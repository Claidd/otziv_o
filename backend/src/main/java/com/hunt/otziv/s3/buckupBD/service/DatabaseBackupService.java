package com.hunt.otziv.s3.buckupBD.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.email.service.EmailService;
import com.hunt.otziv.s3.buckupBD.config.BackupProperties;
import com.hunt.otziv.s3.buckupBD.config.BackupS3Properties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

@Slf4j
@Service
@ConditionalOnProperty(name = "backup.enabled", havingValue = "true")
public class DatabaseBackupService {

    static final byte[] ENCRYPTED_FILE_MAGIC = "OTZIVDB2".getBytes(StandardCharsets.US_ASCII);
    static final int AES_KEY_BYTES = 32;
    static final int GCM_IV_BYTES = 12;
    static final int GCM_TAG_BITS = 128;
    static final int GCM_TAG_BYTES = GCM_TAG_BITS / Byte.SIZE;
    static final int NONCE_PREFIX_BYTES = 8;
    static final int ENCRYPTED_FILE_HEADER_BYTES = ENCRYPTED_FILE_MAGIC.length
            + Integer.BYTES
            + Long.BYTES
            + NONCE_PREFIX_BYTES;
    static final int DEFAULT_ENCRYPTION_CHUNK_BYTES = 4 * 1024 * 1024;

    private static final int MIN_ENCRYPTION_CHUNK_BYTES = 64 * 1024;
    private static final int MAX_ENCRYPTION_CHUNK_BYTES = 64 * 1024 * 1024;
    private static final long MAX_ENCRYPTION_CHUNKS = 1L << Integer.SIZE;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");
    private static final Pattern SAFE_PROJECT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SAFE_EVIDENCE_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SAFE_SOURCE_COMMIT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final String BACKUP_FORMAT = "otzivdb2-chunked-aes-256-gcm";
    private static final Duration MAX_OPERATION_TIMEOUT = Duration.ofHours(24);
    private static final Duration MAX_RESTORE_DRILL_RTO = Duration.ofDays(7);
    private static final int MAX_PART_SIZE_MB = 1024;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final BackupProperties backupProps;
    private final BackupS3Properties backupS3Props;
    private final S3Client backupS3Client;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;

    public DatabaseBackupService(
            BackupProperties backupProps,
            BackupS3Properties backupS3Props,
            @Qualifier("backupS3Client") S3Client backupS3Client,
            EmailService emailService,
            ObjectMapper objectMapper
    ) {
        this.backupProps = backupProps;
        this.backupS3Props = backupS3Props;
        this.backupS3Client = backupS3Client;
        this.emailService = emailService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void validateEnabledConfigurationAtStartup() throws IOException {
        BackupRunConfiguration configuration = validateAndPrepareConfiguration();
        Arrays.fill(configuration.encryptionKey(), (byte) 0);
    }

    public void runDailyBackup() throws Exception {
        BackupRunConfiguration configuration = validateAndPrepareConfiguration();
        Instant startedAt = Instant.now();
        String timestamp = LocalDateTime.now().format(TS);
        String runId = timestamp + "_" + UUID.randomUUID().toString().replace("-", "");
        Path sqlFile = configuration.workDir().resolve("backup_" + runId + ".sql");
        Path gzFile = configuration.workDir().resolve("backup_" + runId + ".sql.gz");
        Path encryptedFile = configuration.workDir().resolve("backup_" + runId + ".sql.gz.enc");
        List<Path> parts = new ArrayList<>();
        Exception runFailure = null;

        try {
            dumpViaTcp(sqlFile, configuration);
            gzip(sqlFile, gzFile);
            encryptAesGcm(gzFile, encryptedFile, configuration.encryptionKey());

            // Never upload or report success while recoverable plaintext remains locally.
            deleteRequired(sqlFile);
            deleteRequired(gzFile);

            VerifiedBackup verifiedBackup = uploadAndVerify(
                    encryptedFile,
                    runId,
                    configuration,
                    startedAt
            );

            if (backupProps.getMail().isEnabled()) {
                parts = splitFile(encryptedFile, configuration.partSizeBytes());
                sendEncryptedPartsByEmail(parts, timestamp);
            }

            // Successful evidence is written only after every run-owned local artifact is gone.
            deleteAllRequired(parts);
            deleteRequired(encryptedFile);
            BackupCleanupResult cleanup = new BackupCleanupResult(true, true, true, true, true);
            writeEvidence(configuration.evidenceFile(), verifiedBackup, cleanup);
            log.info(
                    "Database backup completed, independently verified, and temporary files removed: objectId={}, sha256={}",
                    runId,
                    fingerprint(verifiedBackup.sha256())
            );
        } catch (Exception exception) {
            runFailure = exception;
            throw exception;
        } finally {
            Arrays.fill(configuration.encryptionKey(), (byte) 0);
            try {
                deleteAllRequired(concatPaths(sqlFile, gzFile, encryptedFile, parts));
            } catch (IOException cleanupFailure) {
                if (runFailure != null) {
                    runFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    BackupRunConfiguration validateAndPrepareConfiguration() throws IOException {
        BackupProperties.Mysql mysql = backupProps.getMysql();
        if (mysql == null) {
            throw new IllegalStateException("backup.mysql is required");
        }
        requireNonBlank(mysql.getDb(), "backup.mysql.db");
        requireNonBlank(mysql.getUser(), "backup.mysql.user");
        requireNonBlank(mysql.getPassword(), "backup.mysql.password");
        requireNonBlank(firstNonBlank(mysql.getHost(), mysql.getContainer()), "backup.mysql.host");
        requireNonBlank(mysql.getDumpBinary(), "backup.mysql.dump-binary");
        if (mysql.getPort() < 1 || mysql.getPort() > 65_535) {
            throw new IllegalStateException("backup.mysql.port must be between 1 and 65535");
        }

        String bucket = requireNonBlank(backupS3Props.getBucket(), "backup.s3.bucket");
        String projectId = requireNonBlank(backupS3Props.getProjectId(), "backup.s3.project-id");
        if (!SAFE_PROJECT_ID.matcher(projectId).matches()) {
            throw new IllegalStateException("backup.s3.project-id contains unsafe characters");
        }
        if (bucket.length() > 255) {
            throw new IllegalStateException("backup.s3.bucket is too long");
        }

        Duration dumpTimeout = requireTimeout(backupProps.getDumpTimeout(), "backup.dump-timeout");
        Duration uploadTimeout = requireTimeout(backupProps.getUploadTimeout(), "backup.upload-timeout");
        if (backupProps.getMaxStderrBytes() < 1024 || backupProps.getMaxStderrBytes() > 1024 * 1024) {
            throw new IllegalStateException("backup.max-stderr-bytes must be between 1024 and 1048576");
        }
        if (backupProps.getPartSizeMb() < 1 || backupProps.getPartSizeMb() > MAX_PART_SIZE_MB) {
            throw new IllegalStateException("backup.part-size-mb must be between 1 and " + MAX_PART_SIZE_MB);
        }
        String evidenceFileName = requireNonBlank(backupProps.getEvidenceFileName(), "backup.evidence-file-name");
        if (!SAFE_EVIDENCE_FILE.matcher(evidenceFileName).matches()) {
            throw new IllegalStateException("backup.evidence-file-name must be a simple file name");
        }
        String sourceCommit = trimToNull(backupProps.getSourceCommit());
        if (sourceCommit != null && !SAFE_SOURCE_COMMIT.matcher(sourceCommit).matches()) {
            throw new IllegalStateException("backup.source-commit contains unsafe characters or is too long");
        }
        requireDuration(
                backupProps.getRestoreDrillRto(),
                "backup.restore-drill-rto",
                MAX_RESTORE_DRILL_RTO
        );

        validateMailConfiguration();

        String configuredWorkDir = requireNonBlank(backupProps.getWorkDir(), "backup.work-dir");
        Path workDir = Paths.get(configuredWorkDir).toAbsolutePath().normalize();
        Files.createDirectories(workDir);
        Path realWorkDir = workDir.toRealPath();
        if (!Files.isDirectory(realWorkDir) || !Files.isWritable(realWorkDir)) {
            throw new IllegalStateException("backup.work-dir must be a writable directory");
        }
        Path evidenceFile = realWorkDir.resolve(evidenceFileName).normalize();
        if (!evidenceFile.getParent().equals(realWorkDir)) {
            throw new IllegalStateException("backup.evidence-file-name must remain inside backup.work-dir");
        }
        if (Files.exists(evidenceFile) && (!Files.isRegularFile(evidenceFile) || Files.isSymbolicLink(evidenceFile))) {
            throw new IllegalStateException("backup evidence path must be a regular, non-symbolic file");
        }
        if (Files.exists(evidenceFile) && !Files.isWritable(evidenceFile)) {
            throw new IllegalStateException("backup evidence file must be writable");
        }

        long partSizeBytes = Math.multiplyExact((long) backupProps.getPartSizeMb(), 1024L * 1024L);
        byte[] encryptionKey = decodeEncryptionKey(backupProps.getEncryptionKeyBase64());
        return new BackupRunConfiguration(
                realWorkDir,
                encryptionKey,
                dumpTimeout,
                uploadTimeout,
                partSizeBytes,
                backupProps.getMaxStderrBytes(),
                evidenceFile
        );
    }

    private void validateMailConfiguration() {
        BackupProperties.Mail mail = backupProps.getMail();
        if (mail == null || !mail.isEnabled()) {
            return;
        }
        requireNonBlank(mail.getTo(), "backup.mail.to");
        requireNonBlank(mail.getFrom(), "backup.mail.from");
        requireNonBlank(mail.getSubject(), "backup.mail.subject");
        requireNonBlank(mail.getBody(), "backup.mail.body");
    }

    private void dumpViaTcp(Path outSql, BackupRunConfiguration configuration) throws Exception {
        BackupProperties.Mysql mysql = backupProps.getMysql();
        String host = firstNonBlank(mysql.getHost(), mysql.getContainer());
        String password = mysql.getPassword();

        List<String> command = List.of(
                mysql.getDumpBinary(),
                "-h", host,
                "-P", String.valueOf(mysql.getPort()),
                "-u" + mysql.getUser(),
                "--single-transaction",
                "--quick",
                "--routines",
                "--events",
                "--triggers",
                "--hex-blob",
                "--no-tablespaces",
                "--set-gtid-purged=OFF",
                mysql.getDb()
        );

        createEmptyPrivateFile(outSql);
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .redirectOutput(outSql.toFile());
        processBuilder.environment().put("MYSQL_PWD", password);

        log.info("Starting database dump: host={}, port={}, database={}", host, mysql.getPort(), mysql.getDb());
        Process process = processBuilder.start();
        processBuilder.environment().remove("MYSQL_PWD");

        BoundedOutputStream stderr = new BoundedOutputStream(configuration.maxStderrBytes());
        AtomicReference<IOException> stderrReadFailure = new AtomicReference<>();
        Thread stderrThread = Thread.ofPlatform()
                .daemon(true)
                .name("mysqldump-stderr")
                .start(() -> {
                    try (InputStream input = process.getErrorStream()) {
                        input.transferTo(stderr);
                    } catch (IOException exception) {
                        stderrReadFailure.set(exception);
                    }
                });

        boolean finished;
        try {
            finished = process.waitFor(configuration.dumpTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            terminateProcess(process);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("mysqldump was interrupted", interrupted);
        }

        if (!finished) {
            terminateProcess(process);
        }
        joinReader(stderrThread, process);

        String safeStderr = redact(stderr.asUtf8(), password);
        if (!safeStderr.isBlank()) {
            log.warn("mysqldump stderr{}: {}", stderr.wasTruncated() ? " (truncated)" : "", safeStderr);
        }
        if (stderrReadFailure.get() != null && finished) {
            throw new IllegalStateException("Could not read mysqldump stderr", stderrReadFailure.get());
        }
        if (!finished) {
            throw new IllegalStateException("mysqldump timed out after " + configuration.dumpTimeout());
        }

        int exitCode = process.exitValue();
        long size = Files.size(outSql);
        if (exitCode != 0 || size == 0) {
            throw new IllegalStateException("mysqldump failed, exitCode=" + exitCode + ", sqlSize=" + size);
        }
    }

    private void terminateProcess(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private void joinReader(Thread reader, Process process) throws InterruptedException {
        reader.join(5_000);
        if (reader.isAlive()) {
            try {
                process.getErrorStream().close();
            } catch (IOException ignored) {
                // The process may already have closed the pipe.
            }
            reader.join(1_000);
        }
        if (reader.isAlive()) {
            reader.interrupt();
            throw new IllegalStateException("mysqldump stderr reader did not terminate");
        }
    }

    private void gzip(Path input, Path output) throws IOException {
        log.info("Compressing database dump");
        try (InputStream source = Files.newInputStream(input);
             OutputStream destination = newPrivateOutputStream(output);
             GZIPOutputStream gzip = new GZIPOutputStream(destination)) {
            source.transferTo(gzip);
        }
        if (Files.size(output) == 0) {
            throw new IllegalStateException("Compressed database dump is empty");
        }
    }

    void encryptAesGcm(Path input, Path output, byte[] encryptionKey) throws IOException {
        encryptAesGcm(input, output, encryptionKey, DEFAULT_ENCRYPTION_CHUNK_BYTES);
    }

    void encryptAesGcm(Path input, Path output, byte[] encryptionKey, int chunkSizeBytes) throws IOException {
        if (encryptionKey == null || encryptionKey.length != AES_KEY_BYTES) {
            throw new IllegalArgumentException("AES-256 encryption requires exactly 32 key bytes");
        }
        if (chunkSizeBytes < MIN_ENCRYPTION_CHUNK_BYTES || chunkSizeBytes > MAX_ENCRYPTION_CHUNK_BYTES) {
            throw new IllegalArgumentException("Encryption chunk size must be between 65536 and 67108864 bytes");
        }
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Encrypted output must differ from its input");
        }

        long plaintextLength = Files.size(input);
        if (plaintextLength <= 0) {
            throw new IllegalStateException("Compressed database dump is empty");
        }
        long chunkCount = ((plaintextLength - 1) / chunkSizeBytes) + 1;
        if (chunkCount > MAX_ENCRYPTION_CHUNKS) {
            throw new IllegalStateException("Database dump requires too many AES-GCM chunks");
        }

        byte[] noncePrefix = new byte[NONCE_PREFIX_BYTES];
        SECURE_RANDOM.nextBytes(noncePrefix);
        byte[] header = ByteBuffer.allocate(ENCRYPTED_FILE_HEADER_BYTES)
                .put(ENCRYPTED_FILE_MAGIC)
                .putInt(chunkSizeBytes)
                .putLong(plaintextLength)
                .put(noncePrefix)
                .array();
        byte[] plaintext = new byte[chunkSizeBytes];
        boolean outputCreated = false;

        try {
            createEmptyPrivateFile(output);
            outputCreated = true;
            try (InputStream source = Files.newInputStream(input);
                 OutputStream destination = Files.newOutputStream(
                         output,
                         StandardOpenOption.WRITE,
                         StandardOpenOption.TRUNCATE_EXISTING
                 )) {
                destination.write(header);
                long remaining = plaintextLength;
                for (long chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                    int plaintextBytes = (int) Math.min((long) chunkSizeBytes, remaining);
                    readExactly(source, plaintext, plaintextBytes);

                    byte[] nonce = ByteBuffer.allocate(GCM_IV_BYTES)
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
                            Cipher.ENCRYPT_MODE,
                            new SecretKeySpec(encryptionKey, "AES"),
                            new GCMParameterSpec(GCM_TAG_BITS, nonce)
                    );
                    cipher.updateAAD(aad);
                    byte[] ciphertextAndTag = cipher.doFinal(plaintext, 0, plaintextBytes);
                    if (ciphertextAndTag.length != plaintextBytes + GCM_TAG_BYTES) {
                        throw new IllegalStateException("Unexpected AES-GCM chunk size");
                    }
                    destination.write(ciphertextAndTag);
                    remaining -= plaintextBytes;
                }
                if (remaining != 0 || source.read() != -1) {
                    throw new IllegalStateException("Compressed database dump changed while it was encrypted");
                }
            }

            long expectedEnvelopeBytes = Math.addExact(
                    ENCRYPTED_FILE_HEADER_BYTES,
                    Math.addExact(plaintextLength, Math.multiplyExact(chunkCount, (long) GCM_TAG_BYTES))
            );
            if (Files.size(output) != expectedEnvelopeBytes) {
                throw new IllegalStateException("Encrypted backup envelope has an unexpected size");
            }
        } catch (GeneralSecurityException exception) {
            IllegalStateException failure = new IllegalStateException("AES-GCM encryption is unavailable", exception);
            if (outputCreated) {
                cleanupRequiredOrSuppress(output, failure);
            }
            throw failure;
        } catch (IOException | RuntimeException exception) {
            if (outputCreated) {
                cleanupRequiredOrSuppress(output, exception);
            }
            throw exception;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static void readExactly(InputStream source, byte[] destination, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int read = source.read(destination, offset, length - offset);
            if (read < 0) {
                throw new IOException("Compressed database dump ended while it was encrypted");
            }
            offset += read;
        }
    }

    VerifiedBackup uploadAndVerify(
            Path file,
            String runId,
            BackupRunConfiguration configuration,
            Instant startedAt
    ) throws IOException {
        String bucket = backupS3Props.getBucket().trim();
        String key = "backup/" + backupS3Props.getProjectId().trim() + "/backup_" + runId + ".sql.gz.enc";
        long expectedBytes = Files.size(file);
        String expectedSha256 = sha256(file);
        Instant retentionUntil = backupS3Props.isObjectLockEnabled()
                ? Instant.now().plus(Duration.ofDays(backupS3Props.getRetentionDays()))
                : null;
        ObjectLockMode objectLockMode = backupS3Props.isObjectLockEnabled()
                ? ObjectLockMode.fromValue(backupS3Props.getObjectLockMode().trim().toUpperCase(Locale.ROOT))
                : null;

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("backup-format", BACKUP_FORMAT);
        metadata.put("content-encoding-before-encryption", "gzip");
        metadata.put("sha256", expectedSha256);
        metadata.put("retention-mode", objectLockMode == null ? "none" : objectLockMode.toString());
        if (retentionUntil != null) {
            metadata.put("retention-until", retentionUntil.toString());
        }

        PutObjectRequest.Builder putBuilder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("application/octet-stream")
                .contentLength(expectedBytes)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .metadata(metadata)
                .overrideConfiguration(builder -> builder
                        .apiCallTimeout(configuration.uploadTimeout())
                        .apiCallAttemptTimeout(configuration.uploadTimeout()));
        if (objectLockMode != null) {
            putBuilder.objectLockMode(objectLockMode).objectLockRetainUntilDate(retentionUntil);
        }

        backupS3Client.putObject(putBuilder.build(), RequestBody.fromFile(file));

        HeadObjectResponse head = backupS3Client.headObject(HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .overrideConfiguration(builder -> builder
                        .apiCallTimeout(configuration.uploadTimeout())
                        .apiCallAttemptTimeout(configuration.uploadTimeout()))
                .build());
        verifyHeadResponse(head, expectedBytes, expectedSha256, objectLockMode, retentionUntil);

        Path downloaded = configuration.workDir().resolve(".verify_" + runId + ".sql.gz.enc");
        Exception verificationFailure = null;
        try {
            backupS3Client.getObject(
                    GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .overrideConfiguration(builder -> builder
                                    .apiCallTimeout(configuration.uploadTimeout())
                                    .apiCallAttemptTimeout(configuration.uploadTimeout()))
                            .build(),
                    ResponseTransformer.toFile(downloaded)
            );
            long downloadedBytes = Files.size(downloaded);
            String downloadedSha256 = sha256(downloaded);
            if (downloadedBytes != expectedBytes || !MessageDigest.isEqual(
                    expectedSha256.getBytes(StandardCharsets.US_ASCII),
                    downloadedSha256.getBytes(StandardCharsets.US_ASCII)
            )) {
                throw new IllegalStateException("Downloaded backup checksum or size does not match the uploaded object");
            }
        } catch (IOException | RuntimeException exception) {
            verificationFailure = exception;
            throw exception;
        } finally {
            try {
                deleteRequired(downloaded);
            } catch (IOException cleanupFailure) {
                if (verificationFailure != null) {
                    verificationFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }

        Instant verifiedAt = Instant.now();
        return new VerifiedBackup(
                verifiedAt,
                bucket,
                key,
                expectedSha256,
                expectedBytes,
                objectLockMode,
                retentionUntil,
                Duration.between(startedAt, verifiedAt)
        );
    }

    static void verifyHeadResponse(
            HeadObjectResponse head,
            long expectedBytes,
            String expectedSha256,
            ObjectLockMode expectedObjectLockMode,
            Instant expectedRetentionUntil
    ) {
        if (head == null) {
            throw new IllegalStateException("Backup HEAD verification returned no response");
        }
        if (head.contentLength() == null || head.contentLength() != expectedBytes) {
            throw new IllegalStateException("Backup HEAD verification returned an unexpected content length");
        }
        String storedSha256 = head.metadata() == null ? null : head.metadata().get("sha256");
        if (storedSha256 == null || !MessageDigest.isEqual(
                expectedSha256.getBytes(StandardCharsets.US_ASCII),
                storedSha256.getBytes(StandardCharsets.US_ASCII)
        )) {
            throw new IllegalStateException("Backup HEAD verification returned an unexpected SHA-256 metadata value");
        }
        if (head.serverSideEncryption() != ServerSideEncryption.AES256) {
            throw new IllegalStateException("Backup destination did not confirm AES-256 server-side encryption");
        }
        if (expectedObjectLockMode != null) {
            if (head.objectLockMode() != expectedObjectLockMode) {
                throw new IllegalStateException("Backup destination did not confirm the requested Object Lock mode");
            }
            Instant actualRetention = head.objectLockRetainUntilDate();
            if (actualRetention == null || actualRetention.isBefore(expectedRetentionUntil.minusSeconds(1))) {
                throw new IllegalStateException("Backup destination did not confirm the requested Object Lock retention");
            }
        }
    }

    void writeEvidence(
            Path evidenceFile,
            VerifiedBackup backup,
            BackupCleanupResult cleanup
    ) throws IOException {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schema", "otziv-backup-evidence-v1");
        evidence.put("timestampUtc", backup.verifiedAt().toString());
        evidence.put("bucket", backup.bucket());
        evidence.put("objectKey", backup.key());
        evidence.put("sha256", backup.sha256());
        evidence.put("bytes", backup.bytes());
        evidence.put("format", BACKUP_FORMAT);
        evidence.put("elapsedMillis", backup.elapsed().toMillis());
        String sourceCommit = trimToNull(backupProps.getSourceCommit());
        if (sourceCommit != null) {
            evidence.put("sourceCommit", sourceCommit);
        }
        evidence.put("restoreDrillRtoSeconds", backupProps.getRestoreDrillRto().toSeconds());

        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("head", true);
        verification.put("download", true);
        verification.put("sha256", true);
        verification.put("serverSideEncryption", "AES256");
        verification.put("objectLock", backup.objectLockMode() != null);
        evidence.put("verification", verification);

        Map<String, Object> cleanupEvidence = new LinkedHashMap<>();
        cleanupEvidence.put("plaintextSqlDeleted", cleanup.plaintextSqlDeleted());
        cleanupEvidence.put("plaintextGzipDeleted", cleanup.plaintextGzipDeleted());
        cleanupEvidence.put("verificationDownloadDeleted", cleanup.verificationDownloadDeleted());
        cleanupEvidence.put("encryptedTempDeleted", cleanup.encryptedTempDeleted());
        cleanupEvidence.put("encryptedPartsDeleted", cleanup.encryptedPartsDeleted());
        evidence.put("temporaryFileCleanup", cleanupEvidence);

        if (backup.objectLockMode() != null) {
            Map<String, Object> retention = new LinkedHashMap<>();
            retention.put("mode", backup.objectLockMode().toString());
            retention.put("retainUntilUtc", backup.retentionUntil().toString());
            evidence.put("retention", retention);
        }

        byte[] json = objectMapper.writeValueAsBytes(evidence);
        byte[] line = Arrays.copyOf(json, json.length + 1);
        line[line.length - 1] = (byte) '\n';
        if (!Files.exists(evidenceFile)) {
            try {
                createEmptyPrivateFile(evidenceFile);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another process created the evidence file between the check and create.
            }
        }
        if (Files.isSymbolicLink(evidenceFile) || !Files.isRegularFile(evidenceFile)) {
            throw new IllegalStateException("Backup evidence path changed and is no longer a regular file");
        }
        try (FileChannel channel = FileChannel.open(evidenceFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             var ignored = channel.lock()) {
            ByteBuffer buffer = ByteBuffer.wrap(line);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } finally {
            Arrays.fill(json, (byte) 0);
            Arrays.fill(line, (byte) 0);
        }
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    List<Path> splitFile(Path file, long partSizeBytes) throws IOException {
        if (partSizeBytes <= 0) {
            throw new IllegalArgumentException("partSizeBytes must be positive");
        }

        List<Path> parts = new ArrayList<>();
        byte[] buffer = new byte[(int) Math.min(1024L * 1024L, partSizeBytes)];
        Path currentPart = null;
        boolean currentPartCreated = false;
        try (InputStream input = Files.newInputStream(file)) {
            int partIndex = 0;
            boolean endOfFile = false;
            while (!endOfFile) {
                Path part = nextPartPath(file, partIndex++);
                currentPart = part;
                currentPartCreated = false;
                long written = 0;
                createEmptyPrivateFile(part);
                currentPartCreated = true;
                try (OutputStream output = Files.newOutputStream(
                        part,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                )) {
                    while (written < partSizeBytes) {
                        int maximumRead = (int) Math.min(buffer.length, partSizeBytes - written);
                        int read = input.read(buffer, 0, maximumRead);
                        if (read < 0) {
                            endOfFile = true;
                            break;
                        }
                        output.write(buffer, 0, read);
                        written += read;
                    }
                }
                if (written == 0) {
                    deleteRequired(part);
                } else {
                    parts.add(part);
                }
                currentPart = null;
                currentPartCreated = false;
            }
            return parts;
        } catch (IOException | RuntimeException exception) {
            if (currentPartCreated) {
                cleanupRequiredOrSuppress(currentPart, exception);
            }
            parts.forEach(part -> cleanupRequiredOrSuppress(part, exception));
            throw exception;
        }
    }

    private void sendEncryptedPartsByEmail(List<Path> parts, String timestamp) {
        BackupProperties.Mail mail = backupProps.getMail();
        for (int index = 0; index < parts.size(); index++) {
            Path part = parts.get(index);
            String subject = mail.getSubject() + " [" + timestamp + "] encrypted part "
                    + (index + 1) + "/" + parts.size();
            String body = mail.getBody() + "\n"
                    + "Backup timestamp: " + timestamp + "\n"
                    + "Encrypted format: OTZIVDB2 (chunked AES-256-GCM after gzip)\n"
                    + "Part: " + (index + 1) + "/" + parts.size();

            emailService.sendWithAttachment(mail.getTo(), mail.getFrom(), subject, body, part.toFile());
            log.info("Encrypted backup part sent: part={}/{}", index + 1, parts.size());
        }
    }

    static byte[] decodeEncryptionKey(String encodedKey) {
        String value = requireNonBlank(encodedKey, "backup.encryption-key-base64");
        byte[] key;
        try {
            key = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("backup.encryption-key-base64 must be valid Base64", exception);
        }
        if (key.length != AES_KEY_BYTES) {
            Arrays.fill(key, (byte) 0);
            throw new IllegalStateException("backup.encryption-key-base64 must decode to exactly 32 bytes");
        }
        return key;
    }

    private static Duration requireTimeout(Duration timeout, String propertyName) {
        return requireDuration(timeout, propertyName, MAX_OPERATION_TIMEOUT);
    }

    private static Duration requireDuration(Duration duration, String propertyName, Duration maximum) {
        if (duration == null || duration.isZero() || duration.isNegative() || duration.compareTo(maximum) > 0) {
            throw new IllegalStateException(propertyName + " must be positive and no greater than " + maximum);
        }
        return duration;
    }

    private static String requireNonBlank(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " is required");
        }
        return value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String fingerprint(String value) {
        return value == null || value.length() < 12 ? "invalid" : value.substring(0, 12);
    }

    private static String redact(String value, String secret) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String sanitized = value.replace('\u0000', '?').replace('\r', ' ').replace('\n', ' ').trim();
        if (secret != null && !secret.isEmpty()) {
            sanitized = sanitized.replace(secret, "[redacted]");
        }
        return sanitized;
    }

    private static OutputStream newPrivateOutputStream(Path path) throws IOException {
        createEmptyPrivateFile(path);
        return Files.newOutputStream(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void createEmptyPrivateFile(Path path) throws IOException {
        Files.createFile(path);
        try {
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows and some mounted volumes do not expose POSIX permissions.
        }
    }

    private static Path nextPartPath(Path original, int index) {
        return original.resolveSibling(original.getFileName() + ".part" + index);
    }

    static void deleteRequired(Path path) throws IOException {
        if (path == null) {
            return;
        }
        Files.deleteIfExists(path);
        if (Files.exists(path)) {
            throw new IOException("Temporary backup file still exists after deletion: " + path.getFileName());
        }
    }

    private static void deleteAllRequired(List<Path> paths) throws IOException {
        IOException firstFailure = null;
        for (Path path : paths) {
            try {
                deleteRequired(path);
            } catch (IOException exception) {
                if (firstFailure == null) {
                    firstFailure = exception;
                } else {
                    firstFailure.addSuppressed(exception);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private static List<Path> concatPaths(Path sqlFile, Path gzFile, Path encryptedFile, List<Path> parts) {
        List<Path> paths = new ArrayList<>(parts.size() + 3);
        paths.add(sqlFile);
        paths.add(gzFile);
        paths.add(encryptedFile);
        paths.addAll(parts);
        return paths;
    }

    private static void cleanupRequiredOrSuppress(Path path, Throwable failure) {
        try {
            deleteRequired(path);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    record BackupRunConfiguration(
            Path workDir,
            byte[] encryptionKey,
            Duration dumpTimeout,
            Duration uploadTimeout,
            long partSizeBytes,
            int maxStderrBytes,
            Path evidenceFile
    ) {
    }

    record VerifiedBackup(
            Instant verifiedAt,
            String bucket,
            String key,
            String sha256,
            long bytes,
            ObjectLockMode objectLockMode,
            Instant retentionUntil,
            Duration elapsed
    ) {
    }

    record BackupCleanupResult(
            boolean plaintextSqlDeleted,
            boolean plaintextGzipDeleted,
            boolean verificationDownloadDeleted,
            boolean encryptedTempDeleted,
            boolean encryptedPartsDeleted
    ) {
    }

    static final class BoundedOutputStream extends OutputStream {
        private final int limit;
        private final ByteArrayOutputStream delegate;
        private long discardedBytes;

        BoundedOutputStream(int limit) {
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be positive");
            }
            this.limit = limit;
            this.delegate = new ByteArrayOutputStream(Math.min(limit, 8192));
        }

        @Override
        public void write(int value) {
            if (delegate.size() < limit) {
                delegate.write(value);
            } else {
                discardedBytes++;
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            int remaining = limit - delegate.size();
            int accepted = Math.max(0, Math.min(remaining, length));
            if (accepted > 0) {
                delegate.write(bytes, offset, accepted);
            }
            discardedBytes += length - accepted;
        }

        String asUtf8() {
            return delegate.toString(StandardCharsets.UTF_8);
        }

        boolean wasTruncated() {
            return discardedBytes > 0;
        }
    }
}

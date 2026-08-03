package com.hunt.otziv.s3.buckupBD.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.email.service.EmailService;
import com.hunt.otziv.s3.buckupBD.config.BackupProperties;
import com.hunt.otziv.s3.buckupBD.config.BackupS3Properties;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import software.amazon.awssdk.services.s3.model.GetObjectRetentionRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRetentionResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
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
    private static final Pattern SAFE_RUN_REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final String RUN_ID_PATTERN = "\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{3}_[0-9a-f]{32}";
    private static final Pattern OWNED_PLAINTEXT_TEMP_FILE = Pattern.compile(
            "^backup_" + RUN_ID_PATTERN + "\\.sql(?:\\.gz)?$"
    );
    private static final Pattern OWNED_ENCRYPTED_TEMP_FILE = Pattern.compile(
            "^(?:backup_" + RUN_ID_PATTERN + "\\.sql\\.gz\\.enc(?:\\.part\\d+)?"
                    + "|\\.verify_" + RUN_ID_PATTERN + "\\.sql\\.gz\\.enc)$"
    );
    private static final String BACKUP_FORMAT = "otzivdb2-chunked-aes-256-gcm";
    private static final String CLIENT_SIDE_ENCRYPTION = "OTZIVDB2_AES_256_GCM";
    private static final String NO_SERVER_SIDE_ENCRYPTION_REPORTED = "NONE_REPORTED";
    private static final String BACKUP_EVIDENCE_SCHEMA = "otziv-backup-evidence-v1";
    private static final String EVIDENCE_PHASE_REMOTE_VERIFIED = "remote-verified";
    private static final String EVIDENCE_PHASE_COMPLETED = "completed";
    private static final String LOCAL_RUN_LOCK_FILE = ".database-backup-run.lock";
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
        runBackup("scheduled", null);
    }

    public void runCatchUpBackup() throws Exception {
        runBackup("catch-up", null);
    }

    public void runManualBackup(String requestId) throws Exception {
        runBackup("manual", requireRunRequestId(requestId));
    }

    private void runBackup(String trigger, String requestId) throws Exception {
        BackupRunConfiguration configuration = validateAndPrepareConfiguration();
        try {
            try (LocalBackupRunLock ignored = acquireLocalBackupRunLock(configuration.workDir())) {
                cleanupStaleTemporaryFiles(configuration.workDir(), configuration.evidenceFile());
                runBackupWithLocalLock(configuration, trigger, requestId);
            }
        } finally {
            Arrays.fill(configuration.encryptionKey(), (byte) 0);
        }
    }

    private void runBackupWithLocalLock(
            BackupRunConfiguration configuration,
            String trigger,
            String requestId
    ) throws Exception {
        Instant startedAt = Instant.now();
        String timestamp = LocalDateTime.now().format(TS);
        String runId = timestamp + "_" + UUID.randomUUID().toString().replace("-", "");
        Path sqlFile = configuration.workDir().resolve("backup_" + runId + ".sql");
        Path gzFile = configuration.workDir().resolve("backup_" + runId + ".sql.gz");
        Path encryptedFile = configuration.workDir().resolve("backup_" + runId + ".sql.gz.enc");
        List<Path> parts = new ArrayList<>();
        BackupMailDeliveryResult mailDelivery = BackupMailDeliveryResult.disabled();
        Exception mailFailure = null;
        Exception runFailure = null;

        try {
            dumpViaTcp(sqlFile, configuration);
            gzip(sqlFile, gzFile);
            encryptAesGcm(gzFile, encryptedFile, configuration.encryptionKey());

            // Never upload or report success while recoverable plaintext remains locally.
            overwriteAndDeletePlaintext(sqlFile);
            overwriteAndDeletePlaintext(gzFile);

            VerifiedBackup verifiedBackup = uploadAndVerify(
                    encryptedFile,
                    runId,
                    configuration,
                    startedAt
            );
            writeRemoteVerificationEvidence(
                    configuration.evidenceFile(),
                    verifiedBackup,
                    trigger,
                    requestId,
                    backupProps.getMail().isEnabled()
            );

            if (backupProps.getMail().isEnabled()) {
                try {
                    parts = splitFile(encryptedFile, configuration.partSizeBytes());
                    mailDelivery = BackupMailDeliveryResult.failed(parts.size());
                    sendEncryptedPartsByEmail(parts, timestamp);
                    mailDelivery = BackupMailDeliveryResult.succeeded(parts.size());
                } catch (Exception exception) {
                    mailFailure = exception;
                    mailDelivery = BackupMailDeliveryResult.failed(parts.size());
                }
            }

            // The remote-verified receipt above already prevents duplicate immutable uploads. This
            // completed record additionally attests optional email delivery and checked local cleanup.
            deleteAllRequired(parts);
            deleteRequired(encryptedFile);
            BackupCleanupResult cleanup = new BackupCleanupResult(true, true, true, true, true);
            recordVerifiedBackupAndReportMailFailure(
                    configuration.evidenceFile(),
                    verifiedBackup,
                    cleanup,
                    trigger,
                    requestId,
                    mailDelivery,
                    mailFailure
            );
            log.info(
                    "Database backup completed, independently verified, and temporary files removed: objectId={}, sha256={}",
                    runId,
                    fingerprint(verifiedBackup.sha256())
            );
        } catch (Exception exception) {
            runFailure = exception;
            throw exception;
        } finally {
            try {
                cleanupRunArtifacts(sqlFile, gzFile, encryptedFile, parts);
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

    static void verifyEncryptedEnvelope(Path input, byte[] encryptionKey) throws IOException {
        if (encryptionKey == null || encryptionKey.length != AES_KEY_BYTES) {
            throw new IllegalArgumentException("AES-256 envelope verification requires exactly 32 key bytes");
        }
        long envelopeLength = Files.size(input);
        if (envelopeLength < ENCRYPTED_FILE_HEADER_BYTES) {
            throw new IllegalStateException("Encrypted OTZIVDB2 backup is shorter than its header");
        }

        byte[] header = new byte[ENCRYPTED_FILE_HEADER_BYTES];
        try (InputStream source = Files.newInputStream(input)) {
            readExactly(source, header, header.length);
            ByteBuffer headerBuffer = ByteBuffer.wrap(header);
            byte[] magic = new byte[ENCRYPTED_FILE_MAGIC.length];
            headerBuffer.get(magic);
            if (!Arrays.equals(magic, ENCRYPTED_FILE_MAGIC)) {
                throw new IllegalStateException("Encrypted backup is not an OTZIVDB2 envelope");
            }

            int chunkSizeBytes = headerBuffer.getInt();
            long plaintextLength = headerBuffer.getLong();
            byte[] noncePrefix = new byte[NONCE_PREFIX_BYTES];
            headerBuffer.get(noncePrefix);
            if (chunkSizeBytes < MIN_ENCRYPTION_CHUNK_BYTES || chunkSizeBytes > MAX_ENCRYPTION_CHUNK_BYTES) {
                throw new IllegalStateException("OTZIVDB2 envelope has an unsupported chunk size");
            }
            if (plaintextLength <= 0) {
                throw new IllegalStateException("OTZIVDB2 envelope has an invalid plaintext length");
            }

            long chunkCount = ((plaintextLength - 1) / chunkSizeBytes) + 1;
            if (chunkCount > MAX_ENCRYPTION_CHUNKS) {
                throw new IllegalStateException("OTZIVDB2 envelope contains too many chunks");
            }
            long expectedEnvelopeLength;
            try {
                expectedEnvelopeLength = Math.addExact(
                        ENCRYPTED_FILE_HEADER_BYTES,
                        Math.addExact(plaintextLength, Math.multiplyExact(chunkCount, (long) GCM_TAG_BYTES))
                );
            } catch (ArithmeticException exception) {
                throw new IllegalStateException("OTZIVDB2 envelope length is invalid", exception);
            }
            if (envelopeLength != expectedEnvelopeLength) {
                throw new IllegalStateException("OTZIVDB2 envelope length does not match its authenticated header");
            }

            long remaining = plaintextLength;
            for (long chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                int plaintextBytes = (int) Math.min((long) chunkSizeBytes, remaining);
                byte[] ciphertextAndTag = new byte[plaintextBytes + GCM_TAG_BYTES];
                byte[] decrypted = null;
                try {
                    readExactly(source, ciphertextAndTag, ciphertextAndTag.length);
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
                            Cipher.DECRYPT_MODE,
                            new SecretKeySpec(encryptionKey, "AES"),
                            new GCMParameterSpec(GCM_TAG_BITS, nonce)
                    );
                    cipher.updateAAD(aad);
                    decrypted = cipher.doFinal(ciphertextAndTag);
                    if (decrypted.length != plaintextBytes) {
                        throw new IllegalStateException("OTZIVDB2 envelope decrypted to an unexpected chunk size");
                    }
                } catch (GeneralSecurityException exception) {
                    throw new IllegalStateException("OTZIVDB2 client-side envelope authentication failed", exception);
                } finally {
                    Arrays.fill(ciphertextAndTag, (byte) 0);
                    if (decrypted != null) {
                        Arrays.fill(decrypted, (byte) 0);
                    }
                }
                remaining -= plaintextBytes;
            }
            if (remaining != 0 || source.read() != -1) {
                throw new IllegalStateException("OTZIVDB2 envelope did not end at its authenticated boundary");
            }
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
                .metadata(metadata)
                .overrideConfiguration(builder -> builder
                        .apiCallTimeout(configuration.uploadTimeout())
                        .apiCallAttemptTimeout(configuration.uploadTimeout()));
        if (backupS3Props.isRequireServerSideEncryption()) {
            putBuilder.serverSideEncryption(ServerSideEncryption.AES256);
        }
        if (objectLockMode != null) {
            putBuilder.objectLockMode(objectLockMode).objectLockRetainUntilDate(retentionUntil);
        }

        PutObjectResponse putResponse = backupS3Client.putObject(putBuilder.build(), RequestBody.fromFile(file));
        String uploadedVersionId = trimToNull(putResponse == null ? null : putResponse.versionId());
        if (objectLockMode != null && uploadedVersionId == null) {
            throw new IllegalStateException(
                    "Backup destination did not identify the exact object version protected by Object Lock"
            );
        }

        HeadObjectResponse head = backupS3Client.headObject(buildHeadObjectRequest(
                bucket,
                key,
                uploadedVersionId,
                configuration.uploadTimeout()
        ));
        String actualServerSideEncryption = verifyHeadResponse(
                head,
                expectedBytes,
                expectedSha256,
                backupS3Props.isRequireServerSideEncryption()
        );
        if (objectLockMode != null) {
            GetObjectRetentionResponse retention = backupS3Client.getObjectRetention(
                    buildGetObjectRetentionRequest(
                            bucket,
                            key,
                            uploadedVersionId,
                            configuration.uploadTimeout()
                    )
            );
            verifyObjectRetention(retention, objectLockMode, retentionUntil);
        }

        Path downloaded = configuration.workDir().resolve(".verify_" + runId + ".sql.gz.enc");
        Exception verificationFailure = null;
        try {
            backupS3Client.getObject(
                    buildGetObjectRequest(bucket, key, uploadedVersionId, configuration.uploadTimeout()),
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
            verifyEncryptedEnvelope(downloaded, configuration.encryptionKey());
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
                uploadedVersionId,
                expectedSha256,
                expectedBytes,
                CLIENT_SIDE_ENCRYPTION,
                true,
                actualServerSideEncryption,
                backupS3Props.isRequireServerSideEncryption(),
                objectLockMode,
                retentionUntil,
                Duration.between(startedAt, verifiedAt)
        );
    }

    static HeadObjectRequest buildHeadObjectRequest(
            String bucket,
            String key,
            String versionId,
            Duration timeout
    ) {
        HeadObjectRequest.Builder builder = HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(timeout)
                        .apiCallAttemptTimeout(timeout));
        if (versionId != null) {
            builder.versionId(versionId);
        }
        return builder.build();
    }

    static GetObjectRetentionRequest buildGetObjectRetentionRequest(
            String bucket,
            String key,
            String versionId,
            Duration timeout
    ) {
        GetObjectRetentionRequest.Builder builder = GetObjectRetentionRequest.builder()
                .bucket(bucket)
                .key(key)
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(timeout)
                        .apiCallAttemptTimeout(timeout));
        if (versionId != null) {
            builder.versionId(versionId);
        }
        return builder.build();
    }

    static GetObjectRequest buildGetObjectRequest(
            String bucket,
            String key,
            String versionId,
            Duration timeout
    ) {
        GetObjectRequest.Builder builder = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(timeout)
                        .apiCallAttemptTimeout(timeout));
        if (versionId != null) {
            builder.versionId(versionId);
        }
        return builder.build();
    }

    static String verifyHeadResponse(
            HeadObjectResponse head,
            long expectedBytes,
            String expectedSha256,
            boolean requireServerSideEncryption
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
        ServerSideEncryption actualEncryption = head.serverSideEncryption();
        if (requireServerSideEncryption && actualEncryption != ServerSideEncryption.AES256) {
            throw new IllegalStateException("Backup destination did not confirm AES-256 server-side encryption");
        }
        if (actualEncryption != null && actualEncryption != ServerSideEncryption.AES256) {
            throw new IllegalStateException("Backup destination reported an unsupported server-side encryption mode");
        }
        return actualEncryption == ServerSideEncryption.AES256
                ? ServerSideEncryption.AES256.toString()
                : NO_SERVER_SIDE_ENCRYPTION_REPORTED;
    }

    static void verifyObjectRetention(
            GetObjectRetentionResponse response,
            ObjectLockMode expectedObjectLockMode,
            Instant expectedRetentionUntil
    ) {
        if (response == null || response.retention() == null) {
            throw new IllegalStateException("Backup destination returned no Object Lock retention settings");
        }
        String actualMode = response.retention().modeAsString();
        if (actualMode == null || !expectedObjectLockMode.toString().equals(actualMode)) {
            throw new IllegalStateException("Backup destination did not confirm the requested Object Lock mode");
        }
        Instant actualRetention = response.retention().retainUntilDate();
        if (actualRetention == null || actualRetention.isBefore(expectedRetentionUntil.minusSeconds(1))) {
            throw new IllegalStateException("Backup destination did not confirm the requested Object Lock retention");
        }
    }

    public BackupEvidenceSummary readEvidenceSummary() throws IOException {
        String configuredWorkDir = requireNonBlank(backupProps.getWorkDir(), "backup.work-dir");
        String evidenceFileName = requireNonBlank(backupProps.getEvidenceFileName(), "backup.evidence-file-name");
        if (!SAFE_EVIDENCE_FILE.matcher(evidenceFileName).matches()) {
            throw new IllegalStateException("backup.evidence-file-name must be a simple file name");
        }

        Path workDir = Paths.get(configuredWorkDir).toAbsolutePath().normalize();
        if (!Files.exists(workDir)) {
            return BackupEvidenceSummary.empty();
        }
        Path realWorkDir = workDir.toRealPath();
        if (!Files.isDirectory(realWorkDir)) {
            throw new IllegalStateException("backup.work-dir must be a directory");
        }
        Path evidenceFile = realWorkDir.resolve(evidenceFileName).normalize();
        if (!evidenceFile.getParent().equals(realWorkDir)) {
            throw new IllegalStateException("backup.evidence-file-name must remain inside backup.work-dir");
        }
        if (!Files.exists(evidenceFile)) {
            return BackupEvidenceSummary.empty();
        }
        if (Files.isSymbolicLink(evidenceFile) || !Files.isRegularFile(evidenceFile)) {
            throw new IllegalStateException("backup evidence path must be a regular, non-symbolic file");
        }

        Instant latestVerifiedAt = null;
        Set<String> completedManualRequests = new HashSet<>();
        int ignoredRecords = 0;
        try (BufferedReader reader = Files.newBufferedReader(evidenceFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode record = objectMapper.readTree(line);
                    if (!isVerifiedEvidence(record)) {
                        ignoredRecords++;
                        continue;
                    }
                    Instant verifiedAt = Instant.parse(record.path("timestampUtc").asText());
                    if (latestVerifiedAt == null || verifiedAt.isAfter(latestVerifiedAt)) {
                        latestVerifiedAt = verifiedAt;
                    }
                    String requestId = record.path("requestId").asText("");
                    if ("manual".equals(record.path("trigger").asText())
                            && SAFE_RUN_REQUEST_ID.matcher(requestId).matches()) {
                        completedManualRequests.add(requestId);
                    }
                } catch (RuntimeException | IOException invalidRecord) {
                    // A truncated final append must cause a backup to run, never suppress one.
                    ignoredRecords++;
                }
            }
        }
        if (ignoredRecords > 0) {
            log.warn("Ignored {} invalid database backup evidence record(s)", ignoredRecords);
        }
        return new BackupEvidenceSummary(
                Optional.ofNullable(latestVerifiedAt),
                Set.copyOf(completedManualRequests)
        );
    }

    private static boolean isVerifiedEvidence(JsonNode record) {
        if (record == null
                || !BACKUP_EVIDENCE_SCHEMA.equals(record.path("schema").asText())
                || !BACKUP_FORMAT.equals(record.path("format").asText())
                || record.path("timestampUtc").asText("").isBlank()) {
            return false;
        }
        JsonNode verification = record.path("verification");
        JsonNode cleanup = record.path("temporaryFileCleanup");
        boolean serverSideEncryptionRequired = verification.has("serverSideEncryptionRequired")
                ? verification.path("serverSideEncryptionRequired").asBoolean(false)
                : true;
        String actualServerSideEncryption = verification.path("serverSideEncryption").asText();
        boolean legacyClientSideEvidence = !verification.has("clientSideEncryption")
                && !verification.has("clientSideEnvelopeVerified")
                && !verification.has("serverSideEncryptionRequired");
        boolean validClientSideEncryption = legacyClientSideEvidence
                || (CLIENT_SIDE_ENCRYPTION.equals(verification.path("clientSideEncryption").asText())
                && verification.path("clientSideEnvelopeVerified").asBoolean(false));
        boolean validServerSideEncryption = ServerSideEncryption.AES256.toString()
                .equals(actualServerSideEncryption)
                || (!serverSideEncryptionRequired
                && NO_SERVER_SIDE_ENCRYPTION_REPORTED.equals(actualServerSideEncryption));
        boolean remoteObjectVerified = verification.path("head").asBoolean(false)
                && verification.path("download").asBoolean(false)
                && verification.path("sha256").asBoolean(false)
                && validClientSideEncryption
                && validServerSideEncryption;
        if (!remoteObjectVerified) {
            return false;
        }

        String phase = record.path("phase").asText("");
        if (EVIDENCE_PHASE_REMOTE_VERIFIED.equals(phase)) {
            return cleanup.path("plaintextSqlDeleted").asBoolean(false)
                    && cleanup.path("plaintextGzipDeleted").asBoolean(false)
                    && cleanup.path("verificationDownloadDeleted").asBoolean(false)
                    && !cleanup.path("encryptedTempDeleted").asBoolean(true)
                    && cleanup.path("encryptedPartsDeleted").asBoolean(false);
        }
        if (!phase.isEmpty() && !EVIDENCE_PHASE_COMPLETED.equals(phase)) {
            return false;
        }
        return cleanup.path("plaintextSqlDeleted").asBoolean(false)
                && cleanup.path("plaintextGzipDeleted").asBoolean(false)
                && cleanup.path("verificationDownloadDeleted").asBoolean(false)
                && cleanup.path("encryptedTempDeleted").asBoolean(false)
                && cleanup.path("encryptedPartsDeleted").asBoolean(false);
    }

    void writeEvidence(
            Path evidenceFile,
            VerifiedBackup backup,
            BackupCleanupResult cleanup
    ) throws IOException {
        writeEvidence(evidenceFile, backup, cleanup, null, null, BackupMailDeliveryResult.disabled());
    }

    void writeEvidence(
            Path evidenceFile,
            VerifiedBackup backup,
            BackupCleanupResult cleanup,
            String trigger,
            String requestId
    ) throws IOException {
        writeEvidence(evidenceFile, backup, cleanup, trigger, requestId, BackupMailDeliveryResult.disabled());
    }

    void writeRemoteVerificationEvidence(
            Path evidenceFile,
            VerifiedBackup backup,
            String trigger,
            String requestId,
            boolean mailEnabled
    ) throws IOException {
        writeEvidenceRecord(
                evidenceFile,
                backup,
                new BackupCleanupResult(true, true, true, false, true),
                trigger,
                requestId,
                BackupMailDeliveryResult.pending(mailEnabled),
                EVIDENCE_PHASE_REMOTE_VERIFIED
        );
    }

    void recordVerifiedBackupAndReportMailFailure(
            Path evidenceFile,
            VerifiedBackup backup,
            BackupCleanupResult cleanup,
            String trigger,
            String requestId,
            BackupMailDeliveryResult mailDelivery,
            Exception mailFailure
    ) throws IOException {
        try {
            writeEvidence(evidenceFile, backup, cleanup, trigger, requestId, mailDelivery);
        } catch (IOException | RuntimeException evidenceFailure) {
            if (mailFailure != null) {
                evidenceFailure.addSuppressed(mailFailure);
            }
            throw evidenceFailure;
        }
        if (mailFailure != null) {
            throw new IllegalStateException(
                    "Encrypted backup email delivery failed after the exact S3 object was verified; "
                            + "S3 evidence was recorded and the immutable object will not be uploaded again",
                    mailFailure
            );
        }
    }

    void writeEvidence(
            Path evidenceFile,
            VerifiedBackup backup,
            BackupCleanupResult cleanup,
            String trigger,
            String requestId,
            BackupMailDeliveryResult mailDelivery
    ) throws IOException {
        writeEvidenceRecord(
                evidenceFile,
                backup,
                cleanup,
                trigger,
                requestId,
                mailDelivery,
                EVIDENCE_PHASE_COMPLETED
        );
    }

    private void writeEvidenceRecord(
            Path evidenceFile,
            VerifiedBackup backup,
            BackupCleanupResult cleanup,
            String trigger,
            String requestId,
            BackupMailDeliveryResult mailDelivery,
            String phase
    ) throws IOException {
        if (requestId != null) {
            requireRunRequestId(requestId);
        }
        if (mailDelivery == null) {
            throw new IllegalArgumentException("mailDelivery is required");
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schema", BACKUP_EVIDENCE_SCHEMA);
        evidence.put("phase", phase);
        evidence.put("timestampUtc", backup.verifiedAt().toString());
        if (trigger != null) {
            evidence.put("trigger", trigger);
        }
        if (requestId != null) {
            evidence.put("requestId", requestId);
        }
        evidence.put("bucket", backup.bucket());
        evidence.put("objectKey", backup.key());
        if (backup.versionId() != null) {
            evidence.put("objectVersionId", backup.versionId());
        }
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
        verification.put("clientSideEncryption", backup.clientSideEncryption());
        verification.put("clientSideEnvelopeVerified", backup.clientSideEnvelopeVerified());
        verification.put("serverSideEncryption", backup.serverSideEncryption());
        verification.put("serverSideEncryptionRequired", backup.serverSideEncryptionRequired());
        verification.put("objectLock", backup.objectLockMode() != null);
        evidence.put("verification", verification);

        Map<String, Object> cleanupEvidence = new LinkedHashMap<>();
        cleanupEvidence.put("plaintextSqlDeleted", cleanup.plaintextSqlDeleted());
        cleanupEvidence.put("plaintextGzipDeleted", cleanup.plaintextGzipDeleted());
        cleanupEvidence.put("verificationDownloadDeleted", cleanup.verificationDownloadDeleted());
        cleanupEvidence.put("encryptedTempDeleted", cleanup.encryptedTempDeleted());
        cleanupEvidence.put("encryptedPartsDeleted", cleanup.encryptedPartsDeleted());
        evidence.put("temporaryFileCleanup", cleanupEvidence);

        Map<String, Object> mailEvidence = new LinkedHashMap<>();
        mailEvidence.put("enabled", mailDelivery.enabled());
        mailEvidence.put("attempted", mailDelivery.attempted());
        mailEvidence.put("succeeded", mailDelivery.succeeded());
        mailEvidence.put("encryptedPartCount", mailDelivery.encryptedPartCount());
        evidence.put("emailDelivery", mailEvidence);

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

    static String requireRunRequestId(String value) {
        String requestId = requireNonBlank(value, "backup.run-once.request-id");
        if (!SAFE_RUN_REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalStateException(
                    "backup.run-once.request-id must contain only letters, digits, dot, underscore, colon or dash"
            );
        }
        return requestId;
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

    static LocalBackupRunLock acquireLocalBackupRunLock(Path realWorkDir) throws IOException {
        Path lockFile = realWorkDir.resolve(LOCAL_RUN_LOCK_FILE).normalize();
        if (!lockFile.getParent().equals(realWorkDir)) {
            throw new IllegalStateException("Backup run lock must remain inside backup.work-dir");
        }
        if (!Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) {
            try {
                createEmptyPrivateFile(lockFile);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another process created the fixed lock file concurrently.
            }
        }
        if (Files.isSymbolicLink(lockFile) || !Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Backup run lock path must be a regular, non-symbolic file");
        }

        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                lock = null;
            }
            if (lock == null) {
                throw new IllegalStateException("Another database backup is already using this work directory");
            }
            return new LocalBackupRunLock(channel, lock);
        } catch (IOException | RuntimeException exception) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    static int cleanupStaleTemporaryFiles(Path realWorkDir, Path evidenceFile) throws IOException {
        int cleaned = 0;
        try (var candidates = Files.newDirectoryStream(realWorkDir)) {
            for (Path candidate : candidates) {
                Path normalized = candidate.toAbsolutePath().normalize();
                if (!normalized.getParent().equals(realWorkDir) || normalized.equals(evidenceFile)) {
                    continue;
                }
                String fileName = normalized.getFileName().toString();
                boolean plaintext = OWNED_PLAINTEXT_TEMP_FILE.matcher(fileName).matches();
                boolean encrypted = OWNED_ENCRYPTED_TEMP_FILE.matcher(fileName).matches();
                if (!plaintext && !encrypted) {
                    continue;
                }
                if (Files.isSymbolicLink(normalized)) {
                    deleteRequired(normalized);
                } else if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Owned backup temporary path is not a regular file: " + fileName);
                } else if (plaintext) {
                    overwriteAndDeletePlaintext(normalized);
                } else {
                    deleteRequired(normalized);
                }
                cleaned++;
            }
        }
        if (cleaned > 0) {
            log.warn("Removed {} stale database backup temporary file(s) before starting a new run", cleaned);
        }
        return cleaned;
    }

    static void overwriteAndDeletePlaintext(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path)) {
            deleteRequired(path);
            return;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Plaintext backup temporary path is not a regular file: " + path.getFileName());
        }

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            long remaining = channel.size();
            ByteBuffer zeros = ByteBuffer.allocate(64 * 1024);
            channel.position(0);
            while (remaining > 0) {
                zeros.clear();
                zeros.limit((int) Math.min((long) zeros.capacity(), remaining));
                while (zeros.hasRemaining()) {
                    channel.write(zeros);
                }
                remaining -= zeros.limit();
            }
            channel.force(true);
        }
        deleteRequired(path);
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

    private static void cleanupRunArtifacts(
            Path sqlFile,
            Path gzFile,
            Path encryptedFile,
            List<Path> parts
    ) throws IOException {
        IOException firstFailure = null;
        for (Path plaintext : List.of(sqlFile, gzFile)) {
            try {
                overwriteAndDeletePlaintext(plaintext);
            } catch (IOException exception) {
                firstFailure = appendFailure(firstFailure, exception);
            }
        }
        List<Path> encryptedArtifacts = new ArrayList<>(parts.size() + 1);
        encryptedArtifacts.add(encryptedFile);
        encryptedArtifacts.addAll(parts);
        for (Path encrypted : encryptedArtifacts) {
            try {
                deleteRequired(encrypted);
            } catch (IOException exception) {
                firstFailure = appendFailure(firstFailure, exception);
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private static IOException appendFailure(IOException firstFailure, IOException nextFailure) {
        if (firstFailure == null) {
            return nextFailure;
        }
        firstFailure.addSuppressed(nextFailure);
        return firstFailure;
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
            String versionId,
            String sha256,
            long bytes,
            String clientSideEncryption,
            boolean clientSideEnvelopeVerified,
            String serverSideEncryption,
            boolean serverSideEncryptionRequired,
            ObjectLockMode objectLockMode,
            Instant retentionUntil,
            Duration elapsed
    ) {
    }

    static final class LocalBackupRunLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private LocalBackupRunLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            IOException firstFailure = null;
            try {
                lock.close();
            } catch (IOException exception) {
                firstFailure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                firstFailure = appendFailure(firstFailure, exception);
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
        }
    }

    record BackupCleanupResult(
            boolean plaintextSqlDeleted,
            boolean plaintextGzipDeleted,
            boolean verificationDownloadDeleted,
            boolean encryptedTempDeleted,
            boolean encryptedPartsDeleted
    ) {
    }

    record BackupMailDeliveryResult(
            boolean enabled,
            boolean attempted,
            boolean succeeded,
            int encryptedPartCount
    ) {
        BackupMailDeliveryResult {
            if (encryptedPartCount < 0) {
                throw new IllegalArgumentException("encryptedPartCount must be non-negative");
            }
            if (!enabled && (attempted || succeeded || encryptedPartCount != 0)) {
                throw new IllegalArgumentException("Disabled backup email cannot contain delivery results");
            }
            if (succeeded && !attempted) {
                throw new IllegalArgumentException("Successful backup email must have been attempted");
            }
        }

        static BackupMailDeliveryResult disabled() {
            return new BackupMailDeliveryResult(false, false, false, 0);
        }

        static BackupMailDeliveryResult pending(boolean enabled) {
            return enabled
                    ? new BackupMailDeliveryResult(true, false, false, 0)
                    : disabled();
        }

        static BackupMailDeliveryResult failed(int encryptedPartCount) {
            return new BackupMailDeliveryResult(true, true, false, encryptedPartCount);
        }

        static BackupMailDeliveryResult succeeded(int encryptedPartCount) {
            return new BackupMailDeliveryResult(true, true, true, encryptedPartCount);
        }
    }

    public record BackupEvidenceSummary(
            Optional<Instant> latestVerifiedAt,
            Set<String> completedManualRequestIds
    ) {
        static BackupEvidenceSummary empty() {
            return new BackupEvidenceSummary(Optional.empty(), Set.of());
        }

        public boolean containsManualRequest(String requestId) {
            return completedManualRequestIds.contains(requestId);
        }
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

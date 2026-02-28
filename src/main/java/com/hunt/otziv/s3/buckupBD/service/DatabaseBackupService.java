package com.hunt.otziv.s3.buckupBD.service;

import com.hunt.otziv.config.email.EmailService;
import com.hunt.otziv.s3.buckupBD.config.BackupProperties;
import com.hunt.otziv.s3.buckupBD.config.S3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseBackupService {

    private final BackupProperties backupProps;
    private final S3Properties s3Props;
    private final S3Client s3Client;
    private final EmailService emailService;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public void runDailyBackup() throws Exception {
        String ts = LocalDateTime.now().format(TS);

        Path workDir = Paths.get(backupProps.getWorkDir());
        Files.createDirectories(workDir);

        Path sqlFile = workDir.resolve("backup_" + ts + ".sql");
        Path gzFile  = workDir.resolve("backup_" + ts + ".sql.gz");

        List<Path> parts = new ArrayList<>();

        try {
            // 1) mysqldump -> .sql
            dumpViaTcp(sqlFile);

            // 2) gzip
            gzip(sqlFile, gzFile);

            // 3) upload to S3
            uploadToS3(gzFile, ts);

            // 4) split & email
            long partSizeBytes = (long) backupProps.getPartSizeMb() * 1024 * 1024;
            parts = splitFile(gzFile, partSizeBytes);

            sendPartsByEmail(parts, ts);

            log.info("✅ Backup completed: {}", gzFile);
        } finally {
            // cleanup parts + исходники
            safeDelete(sqlFile);
            safeDelete(gzFile);
            for (Path p : parts) safeDelete(p);
        }
    }

    private void dumpViaTcp(Path outSql) throws Exception {
        var mysql = backupProps.getMysql();

        String host = "mysql";
        String port = "3306";


        // ВАЖНО: без quote вокруг пароля в MYSQL_PWD, иначе может стать частью значения с кавычками
        String cmd = "MYSQL_PWD=" + escapeEnv(mysql.getPassword())
                + " mysqldump -h " + host + " -P " + port
                + " -u" + mysql.getUser()
                + " --single-transaction --routines --triggers --no-tablespaces --set-gtid-purged=OFF "
                + mysql.getDb();

//        System.out.println(cmd);

        log.info("▶ Running dump via TCP: {}", outSql);

        ProcessBuilder pb = new ProcessBuilder("sh", "-lc", cmd);
        Process p = pb.start();

        // stderr отдельно
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Thread errThread = new Thread(() -> {
            try (InputStream es = p.getErrorStream()) {
                es.transferTo(errBuf);
            } catch (IOException ignored) {}
        }, "mysqldump-stderr");
        errThread.start();

        // stdout -> файл
        try (InputStream in = p.getInputStream();
             OutputStream out = Files.newOutputStream(outSql,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            in.transferTo(out);
        }

        int code = p.waitFor();
        errThread.join(2000);

        String err = errBuf.toString(java.nio.charset.StandardCharsets.UTF_8);
        if (!err.isBlank()) {
            log.error("mysqldump stderr:\n{}", err);
        }

        long size = Files.exists(outSql) ? Files.size(outSql) : 0;
        if (code != 0 || size == 0) {
            throw new IllegalStateException("mysqldump failed, exitCode=" + code + ", sqlSize=" + size);
        }
    }

    private static String escapeEnv(String s) {
        // для безопасной подстановки в sh: экранируем пробелы/кавычки/доллары
        // (минимально необходимое)
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }



    private void gzip(Path in, Path out) throws IOException {
        log.info("▶ Gzip: {} -> {}", in.getFileName(), out.getFileName());
        try (InputStream is = Files.newInputStream(in);
             OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             GZIPOutputStream gzip = new GZIPOutputStream(os)) {
            is.transferTo(gzip);
        }
    }

    private void uploadToS3(Path file, String ts) {
        String key = "backup/" + s3Props.getProjectId() + "/backup_" + ts + ".sql.gz";

        log.info("▶ Upload to S3: bucket={}, key={}", s3Props.getBucket(), key);

        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(s3Props.getBucket())
                .key(key)
                .contentType("application/gzip")
                .build();

        s3Client.putObject(req, RequestBody.fromFile(file));
    }

    private List<Path> splitFile(Path file, long partSizeBytes) throws IOException {
        log.info("▶ Split file: {}, partSize={} bytes", file.getFileName(), partSizeBytes);

        List<Path> parts = new ArrayList<>();
        byte[] buffer = new byte[1024 * 1024]; // 1MB

        try (InputStream is = Files.newInputStream(file)) {
            int partIndex = 0;
            long writtenInPart = 0;

            Path partPath = nextPartPath(file, partIndex);
            OutputStream os = Files.newOutputStream(partPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            parts.add(partPath);

            int read;
            while ((read = is.read(buffer)) != -1) {
                int offset = 0;
                while (offset < read) {
                    long left = partSizeBytes - writtenInPart;
                    int toWrite = (int) Math.min(left, read - offset);

                    os.write(buffer, offset, toWrite);
                    offset += toWrite;
                    writtenInPart += toWrite;

                    if (writtenInPart >= partSizeBytes) {
                        os.close();
                        partIndex++;
                        partPath = nextPartPath(file, partIndex);
                        os = Files.newOutputStream(partPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        parts.add(partPath);
                        writtenInPart = 0;
                    }
                }
            }
            os.close();
        }

        // если последняя часть пустая — убрать
        parts.removeIf(p -> {
            try { return Files.size(p) == 0; } catch (IOException e) { return false; }
        });

        return parts;
    }

    private void sendPartsByEmail(List<Path> parts, String ts) {
        var mail = backupProps.getMail();

        for (int i = 0; i < parts.size(); i++) {
            Path part = parts.get(i);

            String subject = mail.getSubject() + " [" + ts + "] part " + (i + 1) + "/" + parts.size();
            String body = mail.getBody() + "\n"
                    + "Backup timestamp: " + ts + "\n"
                    + "Part: " + (i + 1) + "/" + parts.size();

            emailService.sendWithAttachment(
                    mail.getTo(),
                    mail.getFrom(),
                    subject,
                    body,
                    part.toFile()
            );

            log.info("✉ Sent part: {}", part.getFileName());
        }
    }

    private static Path nextPartPath(Path original, int index) {
        return original.resolveSibling(original.getFileName().toString() + ".part" + index);
    }

    private static void safeDelete(Path p) {
        try {
            if (p != null) Files.deleteIfExists(p);
        } catch (Exception ignored) {}
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}


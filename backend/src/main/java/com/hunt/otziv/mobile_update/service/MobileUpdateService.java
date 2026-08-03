package com.hunt.otziv.mobile_update.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.mobile_update.dto.MobileUpdateResponse;
import com.hunt.otziv.mobile_update.model.MobileUpdateRelease;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MobileUpdateService {

    private static final Pattern VERSION_NAME = Pattern.compile("[0-9A-Za-z._-]{1,40}");
    private static final long MAX_APK_SIZE = 100L * 1024L * 1024L;
    private static final long MAX_APK_UNCOMPRESSED_SIZE = 512L * 1024L * 1024L;
    private static final long MAX_MANIFEST_SIZE = 16L * 1024L * 1024L;
    private static final int MAX_ZIP_ENTRIES = 20_000;
    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4b, 0x03, 0x04};

    private final ObjectMapper objectMapper;
    private final Path storageDirectory;
    private final Path metadataPath;

    public MobileUpdateService(
            ObjectMapper objectMapper,
            @Value("${otziv.mobile.update.storage-directory:./mobile-releases}") String storageDirectory
    ) {
        this.objectMapper = objectMapper;
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
        this.metadataPath = this.storageDirectory.resolve("release.json");
    }

    public synchronized MobileUpdateResponse current() {
        MobileUpdateRelease release = readRelease();
        return release == null ? MobileUpdateResponse.disabled() : MobileUpdateResponse.from(release);
    }

    public synchronized MobileUpdateRelease requireCurrentRelease() {
        MobileUpdateRelease release = readRelease();
        if (release == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Обновление приложения пока не опубликовано.");
        }
        return release;
    }

    public synchronized Resource currentApk() {
        MobileUpdateRelease release = requireCurrentRelease();
        Path apkPath = safeReleasePath(release.fileName());
        if (!Files.isRegularFile(apkPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Файл обновления не найден.");
        }
        return new FileSystemResource(apkPath);
    }

    public synchronized MobileUpdateResponse publish(
            MultipartFile apk,
            int versionCode,
            String versionName,
            int minSupportedVersionCode,
            boolean required,
            String notes
    ) {
        validateRequest(apk, versionCode, versionName, minSupportedVersionCode);
        MobileUpdateRelease previous = readRelease();
        if (previous != null && versionCode <= previous.versionCode()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "versionCode должен быть больше опубликованного " + previous.versionCode() + "."
            );
        }

        try {
            Files.createDirectories(storageDirectory);
            String normalizedVersionName = versionName.trim();
            String fileName = "otziv-v" + normalizedVersionName + "-code" + versionCode + ".apk";
            Path finalApk = safeReleasePath(fileName);
            Path tempApk = Files.createTempFile(storageDirectory, "upload-", ".apk.tmp");
            Path tempMetadata = Files.createTempFile(storageDirectory, "release-", ".json.tmp");
            try {
                apk.transferTo(tempApk);
                validateApkFile(tempApk);
                String sha256 = sha256(tempApk);
                MobileUpdateRelease release = new MobileUpdateRelease(
                        versionCode,
                        normalizedVersionName,
                        minSupportedVersionCode,
                        required,
                        normalizeNotes(notes),
                        fileName,
                        Files.size(tempApk),
                        sha256,
                        Instant.now()
                );
                objectMapper.writeValue(tempMetadata.toFile(), release);
                moveReplacing(tempApk, finalApk);
                moveReplacing(tempMetadata, metadataPath);
                cleanupOldApks(fileName);
                return MobileUpdateResponse.from(release);
            } finally {
                Files.deleteIfExists(tempApk);
                Files.deleteIfExists(tempMetadata);
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сохранить APK.", exception);
        }
    }

    private MobileUpdateRelease readRelease() {
        if (!Files.isRegularFile(metadataPath)) {
            return null;
        }
        try {
            return objectMapper.readValue(metadataPath.toFile(), MobileUpdateRelease.class);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Метаданные обновления повреждены.", exception);
        }
    }

    private void validateRequest(MultipartFile apk, int versionCode, String versionName, int minSupportedVersionCode) {
        if (apk == null || apk.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите APK для публикации.");
        }
        if (apk.getSize() > MAX_APK_SIZE) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Размер APK превышает 100 МБ.");
        }
        if (versionCode <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "versionCode должен быть положительным.");
        }
        if (versionName == null || !VERSION_NAME.matcher(versionName.trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный versionName.");
        }
        if (minSupportedVersionCode < 0 || minSupportedVersionCode > versionCode) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный минимальный versionCode.");
        }
        String originalName = apk.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Разрешены только файлы APK.");
        }
    }

    private void validateApkFile(Path apkPath) throws IOException {
        long actualSize = Files.size(apkPath);
        if (actualSize > MAX_APK_SIZE) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Размер APK превышает 100 МБ.");
        }
        if (actualSize < ZIP_SIGNATURE.length) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "APK пуст или поврежден.");
        }
        try (InputStream input = Files.newInputStream(apkPath)) {
            for (byte expected : ZIP_SIGNATURE) {
                if (input.read() != Byte.toUnsignedInt(expected)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл не похож на корректный APK.");
                }
            }
        }

        try (ZipFile archive = new ZipFile(apkPath.toFile())) {
            Set<String> names = new HashSet<>();
            ZipEntry manifest = null;
            long declaredUncompressedSize = 0;
            int entryCount = 0;
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw invalidApk("APK содержит слишком много файлов.");
                }
                String name = entry.getName();
                if (!isSafeArchiveEntryName(name) || !names.add(name)) {
                    throw invalidApk("APK содержит некорректную структуру файлов.");
                }
                long size = entry.getSize();
                if (size > 0) {
                    declaredUncompressedSize = Math.addExact(declaredUncompressedSize, size);
                    if (declaredUncompressedSize > MAX_APK_UNCOMPRESSED_SIZE) {
                        throw invalidApk("Распакованный размер APK слишком велик.");
                    }
                }
                if ("AndroidManifest.xml".equals(name) && !entry.isDirectory()) {
                    manifest = entry;
                }
            }
            if (manifest == null) {
                throw invalidApk("В APK отсутствует AndroidManifest.xml.");
            }
            validateManifestEntry(archive, manifest);
        } catch (ZipException exception) {
            throw invalidApk("Файл не является корректным ZIP/APK архивом.", exception);
        } catch (ArithmeticException exception) {
            throw invalidApk("Распакованный размер APK слишком велик.", exception);
        }
    }

    private void validateManifestEntry(ZipFile archive, ZipEntry manifest) throws IOException {
        long declaredSize = manifest.getSize();
        if (declaredSize == 0 || declaredSize > MAX_MANIFEST_SIZE) {
            throw invalidApk("AndroidManifest.xml пуст или имеет недопустимый размер.");
        }
        long readBytes = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = archive.getInputStream(manifest)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                readBytes += read;
                if (readBytes > MAX_MANIFEST_SIZE) {
                    throw invalidApk("AndroidManifest.xml имеет недопустимый размер.");
                }
            }
        }
        if (readBytes == 0) {
            throw invalidApk("AndroidManifest.xml пуст.");
        }
    }

    private boolean isSafeArchiveEntryName(String name) {
        if (name == null || name.isBlank() || name.indexOf('\u0000') >= 0
                || name.startsWith("/") || name.startsWith("\\") || name.contains("\\")) {
            return false;
        }
        if (name.length() >= 2 && Character.isLetter(name.charAt(0)) && name.charAt(1) == ':') {
            return false;
        }
        for (String segment : name.split("/", -1)) {
            if ("..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private ResponseStatusException invalidApk(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException invalidApk(String message, Exception cause) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message, cause);
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStreamDiscarder.INSTANCE);
            }
            return HexFormat.of().withUpperCase().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Path safeReleasePath(String fileName) {
        Path path = storageDirectory.resolve(fileName).normalize();
        if (!path.getParent().equals(storageDirectory)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректное имя файла обновления.");
        }
        return path;
    }

    private void cleanupOldApks(String currentFileName) throws IOException {
        try (Stream<Path> files = Files.list(storageDirectory)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".apk") && !name.equals(currentFileName)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String normalizeNotes(String notes) {
        if (notes == null || notes.isBlank()) {
            return "Доступна новая версия приложения.";
        }
        String normalized = notes.trim();
        return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
    }

    private static final class OutputStreamDiscarder extends java.io.OutputStream {
        private static final OutputStreamDiscarder INSTANCE = new OutputStreamDiscarder();

        @Override
        public void write(int value) {
        }
    }
}

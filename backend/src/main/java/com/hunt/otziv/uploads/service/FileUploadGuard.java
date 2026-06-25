package com.hunt.otziv.uploads.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Set;

@Component
public class FileUploadGuard {

    private static final Set<String> IMPORT_EXTENSIONS = Set.of("csv", "tsv", "xls", "xlsx");
    private static final Set<String> IMPORT_CONTENT_TYPES = Set.of(
            "text/csv",
            "text/plain",
            "application/csv",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/octet-stream"
    );

    private final long maxImageSizeBytes;
    private final long maxImagePixels;
    private final int maxImageWidth;
    private final int maxImageHeight;
    private final long maxImportSizeBytes;
    private final int maxImportRows;

    public FileUploadGuard(
            @Value("${otziv.upload.image.max-size-bytes:${UPLOAD_IMAGE_MAX_SIZE_BYTES:5242880}}")
            long maxImageSizeBytes,
            @Value("${otziv.upload.image.max-pixels:${UPLOAD_IMAGE_MAX_PIXELS:20000000}}")
            long maxImagePixels,
            @Value("${otziv.upload.image.max-width:${UPLOAD_IMAGE_MAX_WIDTH:8000}}")
            int maxImageWidth,
            @Value("${otziv.upload.image.max-height:${UPLOAD_IMAGE_MAX_HEIGHT:8000}}")
            int maxImageHeight,
            @Value("${otziv.upload.import.max-size-bytes:${UPLOAD_IMPORT_MAX_SIZE_BYTES:5242880}}")
            long maxImportSizeBytes,
            @Value("${otziv.upload.import.max-rows:${UPLOAD_IMPORT_MAX_ROWS:5000}}")
            int maxImportRows
    ) {
        this.maxImageSizeBytes = maxImageSizeBytes;
        this.maxImagePixels = maxImagePixels;
        this.maxImageWidth = maxImageWidth;
        this.maxImageHeight = maxImageHeight;
        this.maxImportSizeBytes = maxImportSizeBytes;
        this.maxImportRows = maxImportRows;
    }

    public ImageCheck requireSupportedImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw badRequest("Файл изображения не выбран");
        }
        if (file.getSize() > maxImageSizeBytes) {
            throw badRequest("Изображение слишком большое");
        }

        byte[] bytes = bytes(file, "Изображение не удалось прочитать");
        ImageType type = imageType(bytes);
        if (type == null) {
            throw badRequest("Поддерживаются только jpg, jpeg, png и webp изображения");
        }

        Dimensions dimensions = dimensions(bytes, type);
        long pixels = (long) dimensions.width() * dimensions.height();
        if (dimensions.width() <= 0
                || dimensions.height() <= 0
                || dimensions.width() > maxImageWidth
                || dimensions.height() > maxImageHeight
                || pixels > maxImagePixels) {
            throw badRequest("Размер изображения в пикселях превышает лимит");
        }

        return new ImageCheck(bytes, type.extension(), dimensions.width(), dimensions.height());
    }

    public String requireSupportedImportFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw badRequest("Файл не выбран");
        }
        if (file.getSize() > maxImportSizeBytes) {
            throw badRequest("Файл импорта слишком большой");
        }

        String extension = extension(file.getOriginalFilename());
        if (!IMPORT_EXTENSIONS.contains(extension)) {
            throw badRequest("Поддерживаются только CSV, TSV, XLS и XLSX");
        }

        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            String normalizedContentType = contentType.toLowerCase(Locale.ROOT);
            int parametersIndex = normalizedContentType.indexOf(';');
            if (parametersIndex >= 0) {
                normalizedContentType = normalizedContentType.substring(0, parametersIndex).trim();
            }
            if (!IMPORT_CONTENT_TYPES.contains(normalizedContentType)) {
                throw badRequest("MIME-тип файла импорта не поддерживается");
            }
        }

        byte[] bytes = bytes(file, "Файл не удалось прочитать");
        if ("xlsx".equals(extension) && !hasZipSignature(bytes)) {
            throw badRequest("XLSX-файл имеет неверную сигнатуру");
        }
        if ("xls".equals(extension) && !hasOleSignature(bytes)) {
            throw badRequest("XLS-файл имеет неверную сигнатуру");
        }
        if (("csv".equals(extension) || "tsv".equals(extension)) && looksBinary(bytes)) {
            throw badRequest("CSV/TSV-файл имеет неверный формат");
        }

        return extension;
    }

    public void requireImportRowLimit(int rows) {
        if (rows > maxImportRows) {
            throw badRequest("Файл импорта содержит слишком много строк");
        }
    }

    private byte[] bytes(MultipartFile file, String message) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw badRequest(message);
        }
    }

    private ImageType imageType(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return ImageType.JPEG;
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G'
                && bytes[4] == 0x0d
                && bytes[5] == 0x0a
                && bytes[6] == 0x1a
                && bytes[7] == 0x0a) {
            return ImageType.PNG;
        }
        if (bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return ImageType.WEBP;
        }
        return null;
    }

    private Dimensions dimensions(byte[] bytes, ImageType type) {
        return switch (type) {
            case JPEG -> jpegDimensions(bytes);
            case PNG -> pngDimensions(bytes);
            case WEBP -> webpDimensions(bytes);
        };
    }

    private Dimensions pngDimensions(byte[] bytes) {
        if (bytes.length < 24) {
            throw badRequest("PNG-файл поврежден");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        return new Dimensions(buffer.getInt(16), buffer.getInt(20));
    }

    private Dimensions jpegDimensions(byte[] bytes) {
        int index = 2;
        while (index + 9 < bytes.length) {
            if ((bytes[index] & 0xff) != 0xff) {
                index++;
                continue;
            }
            int marker = bytes[index + 1] & 0xff;
            index += 2;
            if (marker == 0xd8 || marker == 0xd9 || marker == 0x01) {
                continue;
            }
            if (index + 2 > bytes.length) {
                break;
            }
            int length = unsignedShort(bytes, index);
            if (length < 2 || index + length > bytes.length) {
                break;
            }
            if (isJpegStartOfFrame(marker)) {
                return new Dimensions(unsignedShort(bytes, index + 5), unsignedShort(bytes, index + 3));
            }
            index += length;
        }
        throw badRequest("JPEG-файл поврежден");
    }

    private Dimensions webpDimensions(byte[] bytes) {
        if (bytes.length < 30) {
            throw badRequest("WEBP-файл поврежден");
        }
        String chunk = ascii(bytes, 12, 4);
        if ("VP8X".equals(chunk)) {
            return new Dimensions(1 + littleEndian24(bytes, 24), 1 + littleEndian24(bytes, 27));
        }
        if ("VP8L".equals(chunk)) {
            if (bytes.length < 25 || (bytes[20] & 0xff) != 0x2f) {
                throw badRequest("WEBP-файл поврежден");
            }
            int b0 = bytes[21] & 0xff;
            int b1 = bytes[22] & 0xff;
            int b2 = bytes[23] & 0xff;
            int b3 = bytes[24] & 0xff;
            int width = 1 + (((b1 & 0x3f) << 8) | b0);
            int height = 1 + (((b3 & 0x0f) << 10) | (b2 << 2) | ((b1 & 0xc0) >> 6));
            return new Dimensions(width, height);
        }
        if ("VP8 ".equals(chunk)) {
            if (bytes.length < 30
                    || (bytes[23] & 0xff) != 0x9d
                    || (bytes[24] & 0xff) != 0x01
                    || (bytes[25] & 0xff) != 0x2a) {
                throw badRequest("WEBP-файл поврежден");
            }
            int width = littleEndian16(bytes, 26) & 0x3fff;
            int height = littleEndian16(bytes, 28) & 0x3fff;
            return new Dimensions(width, height);
        }
        throw badRequest("WEBP-файл поврежден");
    }

    private boolean isJpegStartOfFrame(int marker) {
        return marker >= 0xc0
                && marker <= 0xcf
                && marker != 0xc4
                && marker != 0xc8
                && marker != 0xcc;
    }

    private int unsignedShort(byte[] bytes, int index) {
        return ((bytes[index] & 0xff) << 8) | (bytes[index + 1] & 0xff);
    }

    private int littleEndian16(byte[] bytes, int index) {
        return (bytes[index] & 0xff) | ((bytes[index + 1] & 0xff) << 8);
    }

    private int littleEndian24(byte[] bytes, int index) {
        return (bytes[index] & 0xff)
                | ((bytes[index + 1] & 0xff) << 8)
                | ((bytes[index + 2] & 0xff) << 16);
    }

    private boolean hasZipSignature(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == 'P'
                && bytes[1] == 'K'
                && (bytes[2] == 0x03 || bytes[2] == 0x05 || bytes[2] == 0x07)
                && (bytes[3] == 0x04 || bytes[3] == 0x06 || bytes[3] == 0x08);
    }

    private boolean hasOleSignature(byte[] bytes) {
        return bytes.length >= 8
                && (bytes[0] & 0xff) == 0xd0
                && (bytes[1] & 0xff) == 0xcf
                && (bytes[2] & 0xff) == 0x11
                && (bytes[3] & 0xff) == 0xe0
                && (bytes[4] & 0xff) == 0xa1
                && (bytes[5] & 0xff) == 0xb1
                && (bytes[6] & 0xff) == 0x1a
                && (bytes[7] & 0xff) == 0xe1;
    }

    private boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 1024);
        for (int index = 0; index < limit; index++) {
            if (bytes[index] == 0) {
                return true;
            }
        }
        return false;
    }

    private String extension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private enum ImageType {
        JPEG("jpg"),
        PNG("png"),
        WEBP("webp");

        private final String extension;

        ImageType(String extension) {
            this.extension = extension;
        }

        private String extension() {
            return extension;
        }
    }

    public record ImageCheck(byte[] bytes, String extension, int width, int height) {
    }

    private record Dimensions(int width, int height) {
    }
}

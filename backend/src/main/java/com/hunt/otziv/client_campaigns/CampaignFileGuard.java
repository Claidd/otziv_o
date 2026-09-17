package com.hunt.otziv.client_campaigns;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

final class CampaignFileGuard {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Map<String,String> TYPES = Map.ofEntries(
            Map.entry("pdf","application/pdf"),Map.entry("txt","text/plain"),Map.entry("doc","application/msword"),
            Map.entry("docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("pptx","application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("jpg","image/jpeg"),Map.entry("jpeg","image/jpeg"),Map.entry("png","image/png"));
    private CampaignFileGuard() {}
    static CampaignModels.Attachment read(MultipartFile file) {
        if (file == null) return null;
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw invalid("Файл должен быть от 1 байта до 5 МБ");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().replace('\\','/');
        name = name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]", "").trim();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot+1).toLowerCase(Locale.ROOT);
        if (name.length() > 200 || !TYPES.containsKey(ext)) throw invalid("Поддерживаются PDF, TXT, DOC, DOCX, XLSX, PPTX, JPG и PNG");
        try {
            byte[] bytes = file.getBytes();
            boolean valid = switch (ext) {
                case "pdf" -> starts(bytes,0x25,0x50,0x44,0x46,0x2d);
                case "docx","xlsx","pptx" -> starts(bytes,0x50,0x4b,0x03,0x04);
                case "doc" -> starts(bytes,0xd0,0xcf,0x11,0xe0,0xa1,0xb1,0x1a,0xe1);
                case "jpg","jpeg" -> starts(bytes,0xff,0xd8,0xff);
                case "png" -> starts(bytes,0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a);
                default -> { boolean text = true; for (byte b : bytes) if (b == 0) { text = false; break; } yield text; }
            };
            if (!valid || bytes.length > MAX_BYTES) throw invalid("Содержимое файла не соответствует расширению");
            return new CampaignModels.Attachment(name,TYPES.get(ext),bytes);
        } catch (IOException failure) { throw invalid("Не удалось прочитать файл"); }
    }
    private static boolean starts(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i=0;i<prefix.length;i++) if ((bytes[i]&255) != prefix[i]) return false;
        return true;
    }
    private static ResponseStatusException invalid(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason); }
}

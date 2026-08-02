package com.hunt.otziv.mobile_update.controller;

import com.hunt.otziv.mobile_update.dto.MobileUpdateResponse;
import com.hunt.otziv.mobile_update.model.MobileUpdateRelease;
import com.hunt.otziv.mobile_update.service.MobileUpdateService;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile-update")
public class MobileUpdateController {

    private static final MediaType APK_MEDIA_TYPE = MediaType.parseMediaType("application/vnd.android.package-archive");
    private final MobileUpdateService mobileUpdateService;

    public MobileUpdateController(MobileUpdateService mobileUpdateService) {
        this.mobileUpdateService = mobileUpdateService;
    }

    @GetMapping
    public ResponseEntity<MobileUpdateResponse> current() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(mobileUpdateService.current());
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download() {
        MobileUpdateRelease release = mobileUpdateService.requireCurrentRelease();
        Resource apk = mobileUpdateService.currentApk();
        return ResponseEntity.ok()
                .contentType(APK_MEDIA_TYPE)
                .contentLength(release.fileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + release.fileName() + "\"")
                .header(HttpHeaders.ETAG, "\"" + release.sha256() + "\"")
                .cacheControl(CacheControl.noCache())
                .body(apk);
    }
}

package com.hunt.otziv.mobile_update;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/mobile-update")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class MobileUpdateAdminController {

    private final MobileUpdateService mobileUpdateService;

    public MobileUpdateAdminController(MobileUpdateService mobileUpdateService) {
        this.mobileUpdateService = mobileUpdateService;
    }

    @PostMapping
    public MobileUpdateResponse publish(
            @RequestParam("apk") MultipartFile apk,
            @RequestParam int versionCode,
            @RequestParam String versionName,
            @RequestParam(defaultValue = "0") int minSupportedVersionCode,
            @RequestParam(defaultValue = "false") boolean required,
            @RequestParam(defaultValue = "") String notes
    ) {
        return mobileUpdateService.publish(
                apk,
                versionCode,
                versionName,
                minSupportedVersionCode,
                required,
                notes
        );
    }
}

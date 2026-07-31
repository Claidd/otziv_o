package com.hunt.otziv.notification_media.controller;

import com.hunt.otziv.notification_media.dto.NotificationMediaAssetRequest;
import com.hunt.otziv.notification_media.dto.NotificationMediaEventResponse;
import com.hunt.otziv.notification_media.dto.NotificationMediaRuleRequest;
import com.hunt.otziv.notification_media.dto.NotificationMediaRuleResponse;
import com.hunt.otziv.notification_media.dto.NotificationMediaTestResponse;
import com.hunt.otziv.notification_media.service.NotificationMediaAdminService;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/notification-media")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class ApiAdminNotificationMediaController {

    private final NotificationMediaAdminService service;

    @GetMapping("/events")
    public List<NotificationMediaEventResponse> events() {
        return service.events();
    }

    @GetMapping("/rules")
    public List<NotificationMediaRuleResponse> rules() {
        return service.rules();
    }

    @PostMapping("/rules")
    public NotificationMediaRuleResponse create(
            @RequestBody NotificationMediaRuleRequest request,
            Principal principal
    ) {
        return service.create(request, principal);
    }

    @PutMapping("/rules/{ruleId}")
    public NotificationMediaRuleResponse update(
            @PathVariable Long ruleId,
            @RequestBody NotificationMediaRuleRequest request,
            Principal principal
    ) {
        return service.update(ruleId, request, principal);
    }

    @DeleteMapping("/rules/{ruleId}")
    public void deleteRule(@PathVariable Long ruleId) {
        service.deleteRule(ruleId);
    }

    @PostMapping(value = "/rules/{ruleId}/images", consumes = "multipart/form-data")
    public NotificationMediaRuleResponse upload(
            @PathVariable Long ruleId,
            @RequestPart("files") List<MultipartFile> files,
            Principal principal
    ) {
        return service.upload(ruleId, files, principal);
    }

    @PutMapping("/images/{assetId}")
    public NotificationMediaRuleResponse updateAsset(
            @PathVariable Long assetId,
            @RequestBody NotificationMediaAssetRequest request
    ) {
        return service.updateAsset(assetId, request);
    }

    @PutMapping(value = "/images/{assetId}/file", consumes = "multipart/form-data")
    public NotificationMediaRuleResponse replaceAsset(
            @PathVariable Long assetId,
            @RequestPart("file") MultipartFile file,
            Principal principal
    ) {
        return service.replaceAsset(assetId, file, principal);
    }

    @DeleteMapping("/images/{assetId}")
    public NotificationMediaRuleResponse deleteAsset(@PathVariable Long assetId) {
        return service.deleteAsset(assetId);
    }

    @PostMapping("/rules/{ruleId}/test")
    public NotificationMediaTestResponse test(@PathVariable Long ruleId, Principal principal) {
        return service.test(ruleId, principal);
    }
}

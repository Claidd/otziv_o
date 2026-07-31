package com.hunt.otziv.notification_media.service;

import com.hunt.otziv.notification_media.dto.NotificationMediaAssetRequest;
import com.hunt.otziv.notification_media.dto.NotificationMediaAssetResponse;
import com.hunt.otziv.notification_media.dto.NotificationMediaEventResponse;
import com.hunt.otziv.notification_media.dto.NotificationMediaRuleRequest;
import com.hunt.otziv.notification_media.dto.NotificationMediaRuleResponse;
import com.hunt.otziv.notification_media.dto.NotificationMediaTestResponse;
import com.hunt.otziv.notification_media.model.NotificationMediaAsset;
import com.hunt.otziv.notification_media.model.NotificationMediaRule;
import com.hunt.otziv.notification_media.repository.NotificationMediaAssetRepository;
import com.hunt.otziv.notification_media.repository.NotificationMediaRuleRepository;
import com.hunt.otziv.notification_media.service.NotificationMediaStorageService.StoredNotificationImage;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class NotificationMediaAdminService {

    private static final int MAX_FILES_PER_UPLOAD = 20;

    private final NotificationMediaRuleRepository ruleRepository;
    private final NotificationMediaAssetRepository assetRepository;
    private final NotificationMediaStorageService storageService;
    private final NotificationMediaDeliveryService deliveryService;
    private final UserRepository userRepository;

    public List<NotificationMediaEventResponse> events() {
        return Arrays.stream(NotificationMediaEventCatalog.values())
                .map(event -> new NotificationMediaEventResponse(
                        event.code(),
                        event.recipientType(),
                        event.label(),
                        event.description(),
                        event.serious()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<NotificationMediaRuleResponse> rules() {
        return ruleRepository.findAllByOrderByEventCodeAsc().stream()
                .map(this::response)
                .toList();
    }

    @Transactional
    public NotificationMediaRuleResponse create(NotificationMediaRuleRequest request, Principal principal) {
        NotificationMediaEventCatalog event = event(request == null ? null : request.eventCode());
        if (ruleRepository.findByEventCode(event.code()).isPresent()) {
            throw conflict("Настройка для этого события уже существует");
        }
        Long actorId = currentUserId(principal);
        NotificationMediaRule rule = new NotificationMediaRule();
        rule.setEventCode(event.code());
        rule.setRecipientType(event.recipientType());
        apply(rule, request);
        rule.setCreatedByUserId(actorId);
        rule.setUpdatedByUserId(actorId);
        try {
            return response(ruleRepository.saveAndFlush(rule));
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Настройка для этого события уже существует");
        }
    }

    @Transactional
    public NotificationMediaRuleResponse update(
            Long ruleId,
            NotificationMediaRuleRequest request,
            Principal principal
    ) {
        NotificationMediaRule rule = requiredRule(ruleId);
        apply(rule, request);
        rule.setUpdatedByUserId(currentUserId(principal));
        return response(ruleRepository.save(rule));
    }

    @Transactional
    public void deleteRule(Long ruleId) {
        NotificationMediaRule rule = requiredRule(ruleId);
        List<String> storageKeys = assetRepository.findByRuleIdOrderBySortOrderAscIdAsc(rule.getId())
                .stream()
                .map(NotificationMediaAsset::getStorageKey)
                .toList();
        ruleRepository.delete(rule);
        ruleRepository.flush();
        storageKeys.forEach(storageService::delete);
    }

    @Transactional
    public NotificationMediaRuleResponse upload(
            Long ruleId,
            List<MultipartFile> files,
            Principal principal
    ) {
        NotificationMediaRule rule = requiredRule(ruleId);
        if (files == null || files.isEmpty()) {
            throw badRequest("Выберите хотя бы одну картинку");
        }
        List<MultipartFile> selected = files.stream()
                .filter(file -> file != null && !file.isEmpty())
                .toList();
        if (selected.isEmpty()) {
            throw badRequest("Выберите хотя бы одну картинку");
        }
        if (selected.size() > MAX_FILES_PER_UPLOAD) {
            throw badRequest("За один раз можно загрузить не более " + MAX_FILES_PER_UPLOAD + " картинок");
        }

        Long actorId = currentUserId(principal);
        int nextSortOrder = assetRepository.findByRuleIdOrderBySortOrderAscIdAsc(ruleId).stream()
                .map(NotificationMediaAsset::getSortOrder)
                .max(Comparator.naturalOrder())
                .orElse(-1) + 1;
        List<StoredNotificationImage> stored = new ArrayList<>();
        try {
            List<NotificationMediaAsset> assets = new ArrayList<>();
            for (int index = 0; index < selected.size(); index++) {
                MultipartFile file = selected.get(index);
                StoredNotificationImage image = storageService.store(file, rule.getEventCode());
                stored.add(image);
                NotificationMediaAsset asset = new NotificationMediaAsset();
                asset.setRule(rule);
                asset.setStorageKey(image.storageKey());
                asset.setImageUrl(image.imageUrl());
                asset.setOriginalFilename(filename(file.getOriginalFilename()));
                asset.setContentType(image.contentType());
                asset.setActive(true);
                asset.setSortOrder(nextSortOrder + index);
                asset.setCreatedByUserId(actorId);
                assets.add(asset);
            }
            assetRepository.saveAll(assets);
            assetRepository.flush();
            return response(rule);
        } catch (RuntimeException exception) {
            stored.forEach(image -> storageService.delete(image.storageKey()));
            throw exception;
        }
    }

    @Transactional
    public NotificationMediaRuleResponse updateAsset(
            Long assetId,
            NotificationMediaAssetRequest request
    ) {
        NotificationMediaAsset asset = requiredAsset(assetId);
        if (request != null && request.active() != null) {
            asset.setActive(request.active());
        }
        if (request != null && request.sortOrder() != null) {
            asset.setSortOrder(Math.max(0, request.sortOrder()));
        }
        assetRepository.save(asset);
        return response(asset.getRule());
    }

    @Transactional
    public NotificationMediaRuleResponse replaceAsset(
            Long assetId,
            MultipartFile file,
            Principal principal
    ) {
        NotificationMediaAsset asset = requiredAsset(assetId);
        StoredNotificationImage replacement = storageService.store(file, asset.getRule().getEventCode());
        String oldStorageKey = asset.getStorageKey();
        try {
            asset.setStorageKey(replacement.storageKey());
            asset.setImageUrl(replacement.imageUrl());
            asset.setContentType(replacement.contentType());
            asset.setOriginalFilename(filename(file.getOriginalFilename()));
            asset.setCreatedByUserId(currentUserId(principal));
            assetRepository.saveAndFlush(asset);
            storageService.delete(oldStorageKey);
            return response(asset.getRule());
        } catch (RuntimeException exception) {
            storageService.delete(replacement.storageKey());
            throw exception;
        }
    }

    @Transactional
    public NotificationMediaRuleResponse deleteAsset(Long assetId) {
        NotificationMediaAsset asset = requiredAsset(assetId);
        NotificationMediaRule rule = asset.getRule();
        String storageKey = asset.getStorageKey();
        assetRepository.delete(asset);
        assetRepository.flush();
        storageService.delete(storageKey);
        return response(rule);
    }

    @Transactional
    public NotificationMediaTestResponse test(Long ruleId, Principal principal) {
        NotificationMediaRule rule = requiredRule(ruleId);
        User user = currentUser(principal);
        if (user.getTelegramChatId() == null) {
            throw conflict("У текущего пользователя не привязан личный Telegram");
        }
        NotificationMediaEventCatalog event = event(rule.getEventCode());
        String text = "🧪 Тест картинки уведомления\n"
                + "Событие: " + event.label() + "\n"
                + "Получатель: " + (event.recipientType().name().equals("MANAGER")
                ? "менеджер"
                : "специалист");
        boolean sent = deliveryService.send(
                event.code(),
                user.getTelegramChatId(),
                user.getId(),
                text,
                null,
                List.of()
        );
        return new NotificationMediaTestResponse(
                sent,
                sent ? "Тест отправлен в личный Telegram" : "Telegram не принял тестовое сообщение"
        );
    }

    private void apply(NotificationMediaRule rule, NotificationMediaRuleRequest request) {
        rule.setEnabled(request == null || request.enabled() == null || request.enabled());
        rule.setImageProbabilityPercent(bounded(
                request == null ? null : request.imageProbabilityPercent(),
                100,
                0,
                100
        ));
        rule.setCooldownMinutes(bounded(
                request == null ? null : request.cooldownMinutes(),
                0,
                0,
                10_080
        ));
    }

    private NotificationMediaRuleResponse response(NotificationMediaRule rule) {
        NotificationMediaEventCatalog event = event(rule.getEventCode());
        List<NotificationMediaAssetResponse> images =
                assetRepository.findByRuleIdOrderBySortOrderAscIdAsc(rule.getId()).stream()
                        .map(asset -> new NotificationMediaAssetResponse(
                                asset.getId(),
                                asset.getImageUrl(),
                                asset.getOriginalFilename(),
                                asset.getContentType(),
                                asset.isActive(),
                                asset.getSortOrder(),
                                asset.getCreatedAt(),
                                asset.getUpdatedAt()
                        ))
                        .toList();
        return new NotificationMediaRuleResponse(
                rule.getId(),
                rule.getEventCode(),
                rule.getRecipientType(),
                event.label(),
                event.description(),
                event.serious(),
                rule.isEnabled(),
                rule.getImageProbabilityPercent(),
                rule.getCooldownMinutes(),
                images,
                rule.getCreatedAt(),
                rule.getUpdatedAt()
        );
    }

    private NotificationMediaRule requiredRule(Long ruleId) {
        return ruleRepository.findById(ruleId)
                .orElseThrow(() -> notFound("Настройка уведомления не найдена"));
    }

    private NotificationMediaAsset requiredAsset(Long assetId) {
        return assetRepository.findById(assetId)
                .orElseThrow(() -> notFound("Картинка уведомления не найдена"));
    }

    private NotificationMediaEventCatalog event(String code) {
        return NotificationMediaEventCatalog.find(code)
                .orElseThrow(() -> badRequest("Неизвестное событие уведомления"));
    }

    private User currentUser(Principal principal) {
        String username = principal == null ? null : principal.getName();
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не определён");
        }
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не найден"));
    }

    private Long currentUserId(Principal principal) {
        return currentUser(principal).getId();
    }

    private int bounded(Integer value, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, value == null ? fallback : value));
    }

    private String filename(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String safe = value.replace("\\", "/");
        int slash = safe.lastIndexOf('/');
        if (slash >= 0) {
            safe = safe.substring(slash + 1);
        }
        safe = safe.replaceAll("[\\p{Cntrl}]", "").trim();
        return safe.length() <= 255 ? safe : safe.substring(safe.length() - 255);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}

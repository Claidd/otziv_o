package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatParticipantIdentity;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.repository.ClientChatParticipantIdentityRepository;
import com.hunt.otziv.u_users.model.User;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ClientChatIdentityService {

    private final ClientChatParticipantIdentityRepository repository;

    @Transactional(readOnly = true)
    public Optional<ClientChatSenderRole> knownRole(
            ClientChatPlatform platform,
            String chatId,
            String externalId,
            String senderName
    ) {
        return knownIdentity(platform, chatId, externalId, senderName)
                .map(ClientChatParticipantIdentity::getSenderRole);
    }

    @Transactional(readOnly = true)
    public Optional<User> knownUser(
            ClientChatPlatform platform,
            String chatId,
            String externalId,
            String senderName
    ) {
        return knownIdentity(platform, chatId, externalId, senderName)
                .filter(identity -> identity.getSenderRole() == ClientChatSenderRole.STAFF)
                .map(ClientChatParticipantIdentity::getLinkedUser);
    }

    private Optional<ClientChatParticipantIdentity> knownIdentity(
            ClientChatPlatform platform,
            String chatId,
            String externalId,
            String senderName
    ) {
        if (platform == null
                || (safe(externalId).isBlank() && normalizeName(senderName).isBlank())) {
            return Optional.empty();
        }
        String key = identityKey(externalId, senderName);
        Optional<ClientChatParticipantIdentity> localIdentity = safe(chatId).isBlank()
                ? Optional.empty()
                : repository.findByPlatformAndChatIdAndIdentityKeyAndActiveTrue(
                                platform,
                                limit(safe(chatId), 160),
                                key);
        if (localIdentity.isPresent() || safe(externalId).isBlank()) {
            return localIdentity;
        }
        return repository
                .findFirstByPlatformAndIdentityKeyAndSenderRoleAndActiveTrueOrderByUpdatedAtDesc(
                        platform,
                        key,
                        ClientChatSenderRole.STAFF
                );
    }

    @Transactional
    public ClientChatParticipantIdentity registerStaff(ClientChatMessage message, Long verifiedByUserId) {
        if (message == null || message.getPlatform() == null) {
            throw new IllegalArgumentException("Сообщение не содержит данных отправителя");
        }
        String key = identityKey(message.getSenderExternalId(), message.getSenderName());
        ClientChatParticipantIdentity identity = repository
                .findByPlatformAndChatIdAndIdentityKeyAndActiveTrue(
                        message.getPlatform(),
                        limit(safe(message.getChatId()), 160),
                        key
                )
                .orElseGet(ClientChatParticipantIdentity::new);
        identity.setPlatform(message.getPlatform());
        identity.setChatId(limit(safe(message.getChatId()), 160));
        identity.setIdentityKey(key);
        identity.setExternalId(limit(message.getSenderExternalId(), 160));
        identity.setNormalizedName(limit(normalizeName(message.getSenderName()), 255));
        identity.setSenderRole(ClientChatSenderRole.STAFF);
        identity.setVerifiedByUserId(verifiedByUserId);
        identity.setSource("MANAGER_CORRECTION");
        identity.setActive(true);
        return repository.save(identity);
    }

    static String identityKey(String externalId, String senderName) {
        String external = safe(externalId);
        if (!external.isBlank()) {
            return limit("id:" + external.toLowerCase(Locale.ROOT), 220);
        }
        String name = normalizeName(senderName);
        if (name.isBlank()) {
            throw new IllegalArgumentException("Нельзя определить отправителя сообщения");
        }
        return limit("name:" + name, 220);
    }

    static String normalizeName(String value) {
        return safe(value)
                .replaceFirst("^@", "")
                .replace('ё', 'е')
                .replace('Ё', 'Е')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

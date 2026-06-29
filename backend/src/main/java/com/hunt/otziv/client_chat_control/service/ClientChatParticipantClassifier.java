package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClientChatParticipantClassifier {

    private final UserRepository userRepository;

    public ClientChatSenderRole classify(
            ClientChatPlatform platform,
            ClientChatDirection direction,
            String senderExternalId
    ) {
        if (platform == null || senderExternalId == null || senderExternalId.isBlank()) {
            return ClientChatSenderRole.CLIENT;
        }
        return switch (platform) {
            case TELEGRAM -> isKnownTelegramUser(senderExternalId)
                    ? ClientChatSenderRole.STAFF
                    : ClientChatSenderRole.CLIENT;
            case WHATSAPP -> isKnownPhone(senderExternalId)
                    ? ClientChatSenderRole.STAFF
                    : ClientChatSenderRole.CLIENT;
            case MAX -> ClientChatSenderRole.CLIENT;
        };
    }

    private boolean isKnownTelegramUser(String senderExternalId) {
        try {
            return userRepository.findByTelegramChatId(Long.parseLong(senderExternalId.trim())).isPresent();
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean isKnownPhone(String senderExternalId) {
        String senderPhone = digits(senderExternalId);
        if (senderPhone.length() < 7) {
            return false;
        }
        List<User> users = userRepository.findAllActiveUsersWithPhoneNumbers();
        return users.stream()
                .map(User::getPhoneNumber)
                .map(ClientChatParticipantClassifier::digits)
                .filter(phone -> phone.length() >= 7)
                .anyMatch(phone -> phone.endsWith(senderPhone) || senderPhone.endsWith(phone));
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D+", "");
    }
}

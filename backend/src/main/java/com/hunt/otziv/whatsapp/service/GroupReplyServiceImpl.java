package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.client_chat_control.dto.ClientChatMessageCommand;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.client_messages.service.PublicationProgressPreferenceService;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import com.hunt.otziv.whatsapp.service.service.GroupReplyService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class GroupReplyServiceImpl implements GroupReplyService {

    private static final Set<String> SYSTEM_NOTIFICATION_PLACEHOLDERS = Set.of(
            "[Вложение: broadcast_notification]",
            "[Вложение: ciphertext]",
            "[Вложение: debug]",
            "[Вложение: e2e_notification]",
            "[Вложение: gp2]",
            "[Вложение: group_notification]",
            "[Вложение: notification]",
            "[Вложение: notification_template]",
            "[Вложение: protocol]"
    );

    private final CompanyService companyService;
    private final WhatsAppGroupCompanyLinker groupCompanyLinker;
    private final PublicationProgressPreferenceService publicationProgressPreferenceService;
    private final WhatsAppService whatsAppService;
    private final ClientChatMessageTrackerService clientChatMessageTrackerService;

    @Override
    public void processGroupReply(WhatsAppGroupReplyDTO reply) {
        if (isSystemNotificationPlaceholder(reply)) {
            log.info("WhatsApp system notification ignored: groupId={}, message={}",
                    reply == null ? null : reply.getGroupId(),
                    reply == null ? null : reply.getMessage());
            return;
        }
        log.info("WhatsApp group reply received: groupId={}, groupNamePresent={}, from={}, messageLength={}",
                reply.getGroupId(), hasText(reply.getGroupName()), maskPhone(reply.getFrom()), textLength(reply.getMessage()));

        Optional<Company> optCompany = companyService.findByGroupId(reply.getGroupId());
        if (optCompany.isPresent()) {
            log.info("Найдена компания '{}' напрямую по GroupId: {}",
                    optCompany.get().getTitle(), reply.getGroupId());
        } else {
            log.info("Компания по GroupId {} не найдена, пробуем искать по телефону и названию", reply.getGroupId());

            String telephoneNumber = reply.getFrom().replaceAll("@c\\.us$", "");
            String rawName = reply.getGroupName();
            String title = rawName.contains(".") ? rawName.substring(0, rawName.indexOf(".")) : rawName;
            log.debug("WhatsApp group reply fallback lookup: phone={}, titlePresent={}",
                    maskPhone(telephoneNumber), hasText(title));

            optCompany = companyService.getCompanyByTelephonAndTitle(telephoneNumber, title);
            if (optCompany.isEmpty()) {
                int linkedByGroupName = groupCompanyLinker.linkByGroupName(reply.getGroupId(), reply.getGroupName());
                if (linkedByGroupName > 0) {
                    log.info("GroupId {} привязан к {} компаниям по названию группы '{}'",
                            reply.getGroupId(), linkedByGroupName, reply.getGroupName());
                    return;
                }
                log.warn("WhatsApp group reply ignored: company not found for groupId={}, phone={}, titlePresent={}, groupNamePresent={}",
                        reply.getGroupId(), maskPhone(telephoneNumber), hasText(title), hasText(reply.getGroupName()));
                return;
            }
            log.info("WhatsApp group reply company found by fallback: companyId={}, phone={}, titlePresent={}",
                    optCompany.get().getId(), maskPhone(telephoneNumber), hasText(title));
        }

        Company found = optCompany.get();
        if (found.getGroupId() == null || found.getGroupId().isBlank()) {
            found.setGroupId(reply.getGroupId());
            companyService.save(found);
            log.info("Компания '{}' (ID={}) успешно привязана к GroupId {}",
                    found.getTitle(), found.getId(), reply.getGroupId());
        } else {
            log.info("Компания '{}' уже имеет GroupId: {}", found.getTitle(), found.getGroupId());
        }
        groupCompanyLinker.linkByGroupName(reply.getGroupId(), reply.getGroupName());

        Optional<PublicationProgressPreferenceService.PreferenceUpdate> preferenceUpdate =
                Boolean.TRUE.equals(reply.getSystemGenerated())
                        ? Optional.empty()
                        : publicationProgressPreferenceService.handleWhatsAppCommand(reply.getGroupId(), reply.getMessage());
        if (preferenceUpdate.isPresent()) {
            sendGroupPreferenceResponse(reply, preferenceUpdate.get().message());
            return;
        }

        trackGroupReply(reply);
        log.info("Обработка ответа из группы '{}' завершена", reply.getGroupName());
    }

    private void trackGroupReply(WhatsAppGroupReplyDTO reply) {
        if (reply == null) {
            return;
        }
        try {
            ClientChatSenderRole senderRoleOverride = Boolean.TRUE.equals(reply.getSystemGenerated())
                    ? ClientChatSenderRole.BOT
                    : reply.isFromMe() && Boolean.FALSE.equals(reply.getSystemGenerated())
                            ? ClientChatSenderRole.STAFF
                            : null;
            clientChatMessageTrackerService.track(new ClientChatMessageCommand(
                    ClientChatPlatform.WHATSAPP,
                    reply.isFromMe() ? ClientChatDirection.OUTGOING : ClientChatDirection.INCOMING,
                    reply.getGroupId(),
                    reply.getGroupName(),
                    reply.getMessageId(),
                    reply.getFrom(),
                    reply.getFromName(),
                    reply.getMessage(),
                    whatsappMessageTime(reply.getTimestamp())
            ), senderRoleOverride);
        } catch (Exception exception) {
            log.warn("WhatsApp group reply tracking failed groupId={}", reply.getGroupId(), exception);
        }
    }

    private void sendGroupPreferenceResponse(WhatsAppGroupReplyDTO reply, String message) {
        if (reply == null || !hasText(reply.getClientId()) || !hasText(reply.getGroupId())) {
            log.warn("WhatsApp preference response skipped: clientId or groupId is empty");
            return;
        }
        whatsAppService.sendMessageToGroup(reply.getClientId(), reply.getGroupId(), message);
    }

    private static int textLength(String value) {
        return value == null ? 0 : value.length();
    }

    private static String maskPhone(String value) {
        if (!hasText(value)) {
            return "";
        }
        String digits = value.replaceAll("@c\\.us$", "").replaceAll("\\D+", "");
        return digits.length() < 4 ? "***" : "***" + digits.substring(digits.length() - 4);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean isSystemNotificationPlaceholder(WhatsAppGroupReplyDTO reply) {
        return reply != null
                && reply.getMessage() != null
                && SYSTEM_NOTIFICATION_PLACEHOLDERS.contains(reply.getMessage().trim());
    }

    private static LocalDateTime whatsappMessageTime(Long timestamp) {
        if (timestamp == null || timestamp <= 0) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault());
    }
}

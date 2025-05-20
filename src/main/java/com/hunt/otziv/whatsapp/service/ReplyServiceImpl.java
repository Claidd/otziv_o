package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.model.Telephone;
import com.hunt.otziv.l_lead.services.LeadService;
import com.hunt.otziv.t_telegrambot.MyTelegramBot;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import com.hunt.otziv.whatsapp.dto.WhatsAppReplyDTO;
import com.hunt.otziv.whatsapp.service.service.ReplyService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReplyServiceImpl implements ReplyService {

    private final CompanyService companyService;
    private final LeadService leadService;
    private final OfferService offerService;
    private final NotificationService notificationService;


    @Override
    public void processIncomingReply(WhatsAppReplyDTO reply) {
        log.info("📩 Ответ от клиента {} ({}): {}", reply.getClientId(), reply.getFrom(), reply.getMessage());

        String telephoneNumber = reply.getFrom().replaceAll("@c\\.us$", "");
        log.info("📞 Извлечён номер телефона: {}", telephoneNumber);

        Optional<Lead> leadOpt = leadService.getByTelephoneLead(telephoneNumber);
        if (leadOpt.isEmpty()) {
            log.warn("❌ Не удалось найти Лид по номеру {} ", telephoneNumber);
            return;
        }

        Lead lead = leadOpt.get();

        if (!lead.isOffer()) {
            String messageText = reply.getMessage().toLowerCase();
            List<String> declineKeywords = List.of("нет", "не надо", "не нужно");

            if (declineKeywords.stream().anyMatch(messageText::contains)) {
                log.info("⛔ Клиент {} отказался в сообщении: '{}'. Оффер не отправляем.", telephoneNumber, messageText);
                return;
            }

            Telephone telephone = lead.getTelephone();
            if (telephone == null) {
                log.warn("⚠️ У лида {} нет привязанного телефона. Пропускаем отправку", lead.getId());
                return;
            }

            String clientId = "client" + telephone.getId();
            String offerText = telephone.getOfferText();

            offerService.sendOfferAsync(lead, clientId, telephoneNumber, offerText);
            log.info("⏳ Оффер поставлен в очередь на отправку клиенту {}", telephoneNumber);
        } else {
            // ✅ если оффер уже был отправлен — проверяем на наличие ссылки
            notifyAdminIfMessageContainsLink(lead, reply);
            log.info("✅ Оффер уже был отправлен ранее клиенту {}", telephoneNumber);
        }
    }


    private static final Pattern LINK_PATTERN = Pattern.compile(
            "(https?://\\S+|www\\.\\S+|t\\.me/\\S+|vk\\.com/\\S+)", Pattern.CASE_INSENSITIVE
    );

    private final List<Long> adminChatIds = List.of(794146111L, 828987226L);

    private void notifyAdminIfMessageContainsLink(Lead lead, WhatsAppReplyDTO reply) {
        String message = reply.getMessage();
        if (message == null) return;

        String clientId = reply.getClientId();
        String telephone = lead.getTelephoneLead();

        String header = LINK_PATTERN.matcher(message).find()
                ? "✅ *Клиент прислал ССЫЛКУ после оффера!*"
                : "\uD83D\uDD14 *Клиент ОТВЕТИЛ после оффера!*";

        String msg = """
            %s

            🆔*Телефон :* `%s`
            👤*Клиент:* `%s`

            📩 *Сообщение:*
            %s
            """.formatted(
                header,
                escapeMarkdown(clientId),
                escapeMarkdown(telephone),
                escapeMarkdown(message)
        );

        notificationService.sendAdminAlert(msg, adminChatIds); // асинхронно
        log.info("📨 Уведомление обрабатывается асинхронно для {}", telephone);
    }



    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("`", "\\`");
    }



    @Override
    public void processGroupReply(WhatsAppGroupReplyDTO reply) {
        log.info("👥 Ответ из группы '{}': от {} — {} id группы - {}", reply.getGroupName(), reply.getFrom(), reply.getMessage(), reply.getGroupId());

        Optional<Company> optCompany = companyService.findByGroupId(reply.getGroupId());

        if (optCompany.isEmpty()) {
            // ⛑ fallback — пробуем по номеру и названию группы
            String telephoneNumber = reply.getFrom().replaceAll("@c\\.us$", "");
            String rawName = reply.getGroupName();
            String title = rawName.contains(".") ? rawName.substring(0, rawName.indexOf(".")) : rawName;

            optCompany = companyService.getCompanyByTelephonAndTitle(telephoneNumber, title);

            if (optCompany.isEmpty()) {
                log.warn("❌ Не удалось найти компанию по номеру {} и названию '{}'", telephoneNumber, title);
                return;
            }

            Company found = optCompany.get();

            if (found.getGroupId() == null || found.getGroupId().isBlank()) {
                found.setGroupId(reply.getGroupId());
                companyService.save(found);
                log.info("📌 Привязали компанию '{}' к ID группы {}", found.getTitle(), reply.getGroupId());
            }
        }

//        Company company = optCompany.get();
        // здесь можно продолжить работу с компанией (например, сохранить ответ в историю)
    }

}



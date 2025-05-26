package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.model.Telephone;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.text_generator.alltext.service.clas.OfferTextService;
import com.hunt.otziv.text_generator.alltext.service.clas.RandomTextService;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import com.hunt.otziv.whatsapp.dto.WhatsAppReplyDTO;
import com.hunt.otziv.whatsapp.service.service.ReplyService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReplyServiceImpl implements ReplyService {

    private final CompanyService companyService;
    private final LeadService leadService;
    private final OfferService offerService;
    private final NotificationService notificationService;
    private final OfferTextService offerTextService;

    private List<String> offerList;

    @PostConstruct
    public void initOfferTexts() {
        this.offerList = offerTextService.findAllTexts();
        log.info("\n════════════════════════════════════════════════════\n📄 [INIT] Загружено {} офферов\n════════════════════════════════════════════════════", offerList.size());
    }

    @Override
    public void processIncomingReply(WhatsAppReplyDTO reply) {
        log.info("\n🟦 [REPLY] Входящий ответ от клиента\n├─ Клиент: {}\n├─ Телефон: {}\n└─ Сообщение: {}",
                reply.getClientId(), reply.getFrom(), reply.getMessage());

        String telephoneNumber = reply.getFrom().replaceAll("@c\\.us$", "");
        log.debug("🔍 Извлечён номер телефона: {}", telephoneNumber);

        Optional<Lead> leadOpt = leadService.getByTelephoneLead(telephoneNumber);
        if (leadOpt.isEmpty()) {
            log.warn("❌ Не найден лид по номеру {}", telephoneNumber);
            return;
        }

        Lead lead = leadOpt.get();

        if (!lead.isOffer()) {
            String messageText = reply.getMessage().toLowerCase();
            List<String> declineKeywords = List.of("нет", "не надо", "не нужно", "отстаньте", "не интересует", "не хочу", "спам", "хватит", "отпишитесь");

            if (declineKeywords.stream().anyMatch(messageText::contains)) {
                log.info("⛔ Клиент отказался: '{}'", messageText);
                return;
            }

            Telephone telephone = lead.getTelephone();
            if (telephone == null) {
                log.warn("⚠️ У лида {} нет привязанного телефона", lead.getId());
                return;
            }

            String clientId = "client" + telephone.getId();
            if (offerList == null || offerList.isEmpty()) {
                log.warn("⚠️ Список offerList пуст — оффер не будет отправлен");
                return;
            }

            String offerText = offerList.get(ThreadLocalRandom.current().nextInt(offerList.size()));
            offerService.sendOfferAsync(lead, clientId, telephoneNumber, offerText);
            log.info("📨 Оффер поставлен в очередь: клиент {}", telephoneNumber);
        } else {
            notifyAdminIfMessageContainsLink(lead, reply);
            log.info("✅ Оффер уже отправлялся ранее клиенту {}", telephoneNumber);
        }
    }

    private static final Pattern LINK_PATTERN = Pattern.compile("(https?://\\S+|www\\.\\S+|t\\.me/\\S+|vk\\.com/\\S+)", Pattern.CASE_INSENSITIVE);
    private final List<Long> adminChatIds = List.of(794146111L, 828987226L);

    private void notifyAdminIfMessageContainsLink(Lead lead, WhatsAppReplyDTO reply) {
        String message = reply.getMessage();
        if (message == null) return;

        String clientId = reply.getClientId();
        String telephone = lead.getTelephoneLead();

        String header = LINK_PATTERN.matcher(message).find()
                ? "✅ *Клиент прислал ССЫЛКУ после оффера!*"
                : "\uD83D\uDD14 *Клиент ОТВЕТИЛ после оффера!*";

        String msg = String.format("""
            %s

            🆔*Телефон :* `%s`
            👤*Клиент:* `%s`

            📩 *Сообщение:*
            %s
            """,
                header,
                escapeMarkdown(clientId),
                escapeMarkdown(telephone),
                escapeMarkdown(message));

        notificationService.sendAdminAlert(msg, adminChatIds);
        log.info("📢 Уведомление отправлено админам: {}", telephone);
    }

    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("`", "\\`")
                .replace("~", "\\~")
                .replace(">", "\\>");
    }

    @Override
    public void processGroupReply(WhatsAppGroupReplyDTO reply) {
        log.info("\n🟦 [GROUP REPLY] Группа '{}'\n├─ От: {}\n├─ Сообщение: {}\n└─ GroupId: {}",
                reply.getGroupName(), reply.getFrom(), reply.getMessage(), reply.getGroupId());

        Optional<Company> optCompany = companyService.findByGroupId(reply.getGroupId());

        if (optCompany.isEmpty()) {
            String telephoneNumber = reply.getFrom().replaceAll("@c\\.us$", "");
            String rawName = reply.getGroupName();
            String title = rawName.contains(".") ? rawName.substring(0, rawName.indexOf(".")) : rawName;

            optCompany = companyService.getCompanyByTelephonAndTitle(telephoneNumber, title);

            if (optCompany.isEmpty()) {
                log.warn("❌ Компания не найдена по номеру {} и названию '{}'", telephoneNumber, title);
                return;
            }

            Company found = optCompany.get();
            if (found.getGroupId() == null || found.getGroupId().isBlank()) {
                found.setGroupId(reply.getGroupId());
                companyService.save(found);
                log.info("📌 Компания '{}' привязана к GroupId {}", found.getTitle(), reply.getGroupId());
            }
        }
    }
}





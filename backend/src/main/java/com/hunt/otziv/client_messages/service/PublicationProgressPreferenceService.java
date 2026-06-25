package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class PublicationProgressPreferenceService {

    public static final String DISABLE_BUTTON_TEXT = "Отключить уведомления";
    public static final String ENABLE_BUTTON_TEXT = "Включить уведомления";

    private static final String CALLBACK_PREFIX = "publication_progress:";
    private static final String CALLBACK_DISABLE = CALLBACK_PREFIX + "disable:";
    private static final String CALLBACK_ENABLE = CALLBACK_PREFIX + "enable:";

    private final CompanyRepository companyRepository;

    public String appendPlainOptOutHint(String message) {
        return trimToEmpty(message)
                + "\n\nНе хотите получать сообщение о каждом опубликованном отзыве?"
                + "\nОтправьте команду: отключить уведомления";
    }

    public String appendTelegramOptOutHint(String message) {
        return trimToEmpty(message)
                + "\n\nНе хотите получать сообщение о каждом опубликованном отзыве?"
                + "\nНажмите кнопку ниже.";
    }

    public String disableCallbackData(Long companyId) {
        return CALLBACK_DISABLE + companyId;
    }

    public String enableCallbackData(Long companyId) {
        return CALLBACK_ENABLE + companyId;
    }

    public Optional<PreferenceUpdate> handleCallback(String callbackData) {
        if (!hasText(callbackData)) {
            return Optional.empty();
        }
        String normalized = callbackData.trim();
        if (normalized.startsWith(CALLBACK_DISABLE)) {
            return parseCompanyId(normalized.substring(CALLBACK_DISABLE.length()))
                    .flatMap(companyId -> setCompanyPreference(companyId, false));
        }
        if (normalized.startsWith(CALLBACK_ENABLE)) {
            return parseCompanyId(normalized.substring(CALLBACK_ENABLE.length()))
                    .flatMap(companyId -> setCompanyPreference(companyId, true));
        }
        return Optional.empty();
    }

    @Transactional
    public Optional<PreferenceUpdate> handleTelegramCommand(Long chatId, String messageText) {
        if (chatId == null || !isPreferenceCommand(messageText)) {
            return Optional.empty();
        }
        return setCompaniesPreference(
                companyRepository.findAllByTelegramGroupChatIdOrderById(chatId),
                isEnableCommand(normalizeCommand(messageText)),
                "Telegram chatId=" + chatId
        );
    }

    @Transactional
    public Optional<PreferenceUpdate> handleWhatsAppCommand(String groupId, String messageText) {
        if (!hasText(groupId) || !isPreferenceCommand(messageText)) {
            return Optional.empty();
        }
        List<Company> companies = companyRepository.findAllByGroupId(groupId);
        if (companies.isEmpty()) {
            return Optional.empty();
        }
        if (companies.size() > 1) {
            log.warn("Publication progress command applies to {} companies with WhatsApp groupId={}",
                    companies.size(), groupId);
        }
        boolean enabled = isEnableCommand(normalizeCommand(messageText));
        return setCompaniesPreference(companies, enabled, "WhatsApp groupId=" + groupId);
    }

    @Transactional
    public Optional<PreferenceUpdate> handleMaxCommand(Long chatId, String messageText) {
        if (chatId == null || !isPreferenceCommand(messageText)) {
            return Optional.empty();
        }
        return setCompaniesPreference(
                companyRepository.findAllByMaxGroupChatIdOrderById(chatId),
                isEnableCommand(normalizeCommand(messageText)),
                "MAX chatId=" + chatId
        );
    }

    public boolean isPreferenceCommand(String messageText) {
        String normalized = normalizeCommand(messageText);
        return isDisableCommand(normalized) || isEnableCommand(normalized);
    }

    private boolean isEnableCommand(String normalized) {
        return normalized.equals("включить уведомления")
                || normalized.equals("включить уведомления о публикациях")
                || normalized.equals("включить уведомления о публикации")
                || normalized.equals("включить оповещения")
                || normalized.equals("включить оповещения о публикациях")
                || normalized.equals("включить оповещения о публикации");
    }

    private boolean isDisableCommand(String normalized) {
        return normalized.equals("отключить уведомления")
                || normalized.equals("отключить уведомления о публикациях")
                || normalized.equals("отключить уведомления о публикации")
                || normalized.equals("отключить уведомления о каждой публикации")
                || normalized.equals("отключите уведомления")
                || normalized.equals("отключите уведомления о публикациях")
                || normalized.equals("отключите уведомления о публикации")
                || normalized.equals("отключите уведомления о каждой публикации")
                || normalized.equals("отключить оповещения")
                || normalized.equals("отключить оповещение")
                || normalized.equals("отключить оповещения о публикациях")
                || normalized.equals("отключить оповещения о публикации")
                || normalized.equals("отключить оповещения о каждой публикации")
                || normalized.equals("отключить оповещение о публикациях")
                || normalized.equals("отключить оповещение о публикации")
                || normalized.equals("отключить оповещение о каждой публикации")
                || normalized.equals("отключите оповещения")
                || normalized.equals("отключите оповещение")
                || normalized.equals("отключите оповещения о публикациях")
                || normalized.equals("отключите оповещения о публикации")
                || normalized.equals("отключите оповещения о каждой публикации")
                || normalized.equals("отключите оповещение о публикациях")
                || normalized.equals("отключите оповещение о публикации")
                || normalized.equals("отключите оповещение о каждой публикации")
                || normalized.equals("отключить их")
                || normalized.equals("отключите их")
                || normalized.equals("стоп уведомления")
                || normalized.equals("стоп оповещения")
                || normalized.equals("стоп оповещение")
                || normalized.equals(normalizeCommand(DISABLE_BUTTON_TEXT));
    }

    @Transactional
    public Optional<PreferenceUpdate> setCompanyPreference(Long companyId, boolean enabled) {
        if (companyId == null) {
            return Optional.empty();
        }
        return companyRepository.findById(companyId)
                .flatMap(company -> setCompanyPreference(company, enabled));
    }

    @Transactional
    protected Optional<PreferenceUpdate> setCompanyPreference(Company company, boolean enabled) {
        if (company == null) {
            return Optional.empty();
        }
        return setCompaniesPreference(preferenceScope(company), enabled, "company id=" + company.getId());
    }

    private Optional<PreferenceUpdate> setCompaniesPreference(List<Company> companies, boolean enabled, String source) {
        if (companies == null || companies.isEmpty()) {
            return Optional.empty();
        }

        if (companies.size() > 1) {
            log.info("Publication progress preference applies to {} companies from {}", companies.size(), source);
        }

        companies.forEach(company -> {
            company.setPublicationProgressReportsEnabled(enabled);
            companyRepository.save(company);
            log.info("Publication progress reports {} for company id={} title='{}'",
                    enabled ? "enabled" : "disabled", company.getId(), company.getTitle());
        });
        Company first = companies.getFirst();
        return Optional.of(new PreferenceUpdate(first.getId(), enabled, responseText(enabled)));
    }

    private List<Company> preferenceScope(Company company) {
        List<Company> companies = new ArrayList<>();
        addCompany(companies, company);

        if (hasText(company.getGroupId())) {
            addCompanies(companies, companyRepository.findAllByGroupId(company.getGroupId()));
        }
        if (company.getTelegramGroupChatId() != null) {
            addCompanies(companies, companyRepository.findAllByTelegramGroupChatIdOrderById(company.getTelegramGroupChatId()));
        }
        if (company.getMaxGroupChatId() != null) {
            addCompanies(companies, companyRepository.findAllByMaxGroupChatIdOrderById(company.getMaxGroupChatId()));
        }

        return companies;
    }

    private void addCompanies(List<Company> target, List<Company> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        source.forEach(company -> addCompany(target, company));
    }

    private void addCompany(List<Company> companies, Company company) {
        if (company == null) {
            return;
        }
        Long companyId = company.getId();
        boolean alreadyAdded = companies.stream()
                .anyMatch(existing -> existing == company
                        || (companyId != null && companyId.equals(existing.getId())));
        if (!alreadyAdded) {
            companies.add(company);
        }
    }

    private String responseText(boolean enabled) {
        if (enabled) {
            return "Оповещения о публикациях включены.";
        }
        return "Готово, уведомления о каждой публикации отключены."
                + "\n\nЧтобы включить снова, отправьте команду: включить уведомления";
    }

    private Optional<Long> parseCompanyId(String raw) {
        try {
            return Optional.of(Long.parseLong(raw.trim()));
        } catch (Exception e) {
            log.warn("Invalid publication progress callback company id '{}'", raw);
            return Optional.empty();
        }
    }

    private String normalizeCommand(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[\"'«».,!?:;]+", "")
                .replaceAll("\\s+", " ");
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public record PreferenceUpdate(Long companyId, boolean enabled, String message) {
    }
}

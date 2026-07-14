package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.u_users.model.Manager;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ClientChatCompanyResolutionService {

    private final CompanyRepository companyRepository;

    @Transactional(readOnly = true)
    public Resolution resolve(ClientChatPlatform platform, String chatId) {
        List<Company> companies = companies(platform, chatId);
        if (companies.isEmpty()) {
            return new Resolution(null, null, List.of(), false);
        }

        Company primary = companies.get(0);
        Manager firstManager = primary.getManager();
        Long firstManagerId = firstManager == null ? null : firstManager.getId();
        boolean commonManager = firstManagerId != null && companies.stream()
                .allMatch(company -> company.getManager() != null
                        && firstManagerId.equals(company.getManager().getId()));
        boolean ambiguous = companies.size() > 1 && !commonManager;
        return new Resolution(primary, commonManager || companies.size() == 1 ? firstManager : null, companies, ambiguous);
    }

    private List<Company> companies(ClientChatPlatform platform, String chatId) {
        if (platform == null || chatId == null || chatId.isBlank()) {
            return List.of();
        }
        return switch (platform) {
            case WHATSAPP -> companyRepository.findAllByGroupId(chatId.trim());
            case TELEGRAM -> parseLong(chatId)
                    .map(companyRepository::findAllByTelegramGroupChatIdOrderById)
                    .orElseGet(List::of);
            case MAX -> parseLong(chatId)
                    .map(companyRepository::findAllByMaxGroupChatIdOrderById)
                    .orElseGet(List::of);
        };
    }

    private java.util.Optional<Long> parseLong(String value) {
        try {
            return java.util.Optional.of(Long.parseLong(value.trim()));
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }

    public record Resolution(
            Company primaryCompany,
            Manager manager,
            List<Company> companies,
            boolean ambiguous
    ) {
        public int companyCount() {
            return companies == null ? 0 : companies.size();
        }

        public String companyTitles() {
            if (companies == null || companies.isEmpty()) {
                return null;
            }
            return companies.stream()
                    .map(Company::getTitle)
                    .filter(title -> title != null && !title.isBlank())
                    .distinct()
                    .limit(10)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse(null);
        }
    }
}

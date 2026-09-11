package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.c_companies.model.Company;

/** Read all routing fields while the entity is attached; provider code receives no JPA proxy. */
public record ManagerControlMessageCompany(Long id, String title, String urlChat, Long telegramGroupChatId, Long maxGroupChatId) {
    static ManagerControlMessageCompany capture(Company company) {
        return company == null ? null : new ManagerControlMessageCompany(company.getId(), company.getTitle(),
                company.getUrlChat(), company.getTelegramGroupChatId(), company.getMaxGroupChatId());
    }

    com.hunt.otziv.client_messages.api.ClientMessageDelivery.Target target() {
        return new com.hunt.otziv.client_messages.api.ClientMessageDelivery.Target(id, title, urlChat, telegramGroupChatId, maxGroupChatId);
    }

    Company toMessageCompany() {
        Company company = new Company();
        company.setId(id);
        company.setTitle(title);
        company.setUrlChat(urlChat);
        company.setTelegramGroupChatId(telegramGroupChatId);
        company.setMaxGroupChatId(maxGroupChatId);
        return company;
    }
}

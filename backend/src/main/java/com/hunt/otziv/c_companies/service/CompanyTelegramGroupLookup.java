package com.hunt.otziv.c_companies.service;

import com.hunt.otziv.c_companies.api.CompanyTelegramGroups;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompanyTelegramGroupLookup implements CompanyTelegramGroups {

    private final CompanyRepository companyRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean isCompanyGroup(long chatId) {
        return companyRepository.existsByTelegramGroupChatId(chatId);
    }
}

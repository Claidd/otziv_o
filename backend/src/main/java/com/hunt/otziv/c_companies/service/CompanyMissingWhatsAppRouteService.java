package com.hunt.otziv.c_companies.service;

import com.hunt.otziv.c_companies.api.MissingWhatsAppRoute;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompanyMissingWhatsAppRouteService implements MissingWhatsAppRoute {
    private final CompanyRepository companies;

    @Override @Transactional(readOnly = true)
    public Optional<Snapshot> find(Long companyId) {
        return companies.findByIdForCompanyDto(companyId).map(company -> new Snapshot(company.getId(), company.getUrlChat(),
                company.getGroupId(), CompanyChatBindingPolicy.isRequired(company)));
    }

    @Override
    public boolean restore(Snapshot expected, String groupId) {
        if (expected == null || !expected.bindingRequired() || groupId == null || !groupId.matches("[0-9]+@g\\.us")) return false;
        return companies.restoreMissingWhatsAppGroup(expected.companyId(), expected.invite(), groupId) == 1;
    }

    @Override
    public boolean refresh(Snapshot expected, String groupId) {
        if (expected == null || !expected.bindingRequired() || groupId == null || !groupId.matches("[0-9]+@g\\.us")) return false;
        return companies.refreshWhatsAppGroup(expected.companyId(), expected.invite(), expected.groupId(), groupId) == 1;
    }
}

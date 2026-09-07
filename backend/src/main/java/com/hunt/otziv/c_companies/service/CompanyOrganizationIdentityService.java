package com.hunt.otziv.c_companies.service;

import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.CompanyOrganizationIdentityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompanyOrganizationIdentityService {
    private final CompanyOrganizationIdentityRepository repository;
    private final TwoGisLinkResolver resolver;

    public String resolve(String url) {
        if (!TwoGisUrl.supports(url)) {
            return null; // Filials on other platforms retain their existing URL semantics.
        }
        var uri = TwoGisUrl.parse(url);
        var id = TwoGisUrl.cardId(uri);
        if (id.isPresent()) {
            return id.get();
        }
        String key = TwoGisUrl.shortKey(uri);
        String cached = repository.resolvedShortLink(key);
        if (cached != null) {
            return cached;
        }
        String resolved = resolver.resolve(key);
        repository.rememberShortLink(key, resolved);
        return resolved;
    }

    /** Must run within the same transaction as the filial save. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void prepareSave(Filial filial, boolean urlChanged) {
        if (urlChanged) {
            String id = resolve(filial.getUrl());
            if (id != null) {
                repository.lockOrganization(id);
                Long conflict = repository.conflictingFilial(id, filial.getId());
                if (conflict != null) {
                    throw new IllegalArgumentException("Эта карточка 2ГИС уже используется в филиале #" + conflict);
                }
            }
            filial.setTwoGisOrganizationId(id);
        }
        if (filial.getCompany() != null) {
            // Append-only: correcting a URL must not erase the company's previous history.
            repository.rememberCompany(filial.getCompany().getId(), filial.getTwoGisOrganizationId());
        }
    }
}

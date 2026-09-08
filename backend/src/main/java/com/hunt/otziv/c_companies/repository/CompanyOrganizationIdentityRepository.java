package com.hunt.otziv.c_companies.repository;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CompanyOrganizationIdentityRepository {
    private final JdbcTemplate jdbc;

    public String resolvedShortLink(String url) {
        return jdbc.query("SELECT organization_id FROM two_gis_url_identities WHERE source_url = ?",
                (rs, row) -> rs.getString(1), url).stream().findFirst().orElse(null);
    }

    public void rememberShortLink(String url, String id) {
        jdbc.update("INSERT IGNORE INTO two_gis_url_identities (source_url, organization_id) VALUES (?, ?)", url, id);
    }

    public void lockOrganization(String id) {
        jdbc.update("INSERT IGNORE INTO two_gis_organizations (organization_id) VALUES (?)", id);
        jdbc.queryForObject("SELECT organization_id FROM two_gis_organizations WHERE organization_id = ? FOR UPDATE",
                String.class, id);
    }

    public void rememberCompany(Long companyId, String id) {
        if (companyId != null && id != null) {
            jdbc.update("INSERT IGNORE INTO company_two_gis_identities (company_id, organization_id) VALUES (?, ?)",
                    companyId, id);
        }
    }

    public Long conflictingFilial(String id, Long excludedId) {
        // A current read after the organization lock also sees a concurrent committed creation
        // under MySQL REPEATABLE READ. Archived filials still count as existing cards.
        return jdbc.query("""
                SELECT filial_id FROM filial WHERE two_gis_organization_id = ?
                  AND (? IS NULL OR filial_id <> ?) ORDER BY filial_id LIMIT 1 FOR SHARE
                """, (rs, row) -> rs.getLong(1), id, excludedId, excludedId).stream().findFirst().orElse(null);
    }

    public Set<Long> relatedCompanyIds(Long companyId) {
        return new HashSet<>(jdbc.query("""
                WITH RECURSIVE related(company_id) AS (
                    SELECT CAST(? AS SIGNED)
                    UNION DISTINCT
                    SELECT other.company_id FROM related r
                    JOIN company_two_gis_identities own ON own.company_id = r.company_id
                    JOIN company_two_gis_identities other ON other.organization_id = own.organization_id
                ) SELECT company_id FROM related
                """, (rs, row) -> rs.getLong(1), companyId));
    }

}

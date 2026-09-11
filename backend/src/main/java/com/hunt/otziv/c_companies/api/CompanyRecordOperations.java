package com.hunt.otziv.c_companies.api;

import com.hunt.otziv.c_companies.model.Company;

/** Company-owned record operations used inside order/payment application transactions. */
public interface CompanyRecordOperations {
    Company getCompaniesById(Long id);
    void save(Company company);
}

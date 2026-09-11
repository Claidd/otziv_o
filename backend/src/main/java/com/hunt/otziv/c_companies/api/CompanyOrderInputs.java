package com.hunt.otziv.c_companies.api;

import com.hunt.otziv.c_companies.dto.CompanyDTO;

/** Company-owned, read-only input for the new-order form. No persistence or mutation methods. */
public interface CompanyOrderInputs {
    CompanyDTO getCompaniesDTOById(Long id);
}

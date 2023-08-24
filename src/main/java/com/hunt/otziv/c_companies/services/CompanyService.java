package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.model.Company;

import java.security.Principal;

public interface CompanyService {

    CompanyDTO convertToDtoToManager(Long leadId, Principal principal);
}

package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.u_users.model.Manager;

public interface CompanyStatusService {

    // Взять менеджера по id его юзера
    CompanyStatus getCompanyStatusById (Long id);


}

package com.hunt.otziv.l_lead.services;


import com.hunt.otziv.l_lead.model.Telephone;

public interface TelephoneService {
    Telephone saveTelephone(Telephone telephone);
    Telephone getTelephoneById(Long telephoneId);
}

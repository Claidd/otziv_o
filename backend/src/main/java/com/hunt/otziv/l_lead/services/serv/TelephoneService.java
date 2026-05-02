package com.hunt.otziv.l_lead.services.serv;


import com.hunt.otziv.l_lead.dto.TelephoneDTO;
import com.hunt.otziv.l_lead.model.Telephone;

import java.util.List;

public interface TelephoneService {
    Telephone saveTelephone(Telephone telephone);
    Telephone getTelephoneById(Long telephoneId);

    List<TelephoneDTO> getAllTelephones();

    TelephoneDTO getTelephoneDTOById(Long phoneId);

    void updatePhone(Long phoneId, TelephoneDTO dto);

    void createTelephone(TelephoneDTO dto);

    TelephoneDTO createEmptyDTO();

    void deletePhone(Long phoneId);
}

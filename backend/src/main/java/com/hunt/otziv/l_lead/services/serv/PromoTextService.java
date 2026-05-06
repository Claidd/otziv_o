package com.hunt.otziv.l_lead.services.serv;

import java.util.List;


public interface PromoTextService {
    List<String> getAllPromoTexts();

    List<String> getPromoTextsForManager(Long managerId, String sectionCode);

    String findById(int l);
}

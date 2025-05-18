package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.l_lead.model.PromoText;
import com.hunt.otziv.l_lead.repository.PromoTextRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.method.P;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class PromoTextServiceImpl implements PromoTextService{

    private final PromoTextRepository promoTextRepository;

    public PromoTextServiceImpl(PromoTextRepository promoTextRepository) {
        this.promoTextRepository = promoTextRepository;
    }

    @Override
    public List<String> getAllPromoTexts() { // Взять все текста
        List<String> result = new ArrayList<>();
        List<PromoText> textBD =  promoTextRepository.findAll();
        for ( PromoText textList : textBD) {
            result.add( textList.getPromoText().replace("lineSep", System.lineSeparator()));
        }
        return result;
    } // Взять все текста

    @Override
    public String findById(int id) {
        String text;
        Optional<PromoText> textOpl = promoTextRepository.findById(id);
        if (textOpl.isEmpty()) {
            return null;
        }
        else {
            text = textOpl.get().getPromoText().replace("lineSep", System.lineSeparator());
        }
        return text;
    }
}

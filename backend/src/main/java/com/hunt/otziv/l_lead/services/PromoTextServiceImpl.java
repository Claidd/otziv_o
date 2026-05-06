package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.l_lead.model.PromoText;
import com.hunt.otziv.l_lead.model.PromoTextAssignment;
import com.hunt.otziv.l_lead.promo.PromoButtonCatalog;
import com.hunt.otziv.l_lead.promo.PromoButtonCatalog.Slot;
import com.hunt.otziv.l_lead.repository.PromoTextAssignmentRepository;
import com.hunt.otziv.l_lead.repository.PromoTextRepository;
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.config.cache.CacheConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class PromoTextServiceImpl implements PromoTextService {

    private final PromoTextRepository promoTextRepository;
    private final PromoTextAssignmentRepository promoTextAssignmentRepository;

    public PromoTextServiceImpl(
            PromoTextRepository promoTextRepository,
            PromoTextAssignmentRepository promoTextAssignmentRepository
    ) {
        this.promoTextRepository = promoTextRepository;
        this.promoTextAssignmentRepository = promoTextAssignmentRepository;
    }

    @Override
    @Cacheable(CacheConfig.PROMO_TEXTS)
    public List<String> getAllPromoTexts() { // Взять все текста
        List<String> result = new ArrayList<>();
        List<PromoText> textBD =  promoTextRepository.findAllByOrderByIdAsc();
        for ( PromoText textList : textBD) {
            result.add(toDisplayPromoText(textList.getPromoText()));
        }
        return result;
    } // Взять все текста

    @Override
    public List<String> getPromoTextsForManager(Long managerId, String sectionCode) {
        List<String> result = new ArrayList<>(getAllPromoTexts());
        if (managerId == null || sectionCode == null || sectionCode.isBlank()) {
            return result;
        }

        List<PromoTextAssignment> assignments = promoTextAssignmentRepository.findAllByManagerIdWithText(managerId);
        for (PromoTextAssignment assignment : assignments) {
            PromoButtonCatalog.find(assignment.getSectionCode(), assignment.getButtonKey())
                    .filter(slot -> slot.sectionCode().equals(sectionCode))
                    .ifPresent(slot -> applyAssignment(result, slot, assignment.getPromoText()));
        }

        return result;
    }

    @Override
    public String findById(int id) {
        String text;
        Optional<PromoText> textOpl = promoTextRepository.findById((long) id);
        if (textOpl.isEmpty()) {
            return null;
        }
        else {
            text = textOpl.get().getPromoText().replace("lineSep", System.lineSeparator());
        }
        return text;
    }

    private void applyAssignment(List<String> result, Slot slot, PromoText promoText) {
        if (promoText == null) {
            return;
        }

        while (result.size() <= slot.outputIndex()) {
            result.add("");
        }
        result.set(slot.outputIndex(), toDisplayPromoText(promoText.getPromoText()));
    }

    private String toDisplayPromoText(String value) {
        return value == null ? "" : value.replace("lineSep", System.lineSeparator());
    }
}

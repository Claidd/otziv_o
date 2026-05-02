package com.hunt.otziv.text_generator.alltext.service;

import com.hunt.otziv.text_generator.alltext.model.HelloText;
import com.hunt.otziv.text_generator.alltext.model.OfferText;
import com.hunt.otziv.text_generator.alltext.repository.OfferTextRepository;
import com.hunt.otziv.text_generator.alltext.service.clas.OfferTextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class OfferTextServiceImpl implements OfferTextService {

    private final OfferTextRepository offerTextRepository;

    public List<String> findAllTexts() {
        return offerTextRepository.findAll().stream()
                .map(OfferText::getText)
                .collect(Collectors.toList());
    }
}

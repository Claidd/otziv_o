package com.hunt.otziv.text_generator.alltext.service;

import com.hunt.otziv.text_generator.alltext.model.HelloText;
import com.hunt.otziv.text_generator.alltext.model.OfferText;
import com.hunt.otziv.text_generator.alltext.model.RandomText;
import com.hunt.otziv.text_generator.alltext.repository.OfferTextRepository;
import com.hunt.otziv.text_generator.alltext.repository.RandomTextRepository;
import com.hunt.otziv.text_generator.alltext.service.clas.RandomTextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RandomTextServiceImpl implements RandomTextService {

    private final RandomTextRepository randomTextRepository;

    public List<String> findAllTexts() {
        return randomTextRepository.findAll().stream()
                .map(RandomText::getText)
                .collect(Collectors.toList());
    }
}

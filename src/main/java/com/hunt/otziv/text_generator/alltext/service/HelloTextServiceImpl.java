package com.hunt.otziv.text_generator.alltext.service;

import com.hunt.otziv.text_generator.alltext.model.HelloText;
import com.hunt.otziv.text_generator.alltext.repository.HelloTextRepository;
import com.hunt.otziv.text_generator.alltext.service.clas.HelloTextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class HelloTextServiceImpl implements HelloTextService {

    private final HelloTextRepository helloTextRepository;

    public List<String> findAllTexts() {
        return helloTextRepository.findAll().stream()
                .map(HelloText::getText)
                .collect(Collectors.toList());
    }
}

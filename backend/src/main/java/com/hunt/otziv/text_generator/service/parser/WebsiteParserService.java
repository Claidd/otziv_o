package com.hunt.otziv.text_generator.service.parser;

import org.springframework.context.annotation.ComponentScan;

@ComponentScan
public interface WebsiteParserService {

    String extractTextFromWebsite(String url);
}

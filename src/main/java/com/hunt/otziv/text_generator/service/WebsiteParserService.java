package com.hunt.otziv.text_generator.service;

import org.springframework.context.annotation.ComponentScan;

@ComponentScan
public interface WebsiteParserService {

    String extractTextFromWebsite(String url);
}

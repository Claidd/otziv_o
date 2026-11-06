package com.hunt.otziv.text_generator.service.gpt;

import com.hunt.otziv.text_generator.dto.PromptDTO;


public interface ReviewGeneratorService {

    String safeGenerateSingleReview(PromptDTO promptDTO);

}

package com.hunt.otziv.text_generator.service;

import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_categories.service.CategoryService;
import com.hunt.otziv.c_categories.service.SubCategoryService;
import com.hunt.otziv.c_companies.service.FilialService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.bot.service.ReviewAccountWalkScheduleService;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.p_products.service.BotAssignmentService;
import com.hunt.otziv.text_generator.config.PromptFactory;
import com.hunt.otziv.text_generator.dto.PromptDTO;
import com.hunt.otziv.text_generator.service.config.ReviewGenerationManager;
import com.hunt.otziv.text_generator.service.parser.WebsiteParserService;
import com.hunt.otziv.text_generator.service.gpt.ReviewGeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoTextServiceImplTest {

    @Mock
    private ReviewService reviewService;

    @Mock
    private ReviewGeneratorService reviewGeneratorService;

    @Mock
    private BotAssignmentService botAssignmentService;

    @Mock
    private CategoryService categoryService;

    @Mock
    private SubCategoryService subCategoryService;

    @Mock
    private FilialService filialService;

    @Mock
    private ReviewGenerationManager reviewGenerationManager;

    @Mock
    private PromptFactory promptFactory;

    @Mock
    private WebsiteParserService websiteParserService;

    @Mock
    private ReviewAccountWalkScheduleService accountWalkScheduleService;

    private AutoTextServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AutoTextServiceImpl(
                reviewService,
                reviewGeneratorService,
                botAssignmentService,
                categoryService,
                subCategoryService,
                filialService,
                reviewGenerationManager,
                promptFactory,
                websiteParserService,
                accountWalkScheduleService
        );
    }

    @Test
    void changeReviewTextDoesNotSaveGeneratorErrorText() {
        Review review = review("старый текст");
        when(reviewService.getReviewById(10L)).thenReturn(review);
        when(promptFactory.generatePrompt(anyString(), eq("Ремонт"))).thenReturn(prompt());
        when(reviewGeneratorService.safeGenerateSingleReview(any(PromptDTO.class)))
                .thenReturn("⚠️ Ошибка при генерации отзыва.");

        boolean changed = service.changeReviewText(10L);

        assertFalse(changed);
        assertEquals("старый текст", review.getText());
        verify(reviewGeneratorService, times(3)).safeGenerateSingleReview(any(PromptDTO.class));
        verify(reviewService, never()).save(any(Review.class));
    }

    @Test
    void changeReviewTextRetriesAfterGeneratorErrorText() {
        Review review = review("старый текст");
        when(reviewService.getReviewById(11L)).thenReturn(review);
        when(promptFactory.generatePrompt(anyString(), eq("Ремонт"))).thenReturn(prompt());
        when(reviewGeneratorService.safeGenerateSingleReview(any(PromptDTO.class)))
                .thenReturn("⚠️ Ошибка при генерации отзыва.")
                .thenReturn("Новый живой текст отзыва");
        when(reviewService.save(review)).thenReturn(review);

        boolean changed = service.changeReviewText(11L);

        assertTrue(changed);
        assertEquals("Новый живой текст отзыва", review.getText());
        verify(reviewGeneratorService, times(2)).safeGenerateSingleReview(any(PromptDTO.class));
        verify(reviewService).save(review);
    }

    private static Review review(String text) {
        Review review = new Review();
        review.setId(10L);
        review.setText(text);
        review.setSubCategory(SubCategory.builder().subCategoryTitle("Ремонт").build());
        return review;
    }

    private static PromptDTO prompt() {
        return PromptDTO.builder()
                .system("system")
                .prompt("prompt")
                .temperature(0.7)
                .build();
    }
}

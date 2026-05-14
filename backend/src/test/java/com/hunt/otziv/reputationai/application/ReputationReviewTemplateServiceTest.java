package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewTemplatesApplyRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewTemplatesRequest;
import com.hunt.otziv.reputationai.domain.CompanyAiProfile;
import com.hunt.otziv.reputationai.domain.CompanySource;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ReputationReviewTemplatesResult;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import com.hunt.otziv.reputationai.persistence.ReputationContentPackJobEntity;
import com.hunt.otziv.reputationai.persistence.ReputationContentPackJobRepository;
import com.hunt.otziv.reputationai.persistence.ReputationDeepReportJobEntity;
import com.hunt.otziv.reputationai.persistence.ReputationDeepReportJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReputationReviewTemplateServiceTest {

    @Mock
    private ReputationContentPackJobRepository contentPackJobRepository;

    @Mock
    private ReputationDeepReportJobRepository deepReportJobRepository;

    @Mock
    private AiReviewTemplateFactory aiReviewTemplateFactory;

    private ObjectMapper objectMapper;
    private ReputationReviewTemplateService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new ReputationReviewTemplateService(
                contentPackJobRepository,
                deepReportJobRepository,
                aiReviewTemplateFactory,
                objectMapper
        );
    }

    @Test
    void generatesLocalFallbackWhenAiDoesNotReturnReviewTemplates() {
        ReputationContentPack pack = pack();
        ReputationContentPackJobEntity packEntity = contentPackEntity(pack);
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(report());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.of(packEntity));
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiReviewTemplateFactory.create(any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        ReputationReviewTemplatesResult result = service.generate(
                7L,
                new ReputationReviewTemplatesRequest(null, null, "больше про дни рождения", 4, 3, null, null)
        );

        assertThat(result.provider()).isEqualTo("local");
        assertThat(result.companyName()).isEqualTo("Iquest");
        assertThat(result.honestReviewTopics()).hasSize(4);
        assertThat(result.reviewDraftTemplates()).hasSize(3);
        assertThat(result.reviewDraftTemplates()).allMatch(draft -> draft.contains("Iquest"));
        assertThat(result.safetyNotes()).anyMatch(note -> note.contains("реальный личный опыт"));
    }

    @Test
    void appliesImprovedReviewTemplatesToSavedContentPack() throws JsonProcessingException {
        ReputationContentPack pack = pack();
        ReputationContentPackJobEntity packEntity = contentPackEntity(pack);
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.of(packEntity));
        when(contentPackJobRepository.save(packEntity)).thenReturn(packEntity);

        ReputationContentPack updated = service.apply(
                7L,
                new ReputationReviewTemplatesApplyRequest(
                        null,
                        List.of("Новый угол про пакет дня рождения"),
                        List.of("Новый черновик с УТП и личной вставкой")
                )
        );

        assertThat(updated.honestReviewTopics()).containsExactly("Новый угол про пакет дня рождения");
        assertThat(updated.reviewDraftTemplates()).containsExactly("Новый черновик с УТП и личной вставкой");
        assertThat(updated.safetyNotes()).anyMatch(note -> note.contains("улучшен отдельной AI-генерацией"));

        ReputationContentPack persisted = objectMapper.readValue(packEntity.getPackJson(), ReputationContentPack.class);
        assertThat(persisted.honestReviewTopics()).containsExactly("Новый угол про пакет дня рождения");
        assertThat(persisted.reviewDraftTemplates()).containsExactly("Новый черновик с УТП и личной вставкой");
        verify(contentPackJobRepository).save(packEntity);
    }

    private ReputationContentPackJobEntity contentPackEntity(ReputationContentPack pack) {
        ReputationContentPackJobEntity entity = new ReputationContentPackJobEntity();
        entity.setCompanyId(7L);
        entity.setCompanyTitle("Iquest");
        entity.setProvider("openai");
        entity.setModel("gpt-5.5");
        entity.setPackJson(write(pack));
        return entity;
    }

    private ReputationDeepReportJobEntity deepReportEntity(DeepCompanyResearchReport report) {
        ReputationDeepReportJobEntity entity = new ReputationDeepReportJobEntity();
        entity.setCompanyId(7L);
        entity.setCompanyTitle("Iquest");
        entity.setProvider("openai");
        entity.setModel("gpt-5.5");
        entity.setReportJson(write(report));
        return entity;
    }

    private ReputationContentPack pack() {
        ResearchSnapshot snapshot = new ResearchSnapshot(
                7L,
                "Iquest",
                "Ангарск",
                "https://iquest.example",
                "Детские праздники",
                "Квесты",
                "Есть филиалы и чайная зона",
                List.of("пакет дня рождения", "лазертаг"),
                List.of("чайная зона", "активные игры"),
                List.of("эмоции детей"),
                List.of("уточнение цены"),
                List.of(),
                List.of(new CompanySource("website", "Сайт", "https://iquest.example", "Пакеты и программы")),
                "local",
                false,
                List.of(),
                0,
                1,
                List.of(),
                LocalDateTime.now()
        );
        CompanyAiProfile profile = new CompanyAiProfile(
                "Центр детских праздников",
                "Развлечения",
                List.of("пакет дня рождения", "лазертаг"),
                List.of("чайная зона", "фото"),
                List.of("детям понравились активности"),
                List.of("нужно уточнять цены"),
                List.of("условия могут меняться")
        );
        return new ReputationContentPack(
                snapshot,
                profile,
                List.of(
                        "Пакет дня рождения объединяет квест, чайную зону и активные игры в одном сценарии.",
                        "Лазертаг и NERF помогают выбрать программу под возраст и уровень активности."
                ),
                List.of("День рождения без отдельного кафе: программа, чайная зона и фото в одном месте."),
                List.of("Как выбрать программу для детского дня рождения"),
                List.of("Перед бронированием важно уточнить возраст, уровень страха и что входит в пакет."),
                List.of("Спросить, какая программа подошла ребенку и что помогло выбрать."),
                List.of("Выбрали Iquest для дня рождения, потому что [личная причина]."),
                List.of("Спасибо за отзыв!"),
                List.of("Жаль, что возникли вопросы."),
                List.of("https://iquest.example"),
                List.of("Старые цены нужно перепроверить.")
        );
    }

    private DeepCompanyResearchReport report() {
        return new DeepCompanyResearchReport(
                7L,
                "Iquest",
                "Ангарск",
                "openai",
                "gpt-5.5",
                "resp-1",
                "В отчете есть пакеты дня рождения, чайная зона, адреса и ограничения.",
                List.of(new DeepCompanyResearchReport.Section(
                        "Что еще собирать",
                        "Уточнить парковку, вход, актуальные цены и состав пакетов."
                )),
                List.of(new DeepCompanyResearchReport.Source("Сайт", "https://iquest.example", "Пакеты")),
                List.of("Парковка не подтверждена."),
                List.of(),
                DeepCompanyResearchReport.FactSnapshot.empty(),
                LocalDateTime.now()
        );
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

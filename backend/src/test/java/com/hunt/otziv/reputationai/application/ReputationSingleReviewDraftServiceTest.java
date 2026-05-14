package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.api.dto.ReputationSingleReviewDraftRequest;
import com.hunt.otziv.reputationai.domain.CompanyAiProfile;
import com.hunt.otziv.reputationai.domain.CompanySource;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ReputationSingleReviewDraftResult;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReputationSingleReviewDraftServiceTest {

    @Mock
    private ReputationContentPackJobRepository contentPackJobRepository;

    @Mock
    private ReputationDeepReportJobRepository deepReportJobRepository;

    @Mock
    private AiSingleReviewDraftFactory aiSingleReviewDraftFactory;

    private ObjectMapper objectMapper;
    private ReputationSingleReviewDraftService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        ReviewSafetyService reviewSafetyService = new ReviewSafetyService();
        service = new ReputationSingleReviewDraftService(
                contentPackJobRepository,
                deepReportJobRepository,
                aiSingleReviewDraftFactory,
                reviewSafetyService,
                objectMapper
        );
    }

    @Test
    void generatesLocalSingleDraftFromPackAndReportWhenAiDoesNotReturnDraft() {
        ReputationContentPack pack = pack();
        ReputationContentPackJobEntity packEntity = contentPackEntity(pack);
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(report());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.of(packEntity));
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.create(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        ReputationSingleReviewDraftResult result = service.generate(
                7L,
                new ReputationSingleReviewDraftRequest(
                        null,
                        null,
                        "Пакет дня рождения с чайной зоной",
                        "живой, спокойный",
                        "без цен",
                        "medium",
                        "quality"
                )
        );

        assertThat(result.provider()).isEqualTo("local");
        assertThat(result.companyName()).isEqualTo("Iquest");
        assertThat(result.idea()).isEqualTo("Пакет дня рождения с чайной зоной");
        assertThat(result.draft()).contains("Iquest", "Пакет дня рождения");
        assertThat(result.sourceFacts()).isNotEmpty();
        assertThat(result.safetyNotes()).anyMatch(note -> note.contains("реальный опыт"));
        assertThat(result.safetyReport()).isNotNull();
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
                List.of("Пакет дня рождения объединяет квест, чайную зону и активные игры в одном сценарии."),
                List.of("День рождения без отдельного кафе: программа, чайная зона и фото в одном месте."),
                List.of("Как выбрать программу для детского дня рождения"),
                List.of("Перед бронированием важно уточнить возраст, уровень страха и что входит в пакет."),
                List.of("Пакет дня рождения: что помогло выбрать и какой личный штрих добавить."),
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
                        "Сценарии и УТП",
                        "Пакеты дня рождения объединяют квест, активную игру и чайную зону."
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

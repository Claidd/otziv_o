package com.hunt.otziv.reputationai.application.service;

import com.hunt.otziv.reputationai.application.model.PageRole;
import com.hunt.otziv.reputationai.application.model.PageRoleContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.search.dto.SearchResult;
import com.hunt.otziv.reputationai.infrastructure.search.service.SearchProviderRouter;
import com.hunt.otziv.reputationai.infrastructure.web.service.WebsiteCrawler;
import com.hunt.otziv.reputationai.persistence.repository.ReputationResearchSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompanyResearchServiceHybridSearchTest {

    private final PageRoleClassifier pageRoleClassifier = mock(PageRoleClassifier.class);
    private final CompanyResearchService service = new CompanyResearchService(
            mock(CompanyService.class),
            mock(WebsiteCrawler.class),
            mock(SearchProviderRouter.class),
            new ReputationAiProperties(),
            mock(ReputationResearchSnapshotRepository.class),
            new ObjectMapper(),
            pageRoleClassifier
    );

    @Test
    void requiresLocationOrBusinessSignalForShortAmbiguousCompanyName() {
        when(pageRoleClassifier.classify(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(PageRoleContext.class)))
                .thenReturn(PageRole.COMPANY_PROFILE);
        Company company = Company.builder().title("ВСМ").city("Благовещенск").build();
        PageRoleContext context = new PageRoleContext("ВСМ", "Благовещенск", "", "", "");

        boolean unrelated = ReflectionTestUtils.invokeMethod(
                service,
                "isRelevantSearchResult",
                new SearchResult("ВСМ", "https://2gis.ru/moscow/firm/1", "Компания ВСМ в Москве", "yandex"),
                company,
                List.of("спецтехника"),
                context
        );
        boolean local = ReflectionTestUtils.invokeMethod(
                service,
                "isRelevantSearchResult",
                new SearchResult("ВСМ", "https://2gis.ru/blagoveshchensk/firm/2", "ВСМ, Благовещенск", "yandex"),
                company,
                List.of("спецтехника"),
                context
        );

        assertThat(unrelated).isFalse();
        assertThat(local).isTrue();
    }

    @Test
    void acceptsGenericBusinessOffersWithoutQuestSpecificFallbacks() {
        boolean equipmentOffer = ReflectionTestUtils.invokeMethod(service, "isBusinessOfferName", "Продажа спецтехники");
        boolean legalPage = ReflectionTestUtils.invokeMethod(service, "isBusinessOfferName", "Политика конфиденциальности");
        List<String> topics = ReflectionTestUtils.invokeMethod(
                service,
                "buildPositiveTopics",
                List.of("спецтехника"),
                "Консультация, доставка и гарантийная поддержка"
        );

        assertThat(equipmentOffer).isTrue();
        assertThat(legalPage).isFalse();
        assertThat(topics)
                .contains("выбор спецтехника", "консультация и понятные объяснения", "условия и удобство доставки", "гарантийная поддержка")
                .doesNotContain("выбранная программа или квест", "организация праздника");
    }

    @Test
    void usesKnownTwoGisCardInsteadOfSearchingAndCrawlingOtherCards() {
        when(pageRoleClassifier.classify(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(PageRoleContext.class)))
                .thenReturn(PageRole.COMPANY_PROFILE);
        City city = City.builder().title("Саратов").build();
        Filial filial = Filial.builder()
                .title("ул. Московская, 10")
                .city(city)
                .url("https://2gis.ru/saratov/firm/123")
                .build();
        Company company = Company.builder()
                .title("Таксопарк")
                .city("Саратов")
                .filial(Set.of(filial))
                .build();
        PageRoleContext context = new PageRoleContext("Таксопарк", "Саратов", "", "", "");

        List<String> queries = ReflectionTestUtils.invokeMethod(
                service, "buildSearchQueries", company, List.of("такси"), Set.of("2gis"));
        boolean otherCard = ReflectionTestUtils.invokeMethod(
                service,
                "isRelevantSearchResult",
                new SearchResult("Таксопарк", "https://2gis.ru/ufa/firm/999", "Таксопарк в Уфе", "yandex"),
                company,
                List.of("такси"),
                context
        );
        boolean exactAddress = ReflectionTestUtils.invokeMethod(
                service,
                "isRelevantSearchResult",
                new SearchResult("Таксопарк", "https://2gis.ru/saratov/firm/123", "Саратов, ул. Московская, 10", "yandex"),
                company,
                List.of("такси"),
                context
        );
        String platform = ReflectionTestUtils.invokeMethod(service, "mapPlatform", filial.getUrl());

        assertThat(queries)
                .contains("Таксопарк ул. Московская, 10")
                .noneMatch(query -> query.contains("site:2gis.ru") || query.endsWith(" 2ГИС"));
        assertThat(otherCard).isFalse();
        assertThat(exactAddress).isTrue();
        assertThat(platform).isEqualTo("2gis");
    }
}

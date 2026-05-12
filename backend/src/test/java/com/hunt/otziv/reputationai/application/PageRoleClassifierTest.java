package com.hunt.otziv.reputationai.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageRoleClassifierTest {

    private final PageRoleClassifier classifier = new PageRoleClassifier();
    private final PageRoleContext context = new PageRoleContext(
            "Iquest",
            "Ангарск",
            "https://naigru.ru",
            "Дети",
            "Квест"
    );

    @Test
    void detectsOfficialSiteByConfiguredHost() {
        assertThat(classifier.classify(
                "https://naigru.ru/psycho.html",
                "Психоз",
                "Квест с актерами, 14+, 60 минут",
                context
        )).isEqualTo(PageRole.OFFICIAL_SITE);
    }

    @Test
    void detectsServiceAndLegalPagesUniversally() {
        assertThat(classifier.classify(
                "https://example.ru/legal/privacy",
                "Политика конфиденциальности",
                "Персональные данные и условия использования сервиса",
                context
        )).isEqualTo(PageRole.SERVICE_OR_LEGAL);

        assertThat(classifier.classify(
                "https://www.iq-iquest.com/laser.html",
                "404 Page not found. Страница не найдена",
                "Страница не существует",
                context
        )).isEqualTo(PageRole.SERVICE_OR_LEGAL);
    }

    @Test
    void detectsReviewAndCompanyProfilePages() {
        assertThat(classifier.classify(
                "https://yandex.ru/maps/org/ay_kvest/17092564278/reviews/",
                "IQuest: 166 reviews",
                "Отзывы, рейтинг 5.0, Ангарск",
                context
        )).isEqualTo(PageRole.REVIEW_PAGE);

        assertThat(classifier.classify(
                "https://angarsk.example-directory.ru/companies/iquest-angarsk",
                "Iquest Ангарск",
                "Адрес, телефон, отзывы компании",
                context
        )).isEqualTo(PageRole.COMPANY_PROFILE);
    }

    @Test
    void DetectsGenericCatalogPagesWithoutDomainSpecificRules() {
        assertThat(classifier.classify(
                "https://angarsk.example-directory.ru/quests",
                "Все квесты в Ангарске",
                "Каталог, подборка, рейтинг и сравнение компаний",
                context
        )).isEqualTo(PageRole.CATALOG_LISTING);

        assertThat(classifier.classify(
                "https://angarsk.example-directory.ru/categories/quests-18",
                "Квесты 18+ в Ангарске",
                "Лучшие компании и рейтинг",
                context
        )).isEqualTo(PageRole.CATALOG_LISTING);
    }

    @Test
    void keepsSpecificPublicPagesAwayFromGenericCatalogRole() {
        assertThat(classifier.classify(
                "https://angarsk.example-directory.ru/quests/psycho",
                "Психоз от Iquest",
                "Описание квеста, отзывы и рейтинг",
                context
        )).isEqualTo(PageRole.REVIEW_PAGE);
    }

    @Test
    void competitorProfilesDoNotBecomeCompanyProfilesWithoutCompanyMatch() {
        assertThat(classifier.classify(
                "https://angarsk.example-directory.ru/companies/stalker-angarsk",
                "Квест Сталкер Ангарск",
                "Адрес, телефон, отзывы, рейтинг, квесты в Ангарске",
                context
        )).isEqualTo(PageRole.COMPETITOR_LISTING);
    }

    @Test
    void companyProfileCanBeDetectedOnUnknownHostByNameMatch() {
        assertThat(classifier.classify(
                "https://www.iq-iquest.com/stalker.html",
                "Квест Сталкер Ангарск",
                "КВЕСТ ЦЕНТР Iquest, телефон, адрес, цена 1500 руб.",
                context
        )).isEqualTo(PageRole.COMPANY_PROFILE);
    }
}

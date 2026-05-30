package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.reputationai.api.dto.ReputationResearchRequest;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.domain.CompanyResearchAnswer;
import com.hunt.otziv.reputationai.domain.CompanySource;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import com.hunt.otziv.reputationai.infrastructure.search.SearchProviderRouter;
import com.hunt.otziv.reputationai.infrastructure.search.SearchQuery;
import com.hunt.otziv.reputationai.infrastructure.search.SearchResult;
import com.hunt.otziv.reputationai.infrastructure.web.CrawledPage;
import com.hunt.otziv.reputationai.infrastructure.web.WebsiteCrawler;
import com.hunt.otziv.reputationai.persistence.ReputationResearchSnapshotEntity;
import com.hunt.otziv.reputationai.persistence.ReputationResearchSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CompanyResearchService {

    private static final Pattern QUOTED_NAME_PATTERN = Pattern.compile("[«\"]([^»\"]{2,60})[»\"]");
    private static final Pattern PRICE_PATTERN = Pattern.compile("(?iu)(?:от\\s*)?\\d[\\d\\s]{1,7}\\s*(?:₽|руб\\.?|р/ч|р\\b)");
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("(?iu)(?:г\\.?\\s*)?(?:Иркутск|Ангарск)?\\s*,?\\s*(?:ул\\.?|улица|проспект|пр-т|переулок|пер\\.?|мкр\\.?|микрорайон|шоссе)\\s+[А-ЯЁA-Z0-9][А-ЯЁа-яёA-Za-z0-9 .\\-/]{1,70},?\\s*\\d+[А-ЯЁа-яёA-Za-z0-9/\\- ]{0,20}");
    private static final Pattern PERIOD_PATTERN = Pattern.compile("(?iu)(?:с\\s*20\\d{2}\\s*года|с\\s*19\\d{2}\\s*года|работа(?:ем|ет|ют)?\\s+(?:уже\\s+)?\\d{1,2}\\s*(?:год|года|лет)|(?:больше|более)\\s*\\d{1,2}\\s*лет\\s+на\\s+рынке)");
    private static final Pattern LIMIT_PATTERN = Pattern.compile("(?iu)(?:\\d{1,2}\\+|\\d{1,2}\\s*[-–]\\s*\\d{1,2}\\s*(?:лет|человек)|до\\s*\\d{1,2}\\s*человек|\\d{1,3}\\s*(?:минут|часа|часов|человек|персон))");
    private static final Pattern OFFER_FACT_PATTERN = Pattern.compile("(?im)^Услуга/товар:\\s*([^|\\n]+)(?:\\|\\s*описание:\\s*([^|\\n]+))?(?:\\|\\s*цена:\\s*([^|\\n]+))?(?:\\|\\s*URL:\\s*(\\S+))?.*$");
    private static final List<String> INTERNAL_PRODUCT_MARKERS = List.of(
            "отзыв", "2гис", "2 gis", "яндекс", "карты", "репутац", "seo", "продвиж", "реклам", "справочник"
    );
    private static final List<String> NON_OFFER_NAME_MARKERS = List.of(
            "рейтинг", "отзыв", "хорошее место", "search", "overview", "products and services", "business", "default", "award",
            "источник", "карты", "яндекс", "2гис", "vk", "вконтакте"
    );

    private final CompanyService companyService;
    private final WebsiteCrawler websiteCrawler;
    private final SearchProviderRouter searchProviderRouter;
    private final ReputationAiProperties properties;
    private final ReputationResearchSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    private final PageRoleClassifier pageRoleClassifier;

    @Transactional
    public ResearchSnapshot createSnapshot(Long companyId, ReputationResearchRequest request) {
        ReputationResearchRequest safeRequest = request == null
                ? new ReputationResearchRequest(null, null, List.of(), List.of(), true, null, null, null, null, null, null)
                : request;
        Company company = companyService.getCompaniesById(companyId);

        List<CompanySource> sources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        sources.add(new CompanySource(
                "database",
                "Данные компании из CRM",
                "",
                joinNonBlank(company.getTitle(), company.getCity(), company.getCommentsCompany())
        ));

        if (!isBlank(safeRequest.manualDescription())) {
            sources.add(new CompanySource(
                    "manual",
                    "Описание, добавленное пользователем",
                    "",
                    safeRequest.manualDescription()
            ));
        }

        appendFilialSources(company, sources, warnings);

        List<String> productHints = collectProducts(company, safeRequest);
        String website = firstNonBlank(safeRequest.websiteOverride(), company.getUrlSite());
        PageRoleContext pageContext = new PageRoleContext(
                company.getTitle(),
                company.getCity(),
                website,
                categoryTitle(company),
                subCategoryTitle(company)
        );
        List<String> urlsToCrawl = new ArrayList<>();
        if (safeRequest.shouldIncludeCompanyWebsite() && !isBlank(website) && isLikelyPublicBusinessUrl(website)) {
            urlsToCrawl.add(website);
        } else if (safeRequest.shouldIncludeCompanyWebsite() && !isBlank(website)) {
            warnings.add("URL сайта компании похож на служебную или нерелевантную ссылку и не был прочитан: " + website);
        }
        for (String publicUrl : cleanList(safeRequest.publicUrls())) {
            PageRole role = pageRoleClassifier.classify(publicUrl, "", "", pageContext);
            if (isLikelyPublicBusinessUrl(publicUrl) && role.canCrawlFromSearch()) {
                urlsToCrawl.add(publicUrl);
            } else {
                warnings.add("Публичная ссылка пропущена как нерелевантная: " + publicUrl);
            }
        }

        SearchRun searchRun = searchPublicSources(company, productHints, pageContext);
        if (!searchRun.available()) {
            warnings.add("Публичный поиск не выполнен: провайдер " + searchRun.provider() + " не настроен или недоступен.");
        } else if (searchRun.results().isEmpty()) {
            warnings.add("Публичный поиск выполнен, но релевантные результаты не найдены. Добавьте сайт или карточки вручную.");
        }
        for (SearchResult result : searchRun.results()) {
            PageRole role = pageRoleClassifier.classify(result.url(), result.title(), result.snippet(), pageContext);
            if (!role.canUseAsSource() || isBlockedOrServiceContent(joinNonBlank(result.title(), result.snippet(), result.url()))) {
                continue;
            }
            sources.add(new CompanySource(
                    pageRoleClassifier.sourceType(role, "search:" + result.provider()),
                    firstNonBlank(result.title(), "Публичное упоминание"),
                    result.url(),
                    result.snippet()
            ));
        }
        urlsToCrawl.addAll(filialCrawlUrls(company));
        urlsToCrawl.addAll(prioritizedCrawlUrls(searchRun.results(), pageContext));

        Set<String> deepCrawlHosts = pageRoleClassifier.host(website).isBlank()
                ? Set.of()
                : Set.of(pageRoleClassifier.host(website));
        List<CrawledPage> crawledPages = websiteCrawler.crawl(urlsToCrawl, deepCrawlHosts);
        if (!urlsToCrawl.isEmpty() && crawledPages.isEmpty()) {
            warnings.add("Сайт или публичные страницы не удалось прочитать. Проверьте URL, доступность сайта и ограничения площадок.");
        }
        for (CrawledPage page : crawledPages) {
            PageRole role = pageRoleClassifier.classify(page.url(), page.title(), page.text(), pageContext);
            if (!role.canUseAsSource() || isBlockedOrServiceContent(joinNonBlank(page.title(), page.text(), page.url()))) {
                warnings.add("Страница пропущена как captcha/служебная и не использована в слепке: " + page.url());
                continue;
            }
            String sourceType = role == PageRole.OFFICIAL_SITE
                    ? "website"
                    : pageRoleClassifier.sourceType(role, "public");
            sources.add(new CompanySource(
                    sourceType,
                    firstNonBlank(page.title(), "Публичная страница"),
                    page.url(),
                    page.text()
            ));
        }

        SourceTextBundle sourceTexts = splitSourceTexts(sources);
        String sourceText = sourceTexts.allText();
        String signalText = firstNonBlank(sourceTexts.trustedText(), sourceTexts.reputationText());
        if (signalText.isBlank()) {
            signalText = sourceText;
        }
        List<OfferFact> offerFacts = extractOfferFacts(sourceTexts.trustedText());
        List<String> products = mergeProducts(productHints, offerFacts, sourceTexts.trustedText(), company.getTitle());
        List<CompanyResearchAnswer> researchAnswers = buildResearchAnswers(company, products, offerFacts, sources, sourceTexts, warnings);

        ResearchSnapshot snapshot = new ResearchSnapshot(
                company.getId(),
                company.getTitle(),
                company.getCity(),
                website,
                company.getCategoryCompany() == null ? "" : company.getCategoryCompany().getCategoryTitle(),
                company.getSubCategory() == null ? "" : company.getSubCategory().getSubCategoryTitle(),
                company.getCommentsCompany(),
                products,
                detectAdvantages(signalText),
                buildPositiveTopics(products, signalText),
                buildNegativeWatchTopics(signalText),
                researchAnswers,
                sources,
                searchRun.provider(),
                searchRun.available(),
                searchRun.queries(),
                searchRun.results().size(),
                crawledPages.size(),
                warnings,
                LocalDateTime.now()
        );

        save(snapshot);
        return snapshot;
    }

    @Transactional(readOnly = true)
    public Optional<ResearchSnapshot> findLatestSnapshot(Long companyId) {
        return snapshotRepository.findFirstByCompanyIdOrderByCreatedAtDesc(companyId)
                .map(this::readSnapshot);
    }

    private void save(ResearchSnapshot snapshot) {
        ReputationResearchSnapshotEntity entity = new ReputationResearchSnapshotEntity();
        entity.setCompanyId(snapshot.companyId());
        entity.setCompanyTitle(snapshot.companyName());
        entity.setProvider("local-research");
        entity.setSourceCount(snapshot.sources().size());
        entity.setCreatedAt(snapshot.createdAt());
        try {
            entity.setSnapshotJson(objectMapper.writeValueAsString(snapshot));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось сохранить AI-слепок компании", exception);
        }

        snapshotRepository.save(entity);
    }

    private SearchRun searchPublicSources(Company company, List<String> products, PageRoleContext pageContext) {
        List<String> queries = buildSearchQueries(company, products);
        String provider = searchProviderRouter.activeProviderName();
        boolean available = searchProviderRouter.activeProviderAvailable();
        if (queries.isEmpty()) {
            return new SearchRun(provider, available, List.of(), List.of());
        }
        if (!available) {
            return new SearchRun(provider, false, queries, List.of());
        }

        LinkedHashSet<String> seenUrls = new LinkedHashSet<>();
        List<SearchResult> results = new ArrayList<>();
        for (String query : queries.stream().limit(properties.getSearch().getMaxQueries()).toList()) {
            for (SearchResult result : searchProviderRouter.search(new SearchQuery(query, properties.getSearch().getResultsPerQuery()))) {
                if (isRelevantSearchResult(result, company, products, pageContext) && seenUrls.add(result.url())) {
                    results.add(result);
                }
            }
        }

        return new SearchRun(provider, true, queries, results);
    }

    private List<String> buildSearchQueries(Company company, List<String> products) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String companyName = company.getTitle();
        if (isBlank(companyName)) {
            return List.of();
        }

        String city = company.getCity();
        String category = company.getCategoryCompany() == null ? "" : company.getCategoryCompany().getCategoryTitle();
        String product = products == null || products.isEmpty() ? "" : products.getFirst();
        List<String> filialHints = filialSearchHints(company);

        queries.add(joinNonBlank(companyName, city, category, "отзывы"));
        queries.add(joinNonBlank(companyName, city, "2ГИС"));
        queries.add(joinNonBlank(companyName, city, "Яндекс Карты"));
        queries.add(joinNonBlank(companyName, city, "Google Maps"));
        queries.add(joinNonBlank(companyName, city, "сайт"));
        if (!isBlank(product)) {
            queries.add(joinNonBlank(companyName, product, city));
        }
        queries.add(joinNonBlank(companyName, city, "цены услуги"));
        for (String hint : filialHints) {
            queries.add(joinNonBlank(companyName, hint));
            queries.add(joinNonBlank(companyName, hint, "2ГИС"));
            queries.add(joinNonBlank(companyName, hint, "отзывы"));
            queries.add(joinNonBlank(companyName, hint, "цены услуги"));
        }

        return queries.stream()
                .filter(query -> !query.isBlank())
                .toList();
    }

    private List<String> filialSearchHints(Company company) {
        if (company.getFilial() == null || company.getFilial().isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> hints = new LinkedHashSet<>();
        for (Filial filial : company.getFilial()) {
            if (filial == null) {
                continue;
            }
            String city = filial.getCity() == null ? "" : filial.getCity().getTitle();
            String title = firstNonBlank(filial.getTitle(), "");
            if (!isBlank(city)) {
                hints.add(city);
            }
            if (!isBlank(title)) {
                hints.add(title);
                hints.add(joinNonBlank(title, city));
            }
        }
        return hints.stream()
                .filter(hint -> !hint.isBlank())
                .limit(8)
                .toList();
    }

    private List<String> filialCrawlUrls(Company company) {
        if (company.getFilial() == null || company.getFilial().isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (Filial filial : company.getFilial()) {
            if (filial == null || isBlank(filial.getUrl())) {
                continue;
            }
            String url = filial.getUrl().trim();
            if (isLikelyPublicBusinessUrl(url)) {
                urls.add(url);
            }
        }
        return urls.stream()
                .limit(properties.getSearch().getCrawlResultLimit())
                .toList();
    }

    private List<String> prioritizedCrawlUrls(List<SearchResult> results, PageRoleContext pageContext) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        return results.stream()
                .filter(result -> result != null)
                .filter(result -> pageRoleClassifier.classify(result.url(), result.title(), result.snippet(), pageContext).canCrawlFromSearch())
                .filter(result -> !isBlank(result.url()))
                .filter(result -> isLikelyPublicBusinessUrl(result.url()))
                .sorted(Comparator.comparingInt(this::crawlPriority))
                .map(SearchResult::url)
                .distinct()
                .limit(properties.getSearch().getCrawlResultLimit())
                .toList();
    }

    private int crawlPriority(SearchResult result) {
        String value = normalizeForMatch(joinNonBlank(result.url(), result.title(), result.snippet()));
        if (value.contains("2gis") || value.contains("2гис")) {
            return 0;
        }
        if (value.contains("yandex") && (value.contains("maps") || value.contains("карты"))) {
            return 1;
        }
        if (value.contains("google") && value.contains("maps")) {
            return 2;
        }
        if (value.contains("карты") || value.contains("справочник") || value.contains("каталог")) {
            return 3;
        }
        return 10;
    }

    private ResearchSnapshot readSnapshot(ReputationResearchSnapshotEntity entity) {
        try {
            return objectMapper.readValue(entity.getSnapshotJson(), ResearchSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось прочитать AI-слепок компании", exception);
        }
    }

    private void appendFilialSources(Company company, List<CompanySource> sources, List<String> warnings) {
        Set<Filial> filials = company.getFilial();
        if (filials == null || filials.isEmpty()) {
            return;
        }

        for (Filial filial : filials) {
            if (filial == null) {
                continue;
            }

            String city = filial.getCity() == null ? "" : filial.getCity().getTitle();
            String filialUrl = firstNonBlank(filial.getUrl(), "");
            if (!filialUrl.isBlank() && !isLikelyPublicBusinessUrl(filialUrl)) {
                warnings.add("URL филиала '" + firstNonBlank(filial.getTitle(), "Филиал") + "' похож на нерелевантную ссылку и скрыт из источников: " + filialUrl);
                filialUrl = "";
            }
            sources.add(new CompanySource(
                    "filial",
                    firstNonBlank(filial.getTitle(), "Филиал"),
                    filialUrl,
                    joinNonBlank(filial.getTitle(), city, filialUrl)
            ));
        }
    }

    private List<String> collectProducts(Company company, ReputationResearchRequest request) {
        LinkedHashSet<String> products = new LinkedHashSet<>();
        for (String product : cleanList(request.productsOrServices())) {
            if (isBusinessOfferName(product)) {
                products.add(product);
            }
        }

        if (company.getSubCategory() != null && !isBlank(company.getSubCategory().getSubCategoryTitle())) {
            String subCategory = company.getSubCategory().getSubCategoryTitle();
            if (isBusinessOfferName(subCategory)) {
                products.add(subCategory);
            }
        }

        if (products.isEmpty() && !isBlank(company.getCategoryCompany() == null ? null : company.getCategoryCompany().getCategoryTitle())) {
            String category = company.getCategoryCompany().getCategoryTitle();
            if (isBusinessOfferName(category)) {
                products.add(category);
            }
        }

        return List.copyOf(products);
    }

    private SourceTextBundle splitSourceTexts(List<CompanySource> sources) {
        StringBuilder all = new StringBuilder();
        StringBuilder trusted = new StringBuilder();
        StringBuilder website = new StringBuilder();
        StringBuilder reputation = new StringBuilder();
        StringBuilder catalog = new StringBuilder();
        for (CompanySource source : sources) {
            String excerpt = source.excerpt();
            if (excerpt.isBlank()) {
                continue;
            }

            appendLine(all, excerpt);
            if (isTrustedFactSource(source)) {
                appendLine(trusted, excerpt);
            }
            if ("website".equals(source.type())) {
                appendLine(website, excerpt);
            } else if (isReputationSource(source)) {
                appendLine(reputation, joinNonBlank(source.title(), source.excerpt()));
            } else if (isCatalogSource(source)) {
                appendLine(catalog, joinNonBlank(source.title(), source.excerpt()));
            }
        }

        return new SourceTextBundle(all.toString(), trusted.toString(), website.toString(), reputation.toString(), catalog.toString());
    }

    private List<OfferFact> extractOfferFacts(String websiteText) {
        LinkedHashSet<OfferFact> facts = new LinkedHashSet<>();
        Matcher matcher = OFFER_FACT_PATTERN.matcher(websiteText == null ? "" : websiteText);
        while (matcher.find() && facts.size() < 40) {
            String name = cleanOfferName(matcher.group(1));
            if (!isBusinessOfferName(name)) {
                continue;
            }
            String url = matcher.group(4) == null ? "" : matcher.group(4).trim();
            if (isGenericCatalogOffer(name, url)) {
                continue;
            }

            facts.add(new OfferFact(
                    name,
                    limit(matcher.group(2), 420),
                    normalizePrice(matcher.group(3)),
                    url
            ));
        }
        return List.copyOf(facts);
    }

    private List<String> mergeProducts(List<String> productHints, List<OfferFact> offerFacts, String websiteText, String companyName) {
        LinkedHashSet<String> products = new LinkedHashSet<>();
        for (String product : productHints) {
            addProduct(products, product);
        }
        for (OfferFact offer : offerFacts) {
            addProduct(products, offer.name());
        }
        for (String name : extractOfferNames(websiteText, companyName)) {
            addProduct(products, name);
        }

        return products.stream().limit(30).toList();
    }

    private void addProduct(Set<String> products, String value) {
        String clean = cleanOfferName(value);
        if (isBusinessOfferName(clean)) {
            products.add(clean);
        }
    }

    private void appendLine(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(value.trim());
        }
    }

    private List<CompanyResearchAnswer> buildResearchAnswers(
            Company company,
            List<String> products,
            List<OfferFact> offerFacts,
            List<CompanySource> sources,
            SourceTextBundle sourceTexts,
            List<String> warnings
    ) {
        List<CompanyResearchAnswer> answers = new ArrayList<>();
        String sourceText = sourceTexts.allText();
        String businessText = sourceTexts.trustedText();
        List<String> quotedNames = extractOfferNames(businessText, company.getTitle());
        List<String> prices = mergePrices(offerFacts, extractPatternValues(PRICE_PATTERN, businessText, 16));
        List<String> addresses = extractPatternValues(ADDRESS_PATTERN, businessText, 12);
        List<String> periods = extractPatternValues(PERIOD_PATTERN, businessText, 6);
        List<String> limits = extractPatternValues(LIMIT_PATTERN, businessText, 12);
        List<String> reviewQuotes = extractSentences(sourceTexts.reputationText(), List.of(
                "понрав", "восторг", "совет", "атмосфер", "актер", "администратор",
                "интерес", "страш", "эмоци", "персонал", "рейтинг"
        ), 6);

        addAnswer(answers, "branch_address", "Название и адрес филиала",
                branchAnswer(company, addresses),
                sourceEvidence(sources, keywords("адрес", "ул", "улица", "карты", "филиал", company.getCity()), 4),
                addresses.isEmpty() ? 55 : 80);
        addAnswer(answers, "activity", "Чем занимается компания",
                joinSentence(
                        "Компания: " + firstNonBlank(company.getTitle(), "не указана"),
                        "Сфера из CRM: " + firstNonBlank(categoryTitle(company), "не указана"),
                        "Подкатегория: " + firstNonBlank(subCategoryTitle(company), "не указана"),
                        products.isEmpty() ? "" : "Видимые направления: " + String.join(", ", products.stream().limit(12).toList())
                ),
                sourceEvidence(sources, keywords(company.getTitle(), categoryTitle(company), subCategoryTitle(company)), 4),
                85);
        addAnswer(answers, "business_age", "Как давно вы работаете",
                periods.isEmpty()
                        ? createDateAnswer(company)
                        : "В публичных источниках встречается: " + String.join(", ", periods) + ". " + createDateAnswer(company),
                sourceEvidence(sources, List.of("лет", "года", "работает", "основан", "создан"), 4),
                periods.isEmpty() ? 45 : 70);
        addAnswer(answers, "offers", "Что именно вы предлагаете: товары и услуги",
                offeringsAnswer(products, offerFacts, quotedNames),
                sourceEvidence(sources, List.of("квест", "перформанс", "товар", "услуг", "сценар", "ассортимент"), 6),
                products.isEmpty() && quotedNames.isEmpty() && offerFacts.isEmpty() ? 20 : 80);
        addAnswer(answers, "products_prices", "Наименование товара - описание - цена",
                productPriceAnswer(products, offerFacts, prices, limits),
                sourceEvidence(sources, List.of("цена", "стоимость", "руб", "₽", "квест", "перформанс"), 6),
                prices.isEmpty() ? 45 : 75);
        addAnswer(answers, "entrance", "Как выглядит вход в заведение",
                answerFromSentences(businessText, List.of("вход", "вывеск", "этаж", "здание", "двер", "подъезд"), "Вход не описан в прочитанных текстовых источниках. Нужны фото или ручное описание."),
                sourceEvidence(sources, List.of("вход", "вывеск", "этаж", "здание", "двер"), 4),
                containsAny(businessText, List.of("вход", "вывеск", "этаж", "двер")) ? 60 : 0);
        addAnswer(answers, "interior", "Интерьер внутри: атмосфера",
                interiorAnswer(businessText, reviewQuotes),
                sourceEvidence(sources, List.of("атмосфер", "интерьер", "уют", "страш", "локац", "антураж", "актер"), 6),
                containsAny(businessText, List.of("атмосфер", "интерьер", "антураж", "актер")) ? 65 : 25);
        addAnswer(answers, "amenities", "Парковка, зона ожидания, Wi-Fi и дополнительные удобства",
                answerFromSentences(businessText, List.of("парков", "зона ожид", "ожидан", "удобств", "wi-fi", "wifi", "вай-фай", "туалет", "сануз", "гардероб", "детская зон", "чай", "кофе", "оплат"), "Парковка, зона ожидания, Wi-Fi и другие удобства не найдены в источниках. Нужно уточнить вручную."),
                sourceEvidence(sources, List.of("парков", "зона ожид", "удобств", "ожидан", "wi-fi", "wifi", "вай-фай", "туалет", "сануз", "гардероб", "детская зон", "оплат"), 4),
                containsAny(businessText, List.of("парков", "зона ожид", "удобств", "wi-fi", "wifi", "вай-фай", "туалет", "сануз", "гардероб", "детская зон", "чай", "оплат")) ? 65 : 0);
        addAnswer(answers, "popular", "Хиты продаж / популярные услуги",
                popularAnswer(quotedNames, reviewQuotes),
                sourceEvidence(sources, List.of("популяр", "рейтинг", "отзыв", "понрав", "квест"), 6),
                quotedNames.isEmpty() ? 35 : 65);
        addAnswer(answers, "unique_offers", "Уникальные предложения: что есть не у других",
                uniqueAnswer(businessText, quotedNames),
                sourceEvidence(sources, List.of("актер", "перформанс", "страш", "для детей", "день рождения", "уникаль", "корпоратив"), 6),
                containsAny(businessText, List.of("актер", "перформанс", "страш", "для детей", "день рождения")) ? 65 : 25);
        addAnswer(answers, "staff", "Имена и должности ключевых сотрудников",
                staffAnswer(businessText),
                sourceEvidence(sources, List.of("администратор", "менеджер", "мастер", "актер", "сотрудник"), 5),
                containsAny(businessText, List.of("администратор", "менеджер", "мастер", "актер")) ? 35 : 0);
        addAnswer(answers, "staff_experience", "Опыт, специализация, достижения сотрудников",
                staffExperienceAnswer(businessText),
                sourceEvidence(sources, List.of("опыт", "специализац", "достижен", "сертификат"), 4),
                containsAny(businessText, List.of("специализац", "достижен", "сертификат", "опыт")) ? 55 : 0);
        addAnswer(answers, "promotions", "Акции, скидки, спецпредложения",
                answerFromSentences(businessText, List.of("акци", "скид", "промокод", "спецпредлож", "подар", "бонус"), "Акции, скидки и спецпредложения не найдены в источниках."),
                sourceEvidence(sources, List.of("акци", "скид", "промокод", "бонус", "подар"), 4),
                containsAny(businessText, List.of("акци", "скид", "промокод", "бонус")) ? 60 : 0);
        addAnswer(answers, "review_phrases", "3-5 фраз для идеального отзыва",
                reviewPhrasesAnswer(reviewQuotes, company.getTitle()),
                sourceEvidence(sources, List.of("понрав", "восторг", "совет", "отзыв", "атмосфер"), 6),
                reviewQuotes.isEmpty() ? 35 : 70);
        addAnswer(answers, "real_review_quotes", "Реальные цитаты из устных/публичных отзывов",
                reviewQuotes.isEmpty()
                        ? "Цитаты не найдены. Можно собрать вручную у клиентов или из карточек после легального доступа к отзывам."
                        : String.join("\n", reviewQuotes.stream().limit(5).toList()),
                sourceEvidence(sources, List.of("отзыв", "понрав", "совет", "восторг"), 6),
                reviewQuotes.isEmpty() ? 0 : 70);
        addAnswer(answers, "booking_process", "Как происходит заказ/запись",
                answerFromSentences(businessText, List.of("запис", "бронир", "заказ", "позвон", "менеджер", "консультац"), "Порядок заказа/записи не найден. Нужно уточнить: заявка на сайте, звонок, мессенджер, предоплата, подтверждение времени."),
                sourceEvidence(sources, List.of("запис", "бронир", "заказ", "менеджер", "консультац"), 5),
                containsAny(businessText, List.of("запис", "бронир", "заказ")) ? 65 : 20);
        addAnswer(answers, "guarantees", "Гарантии, возвраты, бонусы",
                guaranteesAnswer(businessText),
                sourceEvidence(sources, List.of("гарант", "возврат", "бонус", "сертификат"), 5),
                containsAny(businessText, List.of("гарант", "возврат", "бонус", "сертификат")) ? 60 : 0);
        addAnswer(answers, "wait_time", "Сколько ждать выполнения заказа/услуги",
                limits.isEmpty()
                        ? "Срок выполнения/длительность услуги не найдены в источниках."
                        : "В источниках встречаются ограничения и длительности: " + String.join(", ", limits.stream().limit(8).toList()) + ".",
                sourceEvidence(sources, List.of("минут", "час", "длительность", "ждать", "до "), 5),
                limits.isEmpty() ? 20 : 65);
        addAnswer(answers, "travel_time", "Сколько ехать от центра до адреса",
                "Автоматический расчет времени в пути пока не подключен. Для точного ответа нужен адрес и интеграция с картами/геокодером; найденные адресные ориентиры: " + fallbackJoin(addresses, "не найдены") + ".",
                sourceEvidence(sources, List.of("адрес", "ул", "улица", "карты"), 4),
                addresses.isEmpty() ? 0 : 35);
        addAnswer(answers, "audience_restrictions", "Для кого подходит и какие ограничения",
                limits.isEmpty()
                        ? answerFromSentences(businessText, List.of("дет", "сем", "компан", "возраст", "страш", "сложн"), "Аудитория и ограничения не найдены.")
                        : "Встречаются ограничения/форматы: " + String.join(", ", limits.stream().limit(8).toList()) + ". " + answerFromSentences(businessText, List.of("дет", "сем", "компан", "возраст", "страш", "сложн"), ""),
                sourceEvidence(sources, List.of("дет", "сем", "компан", "возраст", "страш", "сложн", "человек"), 6),
                containsAny(businessText, List.of("дет", "сем", "компан", "возраст", "человек")) ? 65 : 20);
        addAnswer(answers, "competitors", "Конкуренты и соседние варианты в выдаче",
                competitorsAnswer(sources, company.getTitle()),
                sourceEvidence(sources, List.of("сравнен", "квест", "перформанс"), 8),
                45);
        addAnswer(answers, "source_quality", "Что еще важно знать о качестве данных",
                "Собрано источников: " + sources.size() + ". Поиск: " + sources.stream().filter(source -> source.type().startsWith("search")).count()
                        + ", официальный сайт: " + sources.stream().filter(source -> "website".equals(source.type())).count()
                        + ", внешние страницы: " + sources.stream().filter(source -> source.type().startsWith("public")).count()
                        + ". Предупреждения: " + (warnings.isEmpty() ? "нет" : String.join("; ", warnings)) + ". Фото с карт пока не анализируются как изображения, только текстовые описания и сниппеты.",
                sources.stream().limit(6).map(source -> source.title() + ": " + limit(source.excerpt(), 220)).toList(),
                sources.stream().map(CompanySource::url).filter(url -> !url.isBlank()).distinct().limit(6).toList(),
                warnings.isEmpty() ? 70 : 55,
                warnings.isEmpty() ? "found" : "partial");

        return answers;
    }

    private void addAnswer(
            List<CompanyResearchAnswer> answers,
            String key,
            String question,
            String answer,
            List<CompanySource> evidenceSources,
            int confidence
    ) {
        String cleanAnswer = firstNonBlank(answer, "");
        boolean missing = cleanAnswer.isBlank()
                || cleanAnswer.toLowerCase(Locale.ROOT).startsWith("не найден");
        String status = missing ? "missing" : confidence >= 70 ? "found" : "partial";
        answers.add(new CompanyResearchAnswer(
                key,
                question,
                missing ? "Не найдено в собранных источниках. Нужно добавить вручную или подключить профильную площадку." : cleanAnswer,
                evidenceSources.stream()
                        .map(source -> source.title() + ": " + limit(source.excerpt(), 260))
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .limit(4)
                        .toList(),
                evidenceSources.stream()
                        .map(CompanySource::url)
                        .filter(url -> !url.isBlank())
                        .distinct()
                        .limit(4)
                        .toList(),
                missing ? 0 : confidence,
                status
        ));
    }

    private void addAnswer(
            List<CompanyResearchAnswer> answers,
            String key,
            String question,
            String answer,
            List<String> evidence,
            List<String> sourceUrls,
            int confidence,
            String status
    ) {
        answers.add(new CompanyResearchAnswer(key, question, answer, evidence, sourceUrls, confidence, status));
    }

    private List<CompanySource> sourceEvidence(List<CompanySource> sources, List<String> keywords, int limit) {
        LinkedHashSet<CompanySource> result = new LinkedHashSet<>();
        for (CompanySource source : sources) {
            if (isCatalogSource(source)) {
                continue;
            }
            String haystack = normalizeForMatch(joinNonBlank(source.title(), source.url(), source.excerpt()));
            for (String keyword : keywords) {
                String normalized = normalizeForMatch(keyword);
                if (!normalized.isBlank() && haystack.contains(normalized)) {
                    result.add(source);
                    break;
                }
            }
            if (result.size() >= limit) {
                break;
            }
        }

        if (result.isEmpty()) {
            return sources.stream()
                    .filter(source -> !isCatalogSource(source))
                    .limit(Math.max(1, Math.min(limit, 2)))
                    .toList();
        }
        return result.stream().limit(limit).toList();
    }

    private List<String> keywords(String... values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value);
            }
        }
        return result;
    }

    private boolean isTrustedFactSource(CompanySource source) {
        String type = source.type();
        return "database".equals(type)
                || "manual".equals(type)
                || "filial".equals(type)
                || "website".equals(type)
                || type.contains(PageRole.COMPANY_PROFILE.sourceType());
    }

    private boolean isReputationSource(CompanySource source) {
        String type = source.type();
        return type.contains(PageRole.COMPANY_PROFILE.sourceType())
                || type.contains(PageRole.REVIEW_PAGE.sourceType());
    }

    private boolean isCatalogSource(CompanySource source) {
        String type = source.type();
        return type.contains(PageRole.CATALOG_LISTING.sourceType())
                || type.contains(PageRole.COMPETITOR_LISTING.sourceType());
    }

    private String branchAnswer(Company company, List<String> addresses) {
        List<String> branches = company.getFilial() == null ? List.of() : company.getFilial().stream()
                .filter(filial -> filial != null)
                .map(filial -> {
                    String filialUrl = firstNonBlank(filial.getUrl(), "");
                    if (!filialUrl.isBlank() && !isLikelyPublicBusinessUrl(filialUrl)) {
                        filialUrl = "";
                    }
                    return joinNonBlank(
                            firstNonBlank(filial.getTitle(), "Филиал"),
                            filial.getCity() == null ? "" : filial.getCity().getTitle(),
                            filialUrl
                    );
                })
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();

        String branchText = branches.isEmpty()
                ? "Филиалы в CRM не указаны"
                : "Филиалы из CRM: " + String.join("; ", branches) + ".";
        String addressText = addresses.isEmpty()
                ? " Адрес из публичных текстов не выделен."
                : " Адресные фрагменты из источников: " + String.join("; ", addresses) + ".";
        return branchText + addressText;
    }

    private String categoryTitle(Company company) {
        return company.getCategoryCompany() == null ? "" : company.getCategoryCompany().getCategoryTitle();
    }

    private String subCategoryTitle(Company company) {
        return company.getSubCategory() == null ? "" : company.getSubCategory().getSubCategoryTitle();
    }

    private String createDateAnswer(Company company) {
        return company.getCreateDate() == null
                ? "Дата начала работы в CRM не указана."
                : "В CRM компания заведена " + company.getCreateDate() + "; это не обязательно дата открытия бизнеса.";
    }

    private String offeringsAnswer(List<String> products, List<OfferFact> offerFacts, List<String> quotedNames) {
        List<String> parts = new ArrayList<>();
        if (!products.isEmpty()) {
            parts.add("Найденные направления: " + String.join(", ", products.stream().limit(16).toList()));
        }
        if (!offerFacts.isEmpty()) {
            parts.add("По сайту/структурным данным: " + offerFacts.stream()
                    .map(OfferFact::name)
                    .distinct()
                    .limit(16)
                    .reduce("", (left, right) -> left.isBlank() ? right : left + ", " + right));
        }
        if (!quotedNames.isEmpty()) {
            parts.add("Дополнительно в текстах встречаются сценарии: " + String.join(", ", quotedNames.stream().limit(16).toList()));
        }
        return parts.isEmpty() ? "" : String.join(". ", parts) + ".";
    }

    private String productPriceAnswer(List<String> products, List<OfferFact> offerFacts, List<String> prices, List<String> limits) {
        List<String> lines = new ArrayList<>();
        for (OfferFact offer : offerFacts.stream().limit(18).toList()) {
            lines.add("- " + offer.name()
                    + " — " + firstNonBlank(offer.description(), "описание есть на сайте, нужно проверить формулировку")
                    + (offer.price().isBlank() ? "" : "; цена: " + offer.price())
                    + (offer.url().isBlank() ? "" : "; источник: " + offer.url()));
        }
        List<String> names = new ArrayList<>(products);
        names.stream()
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(12)
                .filter(name -> offerFacts.stream().noneMatch(offer -> normalizeForMatch(offer.name()).equals(normalizeForMatch(name))))
                .forEach(name -> lines.add("- " + name + " — описание нужно уточнить по карточке/сайту"
                        + (prices.isEmpty() ? "" : "; найденные цены/ориентиры: " + String.join(", ", prices.stream().limit(3).toList()))
                        + (limits.isEmpty() ? "" : "; ограничения/длительность: " + String.join(", ", limits.stream().limit(3).toList()))));
        if (lines.isEmpty()) {
            return "";
        }
        if (prices.isEmpty()) {
            lines.add("Цены не найдены в прочитанных источниках: их нужно подтянуть с сайта/карточки или внести вручную.");
        }
        return String.join("\n", lines);
    }

    private String interiorAnswer(String sourceText, List<String> reviewQuotes) {
        String sentences = answerFromSentences(sourceText, List.of("атмосфер", "интерьер", "антураж", "уют", "страш", "локац", "актер"), "");
        if (!sentences.isBlank()) {
            return sentences;
        }
        if (!reviewQuotes.isEmpty()) {
            return "По отзывам можно осторожно судить об атмосфере: " + String.join(" ", reviewQuotes.stream().limit(2).toList());
        }
        return "Интерьер и атмосфера не описаны в прочитанных источниках. Для сильного слепка нужны фото, карточка или ручное описание.";
    }

    private String popularAnswer(List<String> quotedNames, List<String> reviewQuotes) {
        if (!quotedNames.isEmpty()) {
            return "Чаще всего в найденных текстах встречаются: " + String.join(", ", quotedNames.stream().limit(8).toList()) + ". Это не равно продажам, но показывает видимые публичные направления.";
        }
        if (!reviewQuotes.isEmpty()) {
            return "По отзывам заметны повторяющиеся темы: " + String.join(" ", reviewQuotes.stream().limit(3).toList());
        }
        return "";
    }

    private String uniqueAnswer(String sourceText, List<String> quotedNames) {
        List<String> signals = new ArrayList<>();
        if (containsAny(sourceText, List.of("актер"))) {
            signals.add("форматы с актерами");
        }
        if (containsAny(sourceText, List.of("перформанс"))) {
            signals.add("перформансы");
        }
        if (containsAny(sourceText, List.of("страш", "ужас", "мистич"))) {
            signals.add("страшные/мистические сценарии");
        }
        if (containsAny(sourceText, List.of("дет", "сем"))) {
            signals.add("сценарии для детей или семейного отдыха");
        }
        if (containsAny(sourceText, List.of("день рождения", "корпоратив"))) {
            signals.add("форматы под праздники и компании");
        }
        if (!quotedNames.isEmpty()) {
            signals.add("видимые сценарии: " + String.join(", ", quotedNames.stream().limit(6).toList()));
        }
        return signals.isEmpty() ? "" : String.join("; ", signals) + ".";
    }

    private String reviewPhrasesAnswer(List<String> reviewQuotes, String companyName) {
        if (!reviewQuotes.isEmpty()) {
            return "Можно просить клиента раскрыть реальные детали вокруг таких тем:\n- что выбрали и с кем ходили;\n- что понравилось в атмосфере/организации;\n- насколько ожидания совпали с описанием;\n- что запомнилось больше всего;\n- кому бы клиент посоветовал " + firstNonBlank(companyName, "компанию") + ".";
        }
        return "Можно использовать нейтральные подсказки: что выбрали, почему выбрали компанию, что понравилось, что можно улучшить, соответствовал ли результат ожиданиям.";
    }

    private String staffAnswer(String businessText) {
        List<String> roles = new ArrayList<>();
        if (containsAny(businessText, List.of("актер", "актёр"))) {
            roles.add("актеры/персонажи в квестах");
        }
        if (containsAny(businessText, List.of("администратор"))) {
            roles.add("администраторы");
        }
        if (containsAny(businessText, List.of("ведущ"))) {
            roles.add("ведущие");
        }
        if (containsAny(businessText, List.of("фотограф"))) {
            roles.add("фотограф");
        }
        if (roles.isEmpty()) {
            return "Имена ключевых сотрудников не найдены. Нужно уточнить вручную.";
        }
        return "Имена сотрудников в источниках не найдены. Упоминаются роли: " + String.join(", ", roles) + ". Для продакшен-текстов лучше добавить реальные имена, должности и опыт.";
    }

    private String staffExperienceAnswer(String businessText) {
        String answer = answerFromSentences(businessText, List.of("опыт", "специализац", "достижен", "сертификат"), "");
        return answer.isBlank()
                ? "Опыт, специализация и достижения сотрудников в источниках не найдены. Нужно запросить вручную: опыт актеров/ведущих, стаж администраторов, обучение, награды."
                : answer;
    }

    private String guaranteesAnswer(String businessText) {
        String answer = answerFromSentences(businessText, List.of("гарант", "возврат", "бонус", "сертификат"), "");
        return answer.isBlank()
                ? "Гарантии, возвраты и бонусы не найдены. Для продакшен-текстов это нужно уточнить у компании."
                : answer;
    }

    private String competitorsAnswer(List<CompanySource> sources, String companyName) {
        List<String> competitors = sources.stream()
                .filter(source -> source.type().startsWith("search"))
                .map(CompanySource::title)
                .filter(title -> !title.isBlank())
                .filter(title -> !normalizeForMatch(title).contains(normalizeForMatch(companyName)))
                .filter(title -> !isBlockedOrServiceContent(title))
                .distinct()
                .limit(8)
                .toList();
        return competitors.isEmpty()
                ? "Конкуренты в текущем поисковом слепке явно не выделены."
                : "В выдаче рядом встречаются: " + String.join("; ", competitors) + ".";
    }

    private String answerFromSentences(String text, List<String> keywords, String fallback) {
        List<String> sentences = extractSentences(text, keywords, 4);
        return sentences.isEmpty() ? fallback : String.join(" ", sentences);
    }

    private List<String> extractOfferNames(String text, String companyName) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = QUOTED_NAME_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find() && result.size() < 30) {
            String value = cleanOfferName(matcher.group(1));
            String lower = normalizeForMatch(value);
            if (isBusinessOfferName(value)
                    && !lower.equals(normalizeForMatch(companyName))) {
                result.add(value);
            }
        }
        return result.stream().limit(20).toList();
    }

    private List<String> extractPatternValues(Pattern pattern, String text, int limit) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        while (matcher.find() && result.size() < limit) {
            String value = cleanExtractedValue(matcher.group());
            if (!value.isBlank() && !isBlockedOrServiceContent(value) && !isNoisyExtractedValue(value)) {
                result.add(value);
            }
        }
        return result.stream().limit(limit).toList();
    }

    private List<String> mergePrices(List<OfferFact> offerFacts, List<String> extractedPrices) {
        LinkedHashSet<String> prices = new LinkedHashSet<>();
        for (OfferFact offer : offerFacts) {
            String price = normalizePrice(offer.price());
            if (!price.isBlank()) {
                prices.add(price);
            }
        }
        for (String price : extractedPrices) {
            String clean = normalizePrice(price);
            if (!clean.isBlank()) {
                prices.add(clean);
            }
        }
        return prices.stream().limit(16).toList();
    }

    private String normalizePrice(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private List<String> extractSentences(String text, List<String> keywords, int limit) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String sentence : text.replaceAll("\\s+", " ").split("(?<=[.!?])\\s+")) {
            String clean = sentence.trim();
            String lower = normalizeForMatch(clean);
            if (clean.length() < 24 || clean.length() > 260 || isBlockedOrServiceContent(clean)) {
                continue;
            }
            if (keywords.stream().map(this::normalizeForMatch).anyMatch(lower::contains)) {
                result.add(clean);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result.stream().limit(limit).toList();
    }

    private boolean containsAny(String text, List<String> keywords) {
        String lower = normalizeForMatch(text);
        return keywords.stream()
                .map(this::normalizeForMatch)
                .filter(keyword -> !keyword.isBlank())
                .anyMatch(lower::contains);
    }

    private boolean isBusinessOfferName(String value) {
        String lower = normalizeForMatch(value);
        if (lower.length() < 3 || lower.length() > 90) {
            return false;
        }
        if (isGenericOfferName(lower)) {
            return false;
        }
        if (INTERNAL_PRODUCT_MARKERS.stream().anyMatch(lower::contains)
                || NON_OFFER_NAME_MARKERS.stream().anyMatch(lower::contains)) {
            return false;
        }
        return containsAny(lower, List.of(
                "квест", "лазертаг", "нерф", "картинг", "день рождения", "праздник", "выпускной",
                "прятки", "амонг", "хагги", "уэнсдей", "сталкер", "психоз", "психбольница",
                "тюрьма", "замок", "экзорцизм", "звездные", "оно", "лоботомия", "космический"
        ));
    }

    private String cleanOfferName(String value) {
        String clean = value == null ? "" : value
                .replaceAll("(?iu)\\s+в\\s+иркутске\\s+и\\s+ангарске", "")
                .replaceAll("(?iu)(иркутск|ирктск|иркутске|иркутска|ангарск|ангарске|ангарска|россия)", "")
                .replaceAll("(?iu)\\bв\\s+и\\b", "")
                .replaceAll("(?iu)\\bг\\.\\s*", "")
                .replaceAll("\\s+", " ")
                .replaceAll("^[\\-:;,.\\s]+|[\\-:;,.\\s]+$", "")
                .trim();
        return clean;
    }

    private boolean isGenericCatalogOffer(String name, String url) {
        String lowerName = normalizeForMatch(name);
        String lowerUrl = normalizeForMatch(url);
        return isGenericOfferName(lowerName)
                || lowerName.contains("mir-kvestov")
                || lowerName.contains("выбрать квест")
                || lowerUrl.contains("/companies/")
                || lowerUrl.endsWith("/quests")
                || lowerUrl.contains("/categories/")
                || lowerUrl.contains("/ratings");
    }

    private boolean isGenericOfferName(String lower) {
        return lower.contains("квесты от")
                || lower.contains("все квесты")
                || lower.contains("лучшие квесты")
                || lower.contains("народный рейтинг")
                || lower.contains("выбрать квест")
                || lower.contains("мир квестов")
                || lower.contains("mir-kvestov")
                || lower.matches(".*\\bквесты?\\s+в\\s+[^\\s]+.*");
    }

    private String cleanExtractedValue(String value) {
        return value == null ? "" : value
                .replaceAll("\\s+", " ")
                .replaceAll("(?iu)\\s+(class|style|id)$", "")
                .replaceAll("(?iu)\\s+class\\s*$", "")
                .trim();
    }

    private boolean isNoisyExtractedValue(String value) {
        String lower = normalizeForMatch(value);
        return lower.contains("mozilla")
                || lower.contains("developer.")
                || lower.contains("align-items")
                || lower.contains("http")
                || lower.contains("level ")
                || lower.contains("local expert")
                || lower.contains("subscribe")
                || lower.contains("source")
                || lower.contains("search directions");
    }

    private String fallbackJoin(List<String> values, String fallback) {
        return values == null || values.isEmpty() ? fallback : String.join(", ", values);
    }

    private String joinSentence(String... values) {
        List<String> clean = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    clean.add(value.trim());
                }
            }
        }
        return clean.stream()
                .map(value -> value.endsWith(".") ? value : value + ".")
                .reduce("", (left, right) -> left.isBlank() ? right : left + " " + right);
    }

    private List<String> detectAdvantages(String text) {
        String lower = normalizeForMatch(text);
        LinkedHashSet<String> advantages = new LinkedHashSet<>();

        addIfContains(advantages, lower, "день рождения под ключ", "детский праздник под ключ");
        addIfContains(advantages, lower, "чайная зона", "чайная зона для гостей");
        addIfContains(advantages, lower, "актер", "квесты с актерами");
        addIfContains(advantages, lower, "актёр", "квесты с актерами");
        addIfContains(advantages, lower, "лазертаг", "лазертаг");
        addIfContains(advantages, lower, "нерф", "NERF-арена");
        addIfContains(advantages, lower, "картинг", "детский дрифт-картинг");
        addIfContains(advantages, lower, "дискотек", "дискотека и активная программа");
        addIfContains(advantages, lower, "фотограф", "можно добавить фотографа");
        addIfContains(advantages, lower, "пицц", "можно заказать питание");
        addIfContains(advantages, lower, "доставка", "доставка");
        addIfContains(advantages, lower, "консульта", "консультация перед покупкой");
        addIfContains(advantages, lower, "ассортимент", "широкий ассортимент");
        addIfContains(advantages, lower, "гарант", "гарантийная поддержка");
        addIfContains(advantages, lower, "качест", "акцент на качестве");
        addIfContains(advantages, lower, "цена", "понятные цены");
        addIfContains(advantages, lower, "опыт", "опыт в своей нише");
        addIfContains(advantages, lower, "подбор", "помощь с подбором");
        addIfContains(advantages, lower, "быстр", "быстрое оформление");

        if (advantages.isEmpty()) {
            advantages.add("можно уточнить преимущества у менеджера");
            advantages.add("можно выделить сервис, ассортимент и удобство покупки после проверки фактов");
        }

        return List.copyOf(advantages);
    }

    private List<String> buildPositiveTopics(List<String> products, String text) {
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        topics.add("выбранная программа или квест");
        topics.add("организация праздника");

        String lower = normalizeForMatch(text);
        if (lower.contains("актер") || lower.contains("актёр")) {
            topics.add("работа актеров");
        }
        if (lower.contains("чайная зона")) {
            topics.add("чайная зона и удобство для гостей");
        }
        if (lower.contains("дет")) {
            topics.add("эмоции ребенка");
        }
        if (!products.isEmpty()) {
            topics.add("выбор " + products.getFirst());
        }

        topics.add("удобство записи");
        topics.add("соответствие описанию");
        return List.copyOf(topics);
    }

    private List<String> buildNegativeWatchTopics(String text) {
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        String lower = normalizeForMatch(text);

        if (lower.contains("страш")) {
            topics.add("уровень страха и возрастные ограничения");
        }
        topics.add("скорость ответа и подтверждения записи");
        topics.add("точность описания программы");
        topics.add("состояние локации и реквизита");
        topics.add("организация времени");
        topics.add("точность ожиданий клиента");
        return List.copyOf(topics);
    }

    private void addIfContains(Set<String> target, String text, String needle, String value) {
        if (text.contains(needle)) {
            target.add(value);
        }
    }

    private boolean isRelevantSearchResult(SearchResult result, Company company, List<String> products, PageRoleContext pageContext) {
        String haystack = normalizeForMatch(joinNonBlank(result.title(), result.snippet(), result.url()));
        PageRole role = pageRoleClassifier.classify(result.url(), result.title(), result.snippet(), pageContext);
        if (!role.canUseAsSource() || isBlockedOrServiceContent(haystack)) {
            return false;
        }

        String companyName = normalizeForMatch(company.getTitle());
        String city = normalizeForMatch(company.getCity());
        boolean hasCity = !city.isBlank() && haystack.contains(city);

        String category = company.getCategoryCompany() == null ? "" : company.getCategoryCompany().getCategoryTitle();
        String subCategory = company.getSubCategory() == null ? "" : company.getSubCategory().getSubCategoryTitle();
        List<String> tokens = new ArrayList<>();
        tokens.add(category);
        tokens.add(subCategory);
        tokens.addAll(products == null ? List.of() : products);
        boolean hasBusinessToken = tokens.stream()
                .map(this::normalizeForMatch)
                .filter(token -> token.length() >= 4)
                .anyMatch(haystack::contains);

        boolean hasFilialSignal = filialSearchHints(company).stream()
                .map(this::normalizeForMatch)
                .filter(token -> token.length() >= 4)
                .anyMatch(haystack::contains);

        if (!companyName.isBlank() && haystack.contains(companyName)) {
            return hasCity || hasFilialSignal || hasBusinessToken || isTrustedLocalDirectory(result.url());
        }

        if (!hasCity && !hasFilialSignal) {
            return false;
        }

        return hasBusinessToken;
    }

    private boolean isTrustedLocalDirectory(String url) {
        String host = host(url == null ? "" : url.trim());
        return List.of(
                "2gis.ru",
                "go.2gis.com",
                "yell.ru",
                "banya.ru",
                "zoon.ru",
                "yandex.ru",
                "maps.yandex.ru"
        ).contains(host);
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) {
            return List.of();
        }

        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String joinNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }

        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim());
            }
        }

        return String.join(" ", result);
    }

    private String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first.trim() : !isBlank(second) ? second.trim() : "";
    }

    private boolean isLikelyPublicBusinessUrl(String value) {
        if (isBlank(value)) {
            return false;
        }

        String normalized = value.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }

        String host = host(normalized);
        if (host.isBlank()) {
            return false;
        }

        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (pageRoleClassifier.isServiceOrLegalPage(normalized, "", "")) {
            return false;
        }

        return !List.of(
                "developer.mozilla.org",
                "learn.microsoft.com",
                "stackoverflow.com",
                "github.com",
                "cloud.yandex.ru",
                "yandex.cloud",
                "localhost",
                "example.com"
        ).contains(lowerHost);
    }

    private boolean isBlockedOrServiceContent(String value) {
        String lower = normalizeForMatch(value);
        return pageRoleClassifier.isServiceOrLegalPage(value, "", value)
                || lower.contains("smartcaptcha")
                || lower.contains("smart-captcha")
                || lower.contains("captcha")
                || lower.contains("are you not a robot")
                || lower.contains("я не робот")
                || lower.contains("please confirm that you are not a robot")
                || lower.contains("решение частых проблем")
                || lower.contains("нет такой страницы")
                || lower.contains("sitemap");
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength).trim();
    }

    private String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception exception) {
            return "";
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizeForMatch(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private record SearchRun(
            String provider,
            boolean available,
            List<String> queries,
            List<SearchResult> results
    ) {
    }

    private record SourceTextBundle(
            String allText,
            String trustedText,
            String websiteText,
            String reputationText,
            String catalogText
    ) {
        SourceTextBundle {
            allText = allText == null ? "" : allText;
            trustedText = trustedText == null ? "" : trustedText;
            websiteText = websiteText == null ? "" : websiteText;
            reputationText = reputationText == null ? "" : reputationText;
            catalogText = catalogText == null ? "" : catalogText;
        }
    }

    private record OfferFact(
            String name,
            String description,
            String price,
            String url
    ) {
        OfferFact {
            name = name == null ? "" : name.trim();
            description = description == null ? "" : description.trim();
            price = price == null ? "" : price.trim();
            url = url == null ? "" : url.trim();
        }
    }
}

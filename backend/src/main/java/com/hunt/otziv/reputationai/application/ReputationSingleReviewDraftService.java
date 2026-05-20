package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.api.dto.ReputationBatchReviewDraftRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationBatchReviewDraftTarget;
import com.hunt.otziv.reputationai.api.dto.ReputationSingleReviewDraftRequest;
import com.hunt.otziv.reputationai.domain.CompanyAiProfile;
import com.hunt.otziv.reputationai.domain.CompanySource;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ReputationBatchReviewDraftItem;
import com.hunt.otziv.reputationai.domain.ReputationBatchReviewDraftResult;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ReputationSingleReviewDraftResult;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import com.hunt.otziv.reputationai.domain.ReviewGenerationBrief;
import com.hunt.otziv.reputationai.domain.ReviewGenerationSlot;
import com.hunt.otziv.reputationai.persistence.ReputationContentPackJobEntity;
import com.hunt.otziv.reputationai.persistence.ReputationContentPackJobRepository;
import com.hunt.otziv.reputationai.persistence.ReputationDeepReportJobEntity;
import com.hunt.otziv.reputationai.persistence.ReputationDeepReportJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReputationSingleReviewDraftService {

    private final ReputationContentPackJobRepository contentPackJobRepository;
    private final ReputationDeepReportJobRepository deepReportJobRepository;
    private final AiSingleReviewDraftFactory aiSingleReviewDraftFactory;
    private final ReviewSafetyService reviewSafetyService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ReputationSingleReviewDraftResult generate(Long companyId, ReputationSingleReviewDraftRequest request) {
        ReputationSingleReviewDraftRequest safeRequest = request == null
                ? new ReputationSingleReviewDraftRequest(null, null, null, null, null, null, null)
                : request;
        ReportEnvelope reportEnvelope = deepReport(companyId, safeRequest.deepReportJobId())
                .orElseThrow(() -> new IllegalStateException("Сначала соберите или загрузите готовый глубокий отчет компании."));
        Optional<PackEnvelope> savedPack = contentPack(companyId, safeRequest.contentPackJobId());
        if (savedPack.isEmpty() && safeRequest.contentPackJobId() != null && safeRequest.contentPackJobId() > 0) {
            throw new IllegalStateException("Готовый AI-пакет для указанной задачи не найден.");
        }
        PackEnvelope packEnvelope = savedPack.orElseGet(() -> reportOnlyPack(reportEnvelope.report()));
        String idea = selectedIdea(packEnvelope.pack(), safeRequest.idea());
        List<String> facts = sourceFacts(packEnvelope.pack(), reportEnvelope.report(), idea, safeRequest.orderContext());

        Optional<ReputationSingleReviewDraftResult> aiResult = aiSingleReviewDraftFactory.create(
                        companyId,
                        reportEnvelope.jobId(),
                        packEnvelope.jobId(),
                        reportEnvelope.report(),
                        packEnvelope.pack(),
                        safeRequest,
                        idea,
                        facts
                );
        ReputationSingleReviewDraftResult result = aiResult.orElseThrow(() -> {
            log.info(
                    "REVIEW_SINGLE_ROUTE source=openai_empty companyId={} reviewId={} reason=openai_unavailable_empty_or_rejected idea=\"{}\"",
                    companyId,
                    safeRequest.targetReviewId(),
                    shortText(idea, 120)
            );
            return new IllegalStateException("OpenAI не вернул подходящий черновик для карточки.");
        });
        log.info(
                "REVIEW_SINGLE_ROUTE source=openai companyId={} reviewId={} provider={} model={} idea=\"{}\"",
                companyId,
                safeRequest.targetReviewId(),
                result.provider(),
                result.model(),
                shortText(idea, 120)
        );
        return sanitizeCompanyName(result, reportEnvelope, packEnvelope);
    }

    @Transactional(readOnly = true)
    public ReputationBatchReviewDraftResult generateBatch(Long companyId, ReputationBatchReviewDraftRequest request) {
        ReputationBatchReviewDraftRequest safeRequest = request == null
                ? new ReputationBatchReviewDraftRequest(null, null, null, null, null, null, null, null, List.of())
                : request;
        if (safeRequest.targets().isEmpty()) {
            throw new IllegalStateException("Нет карточек отзывов для AI-помощи.");
        }
        ReportEnvelope reportEnvelope = deepReport(companyId, safeRequest.deepReportJobId())
                .orElseThrow(() -> new IllegalStateException("Сначала соберите или загрузите готовый глубокий отчет компании."));
        Optional<PackEnvelope> savedPack = contentPack(companyId, safeRequest.contentPackJobId());
        if (savedPack.isEmpty() && safeRequest.contentPackJobId() != null && safeRequest.contentPackJobId() > 0) {
            throw new IllegalStateException("Готовый AI-пакет для указанной задачи не найден.");
        }
        PackEnvelope packEnvelope = savedPack.orElseGet(() -> reportOnlyPack(reportEnvelope.report()));
        ReviewGenerationBrief brief = reviewGenerationBrief(
                packEnvelope.pack(),
                reportEnvelope.report(),
                safeRequest.targets()
        );
        List<ReviewGenerationSlot> slots = reviewGenerationSlots(brief, safeRequest.targets());
        List<AiSingleReviewDraftFactory.BatchDraftTarget> targets = batchTargets(slots);

        log.info(
                "REVIEW_BATCH_START companyId={} targets={} openAiAvailable={} targetIds={}",
                companyId,
                targets.size(),
                aiSingleReviewDraftFactory.isOpenAiAvailable(),
                targets.stream().map(AiSingleReviewDraftFactory.BatchDraftTarget::reviewId).toList()
        );

        Optional<ReputationBatchReviewDraftResult> aiBatch = aiSingleReviewDraftFactory.createBatch(
                        companyId,
                        reportEnvelope.jobId(),
                        packEnvelope.jobId(),
                        packEnvelope.pack(),
                        safeRequest,
                        brief,
                        slots
                );
        ReputationBatchReviewDraftResult generated;
        if (aiBatch.isPresent()) {
            generated = aiBatch.orElseThrow();
            log.info(
                    "REVIEW_BATCH_ROUTE source=openai companyId={} acceptedDrafts={} acceptedIds={}",
                    companyId,
                    generated.drafts().size(),
                    generated.drafts().stream().map(ReputationBatchReviewDraftItem::reviewId).toList()
            );
        } else {
            generated = openAiOnlyEmptyBatchResult(companyId, reportEnvelope, packEnvelope);
            log.info(
                    "REVIEW_BATCH_ROUTE source=openai_empty companyId={} reason=openai_unavailable_empty_or_all_rejected targetIds={}",
                    companyId,
                    targets.stream().map(AiSingleReviewDraftFactory.BatchDraftTarget::reviewId).toList()
            );
        }
        List<Long> missingIds = missingTargetIds(targets, generated);
        if (!missingIds.isEmpty() && hasOpenAiTransportFailure(generated)) {
            log.info(
                    "REVIEW_BATCH_SHORT_FALLBACK_SKIPPED source=short_fallback_missing companyId={} missingDrafts={} missingIds={} reason=openai_transport_error",
                    companyId,
                    missingIds.size(),
                    missingIds
            );
        }
        if (!missingIds.isEmpty() && !hasOpenAiTransportFailure(generated) && aiSingleReviewDraftFactory.isOpenAiAvailable()) {
            List<AiSingleReviewDraftFactory.BatchDraftTarget> fallbackTargets = missingTargets(targets, missingIds);
            ReputationBatchReviewDraftResult shortFallbackResult = shortFallbackBatchResult(
                    companyId,
                    reportEnvelope,
                    packEnvelope,
                    safeRequest,
                    brief,
                    fallbackTargets,
                    generated
            );
            generated = mergeOpenAiBatchResults(generated, shortFallbackResult, targets);
            log.info(
                    "REVIEW_BATCH_SHORT_FALLBACK_RESULT source=short_fallback_missing companyId={} fallbackDrafts={} fallbackIds={} baseProvider={} baseModel={}",
                    companyId,
                    shortFallbackResult.drafts().size(),
                    shortFallbackResult.drafts().stream().map(ReputationBatchReviewDraftItem::reviewId).toList(),
                    generated.provider(),
                    generated.model()
            );
            missingIds = missingTargetIds(targets, generated);
        }
        int fallbackDrafts = countShortFallbackDrafts(generated);
        int openAiDrafts = Math.max(0, generated.drafts().size() - fallbackDrafts);
        if (!missingIds.isEmpty()) {
            log.info(
                    "REVIEW_BATCH_NO_FALLBACK source=openai_only companyId={} missingDrafts={} missingIds={} reason=openai_transport_error_or_unavailable",
                    companyId,
                    missingIds.size(),
                    missingIds
            );
        }
        ReputationBatchReviewDraftResult result = generated;
        String sourceRoute = fallbackDrafts > 0 && openAiDrafts > 0
                ? "mixed"
                : fallbackDrafts > 0 ? "short_fallback" : openAiDrafts > 0 ? "openai" : "openai_empty";
        log.info(
                "REVIEW_BATCH_DONE source={} companyId={} openAiDrafts={} fallbackDrafts={} provider={} model={} totalDrafts={} draftIds={}",
                sourceRoute,
                companyId,
                openAiDrafts,
                fallbackDrafts,
                result.provider(),
                result.model(),
                result.drafts().size(),
                result.drafts().stream().map(ReputationBatchReviewDraftItem::reviewId).toList()
        );
        return sanitizeCompanyName(result, reportEnvelope, packEnvelope);
    }

    private boolean hasOpenAiTransportFailure(ReputationBatchReviewDraftResult result) {
        return result != null && result.safetyNotes().stream().anyMatch(this::isOpenAiTransportFailureNote);
    }

    private boolean isOpenAiTransportFailureNote(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return normalized.contains("оборвался на сетевом уровне")
                || normalized.contains("proxy/vpn")
                || normalized.contains("маршрут до openai")
                || normalized.contains("connection refused")
                || normalized.contains("header parser received no bytes")
                || normalized.contains("chunked transfer encoding");
    }

    private PackEnvelope reportOnlyPack(DeepCompanyResearchReport report) {
        List<String> reviewIdeas = reviewIdeasFromReport(report);
        List<String> advantages = report.sections().stream()
                .filter(section -> importantSection(section.title(), section.body()))
                .map(section -> shortText(section.title() + ": " + section.body(), 240))
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(8)
                .toList();
        List<CompanySource> sources = report.sources().stream()
                .limit(12)
                .map(source -> new CompanySource(source.type(), source.title(), source.url(), source.note()))
                .toList();
        ResearchSnapshot snapshot = new ResearchSnapshot(
                report.companyId(),
                report.companyName(),
                report.city(),
                "",
                "",
                "",
                "",
                List.of(),
                advantages,
                reviewIdeas,
                List.of(),
                List.of(),
                sources,
                report.provider(),
                true,
                List.of(),
                report.sources().size(),
                0,
                report.warnings(),
                report.createdAt()
        );
        CompanyAiProfile profile = new CompanyAiProfile(
                firstSectionBody(report),
                "",
                List.of(),
                advantages,
                reviewIdeas,
                List.of(),
                report.warnings()
        );
        ReputationContentPack pack = new ReputationContentPack(
                snapshot,
                profile,
                advantages.stream().limit(4).toList(),
                List.of(),
                List.of(),
                List.of(),
                reviewIdeas,
                List.of(),
                List.of(),
                List.of(),
                report.sources().stream().map(DeepCompanyResearchReport.Source::url).filter(value -> !value.isBlank()).distinct().toList(),
                List.of("Черновик создан напрямую из глубокого отчёта без отдельного AI-пакета.")
        );
        return new PackEnvelope(null, pack);
    }

    private Optional<PackEnvelope> contentPack(Long companyId, Long contentPackJobId) {
        return contentPackEntity(companyId, contentPackJobId)
                .filter(entity -> entity.getPackJson() != null && !entity.getPackJson().isBlank())
                .map(entity -> new PackEnvelope(entity.getId(), readPack(entity.getPackJson())));
    }

    private Optional<ReputationContentPackJobEntity> contentPackEntity(Long companyId, Long contentPackJobId) {
        if (contentPackJobId != null && contentPackJobId > 0) {
            return contentPackJobRepository.findById(contentPackJobId)
                    .filter(entity -> entity.getCompanyId().equals(companyId));
        }
        return contentPackJobRepository.findByCompanyId(companyId);
    }

    private Optional<ReportEnvelope> deepReport(Long companyId, Long deepReportJobId) {
        Optional<ReputationDeepReportJobEntity> entity = deepReportJobId != null && deepReportJobId > 0
                ? deepReportJobRepository.findById(deepReportJobId).filter(job -> job.getCompanyId().equals(companyId))
                : deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream().findFirst();
        return entity
                .filter(job -> job.getReportJson() != null && !job.getReportJson().isBlank())
                .map(job -> new ReportEnvelope(job.getId(), readDeepReport(job.getReportJson())));
    }

    private ReputationSingleReviewDraftResult localResult(
            Long companyId,
            ReportEnvelope reportEnvelope,
            PackEnvelope packEnvelope,
            ReputationSingleReviewDraftRequest request,
            String idea,
            List<String> facts
    ) {
        ReputationContentPack pack = packEnvelope.pack();
        String company = firstNonBlank(pack.researchSnapshot().companyName(), reportEnvelope.report().companyName(), "компанию");
        String city = firstNonBlank(pack.researchSnapshot().city(), reportEnvelope.report().city(), "городе");
        String rawIdea = firstNonBlank(cleanReviewIdeaText(idea), "услуга компании")
                .replaceAll("[.。]+$", "")
                .trim();
        String cleanIdea = fallbackDraftPhrase(rawIdea);
        String focusSentence = localIdeaFocusSentence(rawIdea);
        List<String> safeFacts = localReviewFacts(facts, rawIdea);
        List<String> draftFacts = new ArrayList<>();
        if (!cleanIdea.isBlank()) {
            draftFacts.add(cleanIdea);
        }
        draftFacts.addAll(safeFacts.stream()
                .map(this::fallbackDraftPhrase)
                .filter(value -> !value.isBlank())
                .toList());
        draftFacts = draftFacts.stream().distinct().toList();
        if (draftFacts.isEmpty()) {
            draftFacts = List.of(cleanIdea);
        }
        String first = stripTrailingSentenceDot(draftFacts.getFirst());
        String second = stripTrailingSentenceDot(draftFacts.get(Math.min(1, draftFacts.size() - 1)));
        String third = stripTrailingSentenceDot(draftFacts.get(Math.min(2, draftFacts.size() - 1)));
        String topic = fallbackTopicLabel(cleanIdea);
        String firstTopic = fallbackTopicLabel(first);
        String secondTopic = fallbackTopicLabel(second);
        String thirdTopic = fallbackTopicLabel(third);
        String detailTopic = firstDifferentTopic(topic, firstTopic, secondTopic, thirdTopic);
        String topicSentence = localTopicSentence(topic, detailTopic);
        int variant = Math.floorMod((request.targetReviewId() == null ? ThreadLocalRandom.current().nextInt() : request.targetReviewId().hashCode()), 10);
        String domainDraft = localDomainDraft(topic, detailTopic, rawIdea, variant);
        String draft = domainDraft.isBlank()
                ? switch (variant) {
            case 0 -> "Нужно было разобраться без долгих общих разговоров. " + topicSentence;
            case 1 -> "Сначала сомневался, стоит ли сразу договариваться. " + topicSentence;
            case 2 -> topicSentence + " Обошлось без общих обещаний, по шагам было понятно, что дальше.";
            case 3 -> "Искал нормальный вариант без лишней суеты. " + topicSentence;
            case 4 -> "Понравилось, что разговор был предметный. " + topicSentence;
            case 5 -> "До обращения были вопросы, не хотелось решать наугад. " + topicSentence;
            case 6 -> "Выбирал между несколькими вариантами и после пояснений уже спокойнее определился. " + topicSentence;
            case 7 -> "Пришёл не за красивыми словами, а за понятными ответами. " + topicSentence;
            case 8 -> "После работы было не до долгих переписок. " + topicSentence;
            default -> "По итогу осталось ровное впечатление. " + topicSentence;
        }
                : domainDraft;
        if (domainDraft.isBlank()) {
            draft = enrichLocalDraftWithIdeaFocus(draft, focusSentence);
        }
        draft = cleanGeneratedLocalDraft(draft, company);
        draft = applyEmojiMode(draft, request.emojiMode());
        String sourceLabel = packEnvelope.jobId() == null ? "глубокому отчёту" : "текущему AI-пакету";
        List<String> safetyNotes = List.of(
                "OpenAI недоступен или не вернул результат: создан локальный вариант по " + sourceLabel + ".",
                "Перед публикацией клиент должен проверить, что реальный опыт совпадает с текстом."
        );
        return new ReputationSingleReviewDraftResult(
                companyId,
                company,
                reportEnvelope.jobId(),
                packEnvelope.jobId(),
                "local",
                "fallback",
                rawIdea,
                request.style(),
                draft,
                safeFacts,
                safetyNotes,
                reviewSafetyService.check(draft, safeFacts),
                LocalDateTime.now()
        );
    }

    private ReviewGenerationBrief reviewGenerationBrief(
            ReputationContentPack pack,
            DeepCompanyResearchReport report,
            List<ReputationBatchReviewDraftTarget> requestTargets
    ) {
        LinkedHashSet<String> services = new LinkedHashSet<>();
        LinkedHashSet<String> products = new LinkedHashSet<>();
        LinkedHashSet<String> prices = new LinkedHashSet<>();
        LinkedHashSet<String> advantages = new LinkedHashSet<>();
        LinkedHashSet<String> ideas = new LinkedHashSet<>();

        if (pack != null && pack.researchSnapshot() != null) {
            addBriefValues(products, pack.researchSnapshot().products(), 120);
            addBriefValues(advantages, pack.researchSnapshot().advantages(), 140);
        }
        if (pack != null && pack.companyProfile() != null) {
            addBriefValues(services, pack.companyProfile().products(), 120);
            addBriefValues(advantages, pack.companyProfile().advantages(), 140);
        }
        if (pack != null) {
            addBriefValues(advantages, pack.utp(), 160);
        }

        List<String> reportIdeas = reviewIdeasFromReport(report);
        reportIdeas.forEach(value -> addIdeaValue(ideas, value, 180));
        reportIdeas.forEach(value -> addBriefValues(services, serviceHintsFromIdea(value), 80));

        priceFactsFromReport(report).forEach(value -> addPriceFactValues(prices, value));
        commercialFacts(report, "").forEach(value -> {
            addPriceFactValues(prices, value);
            if (!isCustomerFacingBriefFact(value)) {
                return;
            }
            if (commercialDetail(value) && isConcreteCommercialItem(value)) {
                addBriefValue(products, value, 180);
            } else {
                addBriefValue(services, value, 140);
            }
        });

        if (requestTargets != null) {
            for (ReputationBatchReviewDraftTarget target : requestTargets) {
                contextValues(target.orderContext(), "Товар/услуга отзыва", "Общий товар/услуга заказа").forEach(value -> {
                    if (!isInternalReviewProduct(value)) {
                        addBriefValue(products, value, 140);
                    }
                });
            }
        }

        List<String> travelFromCenter = reportFactsByKeywords(
                report,
                List.of("от центра", "ехать", "минут", "дорог", "останов", "метро", "район"),
                8
        );
        List<String> employees = reportFactsByKeywords(
                report,
                List.of("сотрудник", "мастер", "менеджер", "администратор", "специалист"),
                10
        ).stream()
                .filter(value -> !value.toLowerCase(Locale.ROOT).replace('ё', 'е').contains("не раскрыт"))
                .toList();
        List<String> amenities = reportFactsByKeywords(
                report,
                List.of("зал ожид", "удобств", "кофе", "чай", "диван", "wi-fi", "wifi", "туалет", "детская", "комната"),
                12
        );
        List<String> parking = reportFactsByKeywords(
                report,
                List.of("парков", "стоян"),
                8
        );
        List<String> interestingFacts = reportFactsByKeywords(
                report,
                List.of("филиал", "вход", "этаж", "запись", "режим", "график", "рядом", "удобно", "наличии"),
                16
        );

        String company = firstNonBlank(
                pack == null || pack.researchSnapshot() == null ? "" : pack.researchSnapshot().companyName(),
                report.companyName()
        );
        String city = firstNonBlank(
                pack == null || pack.researchSnapshot() == null ? "" : pack.researchSnapshot().city(),
                report.city()
        );
        String category = firstNonBlank(
                pack == null || pack.researchSnapshot() == null ? "" : pack.researchSnapshot().subCategory(),
                pack == null || pack.researchSnapshot() == null ? "" : pack.researchSnapshot().category(),
                pack == null || pack.companyProfile() == null ? "" : pack.companyProfile().category()
        );
        String businessType = businessType(
                category,
                services,
                products,
                advantages,
                ideas,
                report
        );

        return new ReviewGenerationBrief(
                company,
                city,
                category,
                businessType,
                services.stream().limit(18).toList(),
                products.stream().limit(18).toList(),
                prices.stream().limit(12).toList(),
                advantages.stream().limit(18).toList(),
                ideas.stream().limit(30).toList(),
                travelFromCenter,
                employees,
                amenities,
                parking,
                interestingFacts,
                allowedScenarioTypes(businessType)
        );
    }

    private String businessType(
            String category,
            LinkedHashSet<String> services,
            LinkedHashSet<String> products,
            LinkedHashSet<String> advantages,
            LinkedHashSet<String> ideas,
            DeepCompanyResearchReport report
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append(category == null ? "" : category).append(' ');
        List.of(services, products, advantages, ideas).forEach(values -> {
            if (values != null) {
                values.stream().limit(24).forEach(value -> builder.append(value).append(' '));
            }
        });
        if (report != null) {
            builder.append(report.companyName()).append(' ')
                    .append(report.city()).append(' ')
                    .append(shortText(report.reportMarkdown(), 1200));
        }
        String text = builder.toString().toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (containsAny(text, List.of(
                "квест", "лазертаг", "аниматор", "актер", "актер", "день рождения",
                "детский праздник", "хоррор", "прятки", "нерф", "развлеч"
        ))) {
            return "entertainment";
        }
        if (containsAny(text, List.of(
                "автосервис", "авто-сервис", "ремонт авто", "ремонт автомобил",
                "диагностика авто", "диагностика автомобил", "подготовка авто", "подготовка автомобил",
                "ходов", "подвес", "гбц", "ремонт стартера", "ремонт генератора",
                "замена масла", "техобслуж", "подбор запчастей"
        ))) {
            return "auto_service";
        }
        if (containsAny(text, List.of(
                "штукатур", "стяжк", "white box", "вайт бокс", "новостройк",
                "приемка квартиры", "приёмка квартиры", "застройщик", "замер", "смет",
                "перегородк", "отделоч", "прораб", "объект", "укрытие", "подъезд/этаж",
                "охран", "сигнализац", "видеонаблюден", "пультов", "маникюр",
                "педикюр", "наращиван", "nail", "ногт", "памятник", "гранит"
        ))) {
            return "local_service";
        }
        if (containsAny(text, List.of(
                "клиник", "медицин", "стоматолог", "врач", "лечение",
                "пациент", "анализ", "диагноз", "прием врача", "приём врача"
        ))) {
            return "clinic";
        }
        if (containsAny(text, List.of(
                "ресторан", "кафе", "кофейн", "бар", "банкет", "завтрак",
                "ужин", "меню", "блюд", "доставка еды", "кейтеринг"
        ))) {
            return "restaurant";
        }
        if (containsAny(text, List.of(
                "магазин", "ассортимент", "покупк", "продаж", "самовывоз",
                "доставка", "возврат", "гарантия", "бренд", "товар"
        ))) {
            return "shop";
        }
        if (containsAny(text, List.of(
                "b2b", "поставка", "договор", "счет", "счёт", "юрлиц", "монтаж",
                "производств", "партия", "опт", "документ", "подряд"
        ))) {
            return "b2b";
        }
        if (containsAny(text, List.of(
                "курсы", "обучен", "школа", "репетитор", "тренинг", "занятия",
                "ученик", "преподавател"
        ))) {
            return "education";
        }
        if (containsAny(text, List.of(
                "ремонт", "сервис", "услуг", "мастер", "запись", "выезд",
                "замер", "установк"
        ))) {
            return "local_service";
        }
        return "other";
    }

    private List<String> allowedScenarioTypes(String businessType) {
        return switch (businessType) {
            case "auto_service" -> List.of(
                    "диагностика", "ремонт", "плановое ТО", "подбор запчастей",
                    "срочное обращение", "подготовка к поездке", "повторное обращение"
            );
            case "entertainment" -> List.of(
                    "день рождения", "квест", "активная игра", "чайная зона",
                    "подбор сценария", "бронирование", "праздник для подростков"
            );
            case "clinic" -> List.of(
                    "запись на прием", "первичная консультация", "понятное объяснение",
                    "организация визита", "работа администратора", "наблюдение без обещаний результата"
            );
            case "restaurant" -> List.of(
                    "завтрак или ужин", "семейный визит", "банкет", "доставка",
                    "обслуживание", "блюдо или меню", "ожидание и посадка"
            );
            case "shop" -> List.of(
                    "выбор товара", "консультация", "наличие", "доставка",
                    "самовывоз", "оплата", "возврат или гарантия"
            );
            case "b2b" -> List.of(
                    "поставка", "согласование", "договор", "документы",
                    "сроки", "монтаж", "повторный заказ"
            );
            case "local_service" -> List.of(
                    "первичное обращение", "замер или консультация", "смета",
                    "выполнение работ", "приемка результата", "договор", "доработка"
            );
            case "education" -> List.of(
                    "первое занятие", "выбор программы", "преподаватель",
                    "расписание", "прогресс без гарантий", "организация обучения"
            );
            default -> List.of(
                    "первичное обращение", "выбор услуги", "консультация",
                    "запись", "результат обращения", "повторное обращение"
            );
        };
    }

    private List<String> clientMustConfirm(String businessType, String theme, List<String> facts) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        String text = (theme + " " + String.join(" ", facts)).toLowerCase(Locale.ROOT).replace('ё', 'е');
        switch (businessType) {
            case "auto_service" -> {
                addConfirm(values, "какая машина была у клиента");
                if (containsAny(text, List.of("запчаст", "детал", "замен", "ремонт"))) {
                    addConfirm(values, "какие детали или запчасти реально меняли");
                }
                if (containsAny(text, List.of("цен", "стоим", "руб", "срок", "быстро"))) {
                    addConfirm(values, "точную стоимость и сроки работ");
                }
            }
            case "entertainment" -> {
                addConfirm(values, "какой сценарий или формат выбрали");
                if (containsAny(text, List.of("дет", "возраст", "день рождения", "праздник"))) {
                    addConfirm(values, "возраст участников и сколько детей было");
                }
                if (containsAny(text, List.of("чай", "торт", "еда", "кейтеринг", "фото"))) {
                    addConfirm(values, "использовали ли чайную зону, еду или фотосъемку");
                }
            }
            case "clinic" -> {
                addConfirm(values, "какой специалист или услуга были на визите");
                addConfirm(values, "не описывает ли текст неподтвержденный медицинский результат");
            }
            case "restaurant" -> {
                addConfirm(values, "какие блюда или формат визита были на самом деле");
                addConfirm(values, "точные цены, скидки и условия брони");
            }
            case "shop" -> {
                addConfirm(values, "конкретный товар, бренд или модель");
                addConfirm(values, "наличие, цену, доставку, возврат или гарантию");
            }
            case "b2b" -> {
                addConfirm(values, "сроки, объем работ или поставки");
                addConfirm(values, "документы, договор и условия оплаты");
            }
            case "local_service" -> {
                addConfirm(values, "какая услуга и объект были у клиента");
                if (containsAny(text, List.of("мастер", "преподав", "сотрудник", "специалист"))) {
                    addConfirm(values, "конкретного мастера или преподавателя, если имя будет упоминаться");
                }
                if (containsAny(text, List.of("цен", "стоим", "материал", "срок", "смет", "договор"))) {
                    addConfirm(values, "точный объем работ, материалы, сроки и стоимость");
                }
            }
            default -> addConfirm(values, "личный опыт клиента и использованные факты");
        }
        return values.stream().limit(5).toList();
    }

    private void addConfirm(LinkedHashSet<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value.trim());
        }
    }

    private List<ReviewGenerationSlot> reviewGenerationSlots(
            ReviewGenerationBrief brief,
            List<ReputationBatchReviewDraftTarget> requestTargets
    ) {
        List<ReputationBatchReviewDraftTarget> targets = requestTargets == null ? List.of() : requestTargets;
        List<String> structures = List.of(
                "начать с конкретной задачи; дальше раскрыть, какой один момент помог её закрыть",
                "начать с личной причины обращения; дальше дать одну фактическую деталь без лишней драматизации",
                "начать с результата; затем коротко объяснить, почему этот результат был важен",
                "начать с результата спустя время; дальше показать, что клиент проверил впечатление в деле",
                "начать с ограничения по времени без точных дат; дальше показать, как это повлияло на выбор",
                "начать с ограничения по бюджету без сумм; дальше раскрыть, что помогло не выбрать лишнее",
                "начать с выбора между вариантами; дальше объяснить, где стала понятна разница",
                "начать с конкретного вопроса клиента; дальше показать полученный ответ через опыт",
                "начать с непонимания темы; дальше связать отзыв с простым объяснением",
                "начать с просьбы объяснить простыми словами; дальше показать, что стало яснее",
                "начать с сомнения; дальше раскрыть, что именно сняло осторожность",
                "начать с осторожного отношения к цене; дальше показать, как клиент понял состав услуги",
                "начать со страха навязанных услуг; дальше показать, что лишнее не давило на решение",
                "начать с прошлого неудачного опыта без названий других компаний; дальше показать отличие этого визита",
                "начать с повторного обращения; дальше показать, что осталось стабильным во втором опыте",
                "начать с рекомендации; дальше показать, что клиент всё равно проверял детали сам",
                "начать с отложенного решения; дальше показать, почему вопрос оказался проще, чем ожидалось",
                "начать с желания избежать суеты; дальше раскрыть один организационный момент",
                "начать с бытовой ситуации; дальше связать её с услугой, ожиданием или записью",
                "начать с дороги или удобства; дальше показать, что клиент заранее уточнял",
                "начать с записи, брони или оформления; дальше показать, что стало понятно до визита",
                "начать с ожидания ответа; дальше раскрыть, как ответ повлиял на решение",
                "начать с удалённого этапа; дальше показать, какая часть вопроса закрылась без личного визита",
                "начать со сметы, договора или условий; дальше раскрыть один пункт, который клиент проверял",
                "начать с проверки деталей перед запуском; дальше показать, почему сверка была полезна",
                "начать с процесса общения; дальше раскрыть тон: не торопили, отвечали по делу или дали время подумать",
                "начать с конкретной детали сервиса; дальше показать, как она повлияла на впечатление",
                "начать с маленького минуса без претензии; дальше показать, как его компенсировали или объяснили",
                "начать с исправления небольшого недочёта; дальше показать, что вопрос закрыли спокойно",
                "начать с финальной проверки; дальше связать её с тем, что клиент принимал результат осознанно",
                "начать с приёмки результата; дальше показать одну вещь, которую проверили на месте",
                "начать с совпадения обещаний и факта без точных гарантий; дальше дать один пример",
                "начать с неожиданно полезной детали; дальше показать, почему она запомнилась",
                "начать с участия близких без личных данных; дальше показать, как это влияло на выбор",
                "начать с короткой благодарности за конкретику; дальше раскрыть, какая конкретика помогла",
                "начать с нейтрального делового вывода; дальше дать одну деталь без восторга",
                "начать с практичной задачи без эмоций; дальше показать путь до результата",
                "начать со сравнения условий; дальше раскрыть, какой критерий оказался главным",
                "начать с того, что важно было не переделывать; дальше показать, что клиент проверил заранее",
                "начать с вопроса после услуги; дальше показать, что поддержка или уточнение закрыли остаточный вопрос"
        );
        List<String> lengths = List.of(
                "2 коротких предложения",
                "3-4 предложения",
                "1-2 предложения, очень лаконично",
                "5-6 предложений, мини-история",
                "3 предложения без рекламных оценок",
                "отзыв из 1 предложения"
        );
        List<String> tones = List.of(
                "спокойный практичный",
                "разговорный, немного неровный",
                "сдержанно благодарный",
                "чуть придирчивый, но довольный",
                "деловой без эмоций"
        );

        List<ReviewGenerationSlot> result = new ArrayList<>();
        List<String> randomizedIdeas = randomizedReviewIdeas(brief.reviewIdeas(), targets.size());
        List<String> structurePlan = randomizedCycle(structures, targets.size());
        List<String> lengthPlan = randomizedCycle(lengths, targets.size());
        List<String> tonePlan = randomizedCycle(tones, targets.size());
        List<String> employeeAnchors = confirmedEmployeeNameAnchors(brief.employees());
        boolean employeeAnchorUsed = false;
        for (int index = 0; index < targets.size(); index++) {
            ReputationBatchReviewDraftTarget target = targets.get(index);
            String reportIdea = pick(randomizedIdeas, index);
            String theme = firstNonBlank(
                    safeSlotTheme(reportIdea),
                    "живой отзыв по фактам заказа"
            );
            List<String> themeServiceHints = serviceHintsFromIdea(theme);
            List<String> focusedThemeHints = focusedServiceHints(themeServiceHints);
            String service = firstNonBlank(
                    pickMatching(focusedThemeHints, theme),
                    pick(focusedThemeHints, 0),
                    pickMatching(themeServiceHints, theme),
                    pick(themeServiceHints, 0),
                    pickMatching(brief.services(), theme),
                    pick(brief.services(), index)
            );
            String product = pickConcreteProduct(brief.products(), brief.services(), theme, service, index);
            String price = firstNonBlank(
                    pickMatching(brief.prices(), theme),
                    pickMatching(brief.prices(), service),
                    pick(brief.prices(), index)
            );
            String advantage = firstNonBlank(
                    pickMatching(brief.advantages(), theme),
                    pickMatching(brief.advantages(), service),
                    pick(brief.advantages(), index + 2)
            );
            List<String> extraDetails = extraBriefDetails(brief);
            String extra = firstNonBlank(
                    pickMatching(extraDetails, theme),
                    pickMatching(extraDetails, service),
                    pick(extraDetails, index)
            );
            String employeeAnchor = "";
            if (!employeeAnchorUsed
                    && !employeeAnchors.isEmpty()
                    && shouldAttachEmployeeAnchor(index, targets.size(), theme, service)) {
                employeeAnchor = pick(employeeAnchors, index);
                employeeAnchorUsed = true;
            }
            List<String> mustUse = slotMustUseFacts(
                    theme,
                    focusedThemeHints,
                    service,
                    product,
                    price,
                    advantage,
                    extra,
                    employeeAnchor
            );
            List<String> mayUse = mergePriorityFacts(
                    List.of(service, product, price, advantage, extra, employeeAnchor),
                    extraDetails,
                    10
            );
            List<String> clientMustConfirm = clientMustConfirm(
                    brief.businessType(),
                    theme,
                    mergePriorityFacts(mustUse, mayUse, 12)
            );
            result.add(new ReviewGenerationSlot(
                    target.reviewId(),
                    theme,
                    service,
                    product,
                    price,
                    advantage,
                    extra,
                    pick(tonePlan, index),
                    pick(lengthPlan, index),
                    pick(structurePlan, index),
                    mustUse,
                    mayUse,
                    clientMustConfirm,
                    target.previousDraft()
            ));
        }
        return result;
    }

    private List<String> focusedServiceHints(List<String> hints) {
        if (hints == null || hints.size() <= 1) {
            return hints == null ? List.of() : hints;
        }
        List<String> focused = hints.stream()
                .filter(value -> !isGenericFocusedServiceHint(value))
                .toList();
        return focused.isEmpty() ? hints : focused;
    }

    private String safeSlotTheme(String value) {
        String clean = cleanReviewIdeaText(value);
        if (clean.isBlank()) {
            return "";
        }
        clean = clean.replaceAll(
                        "(?iu)^(.*?)(?:\\s+[—-]\\s+[^:.;!?]{0,220}(?:после\\s+подтвержд|подтвердить|подтвержд[её]нн)[^:.;!?]*)(:.*)$",
                        "$1$2"
                )
                .replaceAll("(?iu)\\s+[—-]\\s+[^:.;!?]{0,220}(?:после\\s+подтвержд|подтвердить|подтвержд[её]нн)[^:.;!?]*$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return stripBoundaryQuotes(clean);
    }

    private boolean isGenericFocusedServiceHint(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
        return clean.equals("изготовление памятников");
    }

    private List<String> slotMustUseFacts(
            String theme,
            List<String> focusedThemeHints,
            String service,
            String product,
            String price,
            String advantage,
            String extra,
            String employeeAnchor
    ) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (focusedThemeHints != null) {
            focusedThemeHints.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .limit(4)
                    .forEach(values::add);
        }
        addSlotMustUseIfRelevant(values, product, theme, service);
        addSlotMustUseIfRelevant(values, price, theme, service);
        addSlotMustUseIfRelevant(values, advantage, theme, service);
        addSlotMustUseIfRelevant(values, extra, theme, service);
        if (employeeAnchor != null && !employeeAnchor.isBlank()) {
            values.add(employeeAnchor);
        }
        if (values.isEmpty() && service != null && !service.isBlank()) {
            values.add(service);
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .limit(5)
                .toList();
    }

    private List<String> confirmedEmployeeNameAnchors(List<String> employees) {
        if (employees == null || employees.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> anchors = new LinkedHashSet<>();
        for (String employeeFact : employees) {
            String clean = cleanReviewInputText(employeeFact);
            if (clean.isBlank() || requiresClientConfirmationFact(clean)) {
                continue;
            }
            if (!hasEmployeeRoleContext(clean)) {
                continue;
            }
            String role = employeeRole(clean);
            Matcher matcher = Pattern.compile("(?<![\\p{L}\\p{N}])[А-ЯЁ][а-яё]{2,}(?![\\p{L}\\p{N}])").matcher(clean);
            while (matcher.find()) {
                String name = matcher.group();
                if (looksLikeEmployeeName(name)) {
                    anchors.add(role + " " + name);
                }
                if (anchors.size() >= 5) {
                    return List.copyOf(anchors);
                }
            }
        }
        return List.copyOf(anchors);
    }

    private String employeeRole(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (clean.contains("преподав")) {
            return "преподаватель";
        }
        if (clean.contains("администратор")) {
            return "администратор";
        }
        if (clean.contains("менеджер")) {
            return "менеджер";
        }
        if (clean.contains("мастер")) {
            return "мастер";
        }
        return "специалист";
    }

    private boolean hasEmployeeRoleContext(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return containsAny(clean, List.of(
                "сотрудник", "сотрудниц",
                "мастер", "мастериц",
                "преподав",
                "администратор",
                "менеджер",
                "специалист"
        ));
    }

    private boolean looksLikeEmployeeName(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) {
            return false;
        }
        String lower = clean.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return clean.matches("[А-ЯЁ][а-яё]{2,}")
                && !Set.of(
                "есть", "сотрудники", "сотрудник", "мастера", "мастер", "менеджер",
                "администратор", "преподаватель", "специалист", "специалисты",
                "район", "правобережный", "удобства", "парковка", "состав",
                "обучение", "курс", "курсы", "школа", "студия", "зона"
        ).contains(lower);
    }

    private boolean shouldAttachEmployeeAnchor(int index, int total, String theme, String service) {
        String text = (theme + " " + service).toLowerCase(Locale.ROOT).replace('ё', 'е');
        return containsAny(text, List.of("мастер", "преподав", "сотрудник", "специалист", "администратор", "коммуникац", "подход"))
                || index == Math.max(0, total - 1);
    }

    private void addSlotMustUseIfRelevant(LinkedHashSet<String> values, String value, String theme, String service) {
        if (value == null || value.isBlank()) {
            return;
        }
        String clean = cleanReviewInputText(value);
        if (clean.isBlank()) {
            return;
        }
        if (requiresClientConfirmationFact(clean)) {
            return;
        }
        List<String> employeeAnchors = confirmedEmployeeNameAnchors(List.of(clean));
        if (!employeeAnchors.isEmpty()) {
            values.add(employeeAnchors.getFirst());
            return;
        }
        String context = firstNonBlank(theme, service);
        Set<String> keywords = factKeywords(context);
        if (factScore(clean, keywords) > 0
                || normalizedContains(theme, clean)
                || normalizedContains(clean, theme)
                || isConcreteSlotMustUseDetail(clean)) {
            values.add(clean);
        }
    }

    private boolean isConcreteSlotMustUseDetail(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return hasPrice(clean)
                || clean.matches(".*(пакет|программ|формат|квест|экзорц|сталкер|тюрьм|фотоовал|фото на стекле).*")
                || clean.matches(".*(макет|предоплат|доставк|установк|фотоотчет|фотоотчёт).*");
    }

    private List<String> randomizedCycle(List<String> values, int preferredCount) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> shuffled = new ArrayList<>(values);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = shuffled.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            String temp = shuffled.get(i);
            shuffled.set(i, shuffled.get(j));
            shuffled.set(j, temp);
        }
        if (preferredCount <= shuffled.size()) {
            return shuffled;
        }
        List<String> result = new ArrayList<>(preferredCount);
        while (result.size() < preferredCount) {
            result.addAll(shuffled);
        }
        return result;
    }

    private List<String> randomizedReviewIdeas(List<String> ideas, int preferredCount) {
        if (ideas == null || ideas.size() <= 1) {
            return ideas == null ? List.of() : ideas;
        }
        List<String> shuffled = new ArrayList<>(ideas);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = shuffled.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            String temp = shuffled.get(i);
            shuffled.set(i, shuffled.get(j));
            shuffled.set(j, temp);
        }
        List<String> diversified = new ArrayList<>();
        LinkedHashSet<String> usedKeys = new LinkedHashSet<>();
        for (String idea : shuffled) {
            String key = ideaScenarioKey(idea);
            if (diversified.size() < preferredCount && usedKeys.add(key)) {
                diversified.add(idea);
            }
        }
        for (String idea : shuffled) {
            if (!diversified.contains(idea)) {
                diversified.add(idea);
            }
        }

        if (diversified.equals(ideas)) {
            int rotation = random.nextInt(1, shuffled.size());
            List<String> rotated = new ArrayList<>(shuffled.size());
            rotated.addAll(diversified.subList(rotation, diversified.size()));
            rotated.addAll(diversified.subList(0, rotation));
            diversified = rotated;
        }
        return diversified;
    }

    private String ideaScenarioKey(String idea) {
        List<String> hints = serviceHintsFromIdea(idea);
        if (!hints.isEmpty()) {
            return hints.getFirst();
        }
        String clean = idea == null ? "" : idea.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return clean.replaceAll("[^\\p{L}\\p{N}]+", " ").trim().split("\\s+", 2)[0];
    }

    private List<AiSingleReviewDraftFactory.BatchDraftTarget> batchTargets(List<ReviewGenerationSlot> slots) {
        return slots.stream()
                .map(slot -> {
                    List<String> facts = mergePriorityFacts(slot.mustUse(), slot.mayUse(), 12);
                    return new AiSingleReviewDraftFactory.BatchDraftTarget(
                            slot.reviewId(),
                            slot.theme(),
                            slot.previousDraft(),
                            "",
                            facts,
                            firstNonBlank(slot.service(), slot.product(), slot.advantage(), slot.extraDetail(), slot.theme()),
                            slot.extraDetail(),
                            slot.mustUse(),
                            slot.structure(),
                            slot.length(),
                            slot.tone()
                    );
                })
                .toList();
    }

    private List<String> extraBriefDetails(ReviewGenerationBrief brief) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        addBriefValues(values, brief.travelFromCenter(), 160);
        addBriefValues(values, brief.employees(), 140);
        addBriefValues(values, brief.amenities(), 140);
        addBriefValues(values, brief.parking(), 140);
        addBriefValues(values, brief.interestingFacts(), 160);
        return values.stream().limit(20).toList();
    }

    private List<String> contextValues(String context, String... labels) {
        if (context == null || context.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Set<String> normalizedLabels = java.util.Arrays.stream(labels)
                .map(value -> value.toLowerCase(Locale.ROOT).replace('ё', 'е'))
                .collect(java.util.stream.Collectors.toSet());
        for (String rawLine : context.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine == null ? "" : rawLine.trim();
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String label = line.substring(0, separator).trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
            if (normalizedLabels.contains(label)) {
                String value = cleanReviewInputText(line.substring(separator + 1));
                if (!value.isBlank() && !isInternalRecommendation(value) && !isTechnicalContextFact(value)) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private List<String> reportFactsByKeywords(DeepCompanyResearchReport report, List<String> keywords, int limit) {
        if (report == null || report.sections() == null) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (DeepCompanyResearchReport.Section section : report.sections()) {
            String sectionText = (section.title() == null ? "" : section.title()) + " " + (section.body() == null ? "" : section.body());
            if (isInternalRecommendation(sectionText)) {
                continue;
            }
            for (String raw : section.body() == null ? List.<String>of() : section.body().lines().toList()) {
                for (String part : raw.split("(?<=[.!?])\\s+|[;•]")) {
                    String clean = cleanReviewInputText(part);
                    if (clean.length() >= 10
                            && containsAny(clean, keywords)
                            && !isInternalRecommendation(clean)
                            && !isTechnicalContextFact(clean)
                            && isCustomerFacingBriefFact(clean)) {
                        for (String candidate : briefValueCandidates(clean)) {
                            if (containsAny(candidate, keywords)
                                    && !isInternalRecommendation(candidate)
                                    && !isTechnicalContextFact(candidate)
                                    && isCustomerFacingBriefFact(candidate)) {
                                values.add(shortText(candidate, 180));
                            }
                        }
                    }
                }
            }
        }
        return values.stream().limit(limit).toList();
    }

    private void addBriefValues(LinkedHashSet<String> values, List<String> source, int limit) {
        if (source == null) {
            return;
        }
        source.forEach(value -> addBriefValue(values, value, limit));
    }

    private void addIdeaValues(LinkedHashSet<String> values, List<String> source, int limit) {
        if (source == null) {
            return;
        }
        source.forEach(value -> addIdeaValue(values, value, limit));
    }

    private void addIdeaValue(LinkedHashSet<String> values, String value, int limit) {
        String clean = safeSlotTheme(value);
        if (clean.length() >= 10
                && !isWeakTheme(clean)
                && !looksLikeWrittenReviewExample(clean)
                && !isInternalRecommendation(clean)
                && !isTechnicalContextFact(clean)) {
            values.add(shortText(clean, limit));
        }
    }

    private void addBriefValue(LinkedHashSet<String> values, String value, int limit) {
        for (String clean : briefValueCandidates(value)) {
            if (clean.length() >= 3
                    && !isInternalRecommendation(clean)
                    && !isTechnicalContextFact(clean)
                    && isCustomerFacingBriefFact(clean)) {
                values.add(shortText(clean, limit));
            }
        }
    }

    private List<String> briefValueCandidates(String value) {
        String clean = cleanReviewInputText(value);
        if (clean.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (clean.length() <= 130 && !clean.contains(";") && !clean.contains("|") && !clean.contains(":")) {
            result.add(stripBoundaryQuotes(clean));
        }
        for (String part : clean.split("[;|•]|(?<=[.!?])\\s+|:\\s+")) {
            String candidate = stripBoundaryQuotes(cleanReviewInputText(part));
            if (!candidate.isBlank() && candidate.length() <= 130) {
                result.add(candidate);
            }
        }
        return result.stream().distinct().toList();
    }

    private boolean isCustomerFacingBriefFact(String value) {
        String clean = value == null ? "" : value.trim();
        String lower = clean.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (clean.length() > 140 || lower.length() < 3) {
            return false;
        }
        if (!clean.matches(".*\\p{L}.*")
                || lower.replace(".", "").isBlank()
                || lower.equals("квест")
                || lower.equals("квест.")
                || lower.equals("авто-сервис")
                || lower.equals("авто-сервис.")
                || lower.equals("автосервис")
                || lower.equals("автосервис.")
                || clean.contains("|")
                || lower.contains("crm")
                || lower.contains("2гис")
                || lower.contains("2gis")
                || lower.contains("фламп")
                || lower.contains("картах")
                || lower.contains("карты")
                || lower.contains("показывает")
                || lower.contains("выгрузк")
                || lower.contains("источник")
                || lower.contains("уверенность")
                || lower.matches(".*\\b(?:low|medium|high)\\b.*")
                || lower.matches(".*\\+?\\d[\\d\\s()\\-]{7,}\\d.*")
                || lower.contains("телефон")
                || lower.contains("telegram")
                || lower.contains("vk ")
                || lower.contains("http")
                || lower.contains("не найден")
                || lower.contains("не подтвержден")
                || lower.contains("после подтвержд")
                || lower.contains("подтвердить ")
                || lower.contains("подтвердить:")
                || lower.contains("подтверждения состава")
                || lower.contains("подтвержденного сотрудника")
                || lower.contains("подтверждённого сотрудника")
                || lower.contains("требует проверки")
                || lower.contains("требуют проверки")
                || lower.contains("нужно ")
                || lower.contains("лучше ")
                || lower.contains("уточнить")
                || lower.contains("возможно")
                || lower.contains("нельзя")
                || lower.contains("проверить")
                || lower.contains("проверки")
                || lower.contains("сверить")
                || lower.contains("для карточки")
                || lower.contains("что собрать")
                || lower.contains("для сильного ai")
                || lower.contains("готова публиковать")
                || lower.startsWith("как ")
                || lower.startsWith("мы ")
                || lower.startsWith("у нас ")
                || lower.contains("в отзывах")
                || lower.contains("по отзывам")
                || lower.contains("большинство отзывов")
                || lower.contains("один клиент")
                || lower.contains("несколько отзывов")
                || lower.contains("говорят")
                || lower.contains("упомина")
                || lower.contains("публичн")
                || lower.contains("прайс недоступ")
                || lower.contains("цена воспринимается")
                || lower.contains("барьером")
                || lower.contains("официальн")
                || lower.contains("реквизит")
                || lower.contains("гаранти")
                || lower.contains("командой")
                || lower.contains("условного центра")
                || lower.contains("без live-навигации")
                || lower.contains("по прямой")
                || lower.contains("по дорогам")
                || lower.contains("отзывы показывают")
                || lower.contains("сценарии")
                || lower.contains("сильная тема")
                || lower.contains("сильная основа для текстов")
                || lower.contains("сильн...")
                || lower.contains("владельцам")
                || lower.contains("нет списка")
                || lower.contains("на сайте")
                || lower.contains("в карточках")
                || lower.startsWith("команда:")
                || lower.contains("имена мастеров")
                || lower.contains("кто отвечает")
                || lower.contains("если есть")
                || lower.contains("как заехать")
                || lower.contains("можно ли")
                || lower.contains("неясно")
                || lower.contains("публиковать")
                || lower.contains("добавить")
                || lower.contains("объяснять")
                || lower.contains("состав каждого")
                || lower.contains("что входит")
                || lower.contains("где ждать")
                || lower.contains("схемы проезда")
                || lower.contains("фото фасада")
                || lower.contains("наличие фото подтверждено")
                || lower.contains("содержание текстом не разобрано")
                || lower.startsWith("собрать фото")
                || lower.contains("интерьер/экстерьер")
                || lower.contains("единый список")
                || lower.contains("по каждому филиалу")
                || lower.contains("способы оплаты")
                || lower.contains("мир квестов")
                || lower.contains("фото экстерьера")
                || lower.contains("видео-схем")
                || lower.contains("нет достаточно")
                || lower.contains("недостаточно надеж")
                || lower.contains("старый/закрытый")
                || lower.contains("неактуаль")
                || lower.contains("по агрегатор")
                || lower.contains("агрегатор")
                || lower.contains("правила ниже")
                || lower.contains("точный вход")
                || lower.contains("режим по дням")
                || lower.contains("праздникам")
                || lower.contains("не являются")
                || lower.contains("не считать")
                || lower.contains("stroker")
                || lower.contains("дрифт")
                || lower.contains("событ")
                || lower.contains("найден только")
                || lower.contains("подтвержденный адрес")
                || lower.startsWith("адрес:")
                || lower.startsWith("- адрес:")
                || lower.matches("^(филиал|филиалы)\\.?$")
                || lower.startsWith("отзыв о ")) {
            return false;
        }
        return true;
    }

    private boolean isWeakTheme(String value) {
        String clean = cleanReviewInputText(value).toLowerCase(Locale.ROOT).replace('ё', 'е');
        return clean.isBlank()
                || isGenericBusinessDescriptor(clean)
                || isGenericServiceLabel(clean)
                || clean.equals("авто-сервис")
                || clean.equals("авто-сервис.")
                || clean.equals("автосервис")
                || clean.equals("автосервис.")
                || clean.equals("услуги")
                || clean.equals("услуги.")
                || clean.equals("товар")
                || clean.equals("товар.")
                || clean.equals("квест")
                || clean.equals("квест.")
                || clean.equals("развлечения")
                || clean.equals("развлечения.")
                || clean.equals("охрана")
                || clean.equals("охрана.")
                || clean.equals("ногти")
                || clean.equals("ногти.")
                || clean.equals("штукатурные работ")
                || clean.equals("штукатурные работ.")
                || clean.equals("штукатурные работы")
                || clean.equals("штукатурные работы.")
                || clean.matches(".*(^|\\s)авто-?сервис\\.?$");
    }

    private boolean isGenericBusinessDescriptor(String clean) {
        String normalized = clean == null ? "" : clean.replaceAll("[.。]+$", "").trim();
        if (normalized.isBlank() || hasCustomerExperienceSignal(normalized)) {
            return false;
        }
        return normalized.matches("^(?:компания|организация|фирма|предприятие|производство|мастерская|студия|школа|центр|сервис|салон)\\b.*")
                || normalized.matches(".*\\bкомпания\\s+по\\b.*");
    }

    private boolean isGenericServiceLabel(String clean) {
        String normalized = clean == null ? "" : clean.replaceAll("[.。]+$", "").trim();
        if (normalized.isBlank() || hasCustomerExperienceSignal(normalized)) {
            return false;
        }
        int words = normalized.split("[^\\p{L}\\p{N}]+").length;
        return words <= 4 && containsAny(normalized, List.of(
                "услуг", "ремонт", "изготовлен", "производств", "продаж", "монтаж",
                "установк", "обучен", "охран", "маникюр", "педикюр", "штукатур",
                "автосервис", "квест", "памятник", "ногт", "стяжк", "диагност"
        ));
    }

    private boolean hasCustomerExperienceSignal(String value) {
        return containsAny(value, List.of(
                "как ", "какие", "какой", "почему", "после", "перед", "сначала",
                "опыт", "выбор", "выбирал", "выбирали", "заказ", "заказывал",
                "обращен", "обращал", "визит", "соглас", "помог", "результат",
                "дистанцион", "семейн", "двойн", "повторн", "срочн", "под ключ",
                "договор", "приемк", "приёмк", "благоустрой", "ожидан", "дорог",
                "парков", "удобн", "коммуникац", "менеджер", "смет", "макет",
                "фотоотчет", "фотоотчёт", "материал", "образц"
        ));
    }

    private boolean isSimplePrice(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        return clean.matches("^\\d[\\d\\s]*(?:[,.]\\d+)?\\s*(?:₽|руб\\.?|р\\.?)$");
    }

    private void addPriceFactValues(LinkedHashSet<String> prices, String value) {
        if (prices == null || value == null || value.isBlank() || !hasPrice(value)) {
            return;
        }
        for (String candidate : priceFactCandidates(value)) {
            String clean = cleanReviewInputText(candidate);
            if (!clean.isBlank() && isUsablePriceFact(clean)) {
                prices.add(shortText(clean, 190));
            }
        }
    }

    private List<String> priceFactCandidates(String value) {
        String clean = cleanReviewInputText(value)
                .replaceAll("(?iu)(?:;\\s*)?(?:источник|source|уверенность|confidence)[:/\\s,;].*$", "")
                .replaceAll("(?iu);\\s*(?:сайт|официальный\\s+сайт|2гис|2gis|zoon|obiz)[^;]{0,80}(?:средн|высок|низк)[^;]{0,80}$", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.isBlank() || !hasPrice(clean)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        result.add(clean);
        for (String part : clean.split("\\s*[;•]\\s*")) {
            String candidate = part == null ? "" : part.trim();
            if (!candidate.isBlank() && hasPrice(candidate)) {
                result.add(candidate);
            }
        }
        return result.stream().distinct().toList();
    }

    private boolean isUsablePriceFact(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return hasPrice(clean)
                && clean.length() <= 220
                && !clean.contains("нет ")
                && !clean.contains("не ")
                && !clean.contains("не найден")
                && !clean.contains("не подтвержден")
                && !clean.contains("прайс недоступ")
                && !clean.contains("публичного прайса")
                && !clean.contains("цена воспринимается")
                && !clean.contains("большинство отзывов")
                && !clean.contains("один клиент");
    }

    private boolean isConcreteCommercialItem(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return clean.length() <= 130
                && !clean.contains("владельцам")
                && !clean.contains("отзывы")
                && !clean.contains("сценарии")
                && !clean.contains("не найден")
                && !clean.contains("нет ")
                && !clean.contains("лучше")
                && !clean.contains("провер")
                && !clean.contains("сверить");
    }

    private List<String> serviceHintsFromIdea(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        List<String> result = new ArrayList<>();
        if (clean.contains("дистанцион") || clean.contains("другого города")) {
            result.add("дистанционный заказ");
            if (containsAny(clean, List.of("макет", "смет", "фотоотчет", "фотоотчёт", "установк"))) {
                result.add("макет, смета и установка");
            }
        }
        if ((clean.contains("менеджер") || clean.contains("сопровожд"))
                && containsAny(clean, List.of("выбор", "бюджет", "вопрос", "срок"))) {
            result.add("сопровождение менеджера");
            result.add("выбор и бюджет");
        }
        if (clean.contains("под ключ")
                && containsAny(clean, List.of("договор", "предоплат", "доставк", "приемк", "приёмк", "установк"))) {
            result.add("заказ под ключ");
            result.add("договор и приемка");
        }
        if (containsAny(clean, List.of(
                "памятник", "гранит", "фотоовал", "фото на стекле", "портрет",
                "надпис", "цветник", "отмостк", "плиты", "благоустрой"
        ))) {
            if (clean.contains("дистанцион") || clean.contains("другого города")) {
                result.add("дистанционный заказ памятника");
                result.add("макет и смета");
                result.add("фотоотчет по установке");
            }
            if (clean.contains("менеджер") || clean.contains("сопровожд") || clean.contains("бюджет")) {
                result.add("сопровождение менеджера");
                result.add("выбор и бюджет");
            }
            if (clean.contains("под ключ")) {
                result.add("заказ памятника под ключ");
                result.add("договор и приемка");
            }
            if (clean.contains("стар") || clean.contains("восстанов") || clean.contains("ремонт")) {
                result.add("ремонт старого памятника");
                result.add("восстановление внешнего вида");
            }
            if (clean.contains("семейн") || clean.contains("двойн") || clean.contains("форма")
                    || clean.contains("надпис") || clean.contains("композици")) {
                result.add("семейный памятник");
                result.add("форма, надписи и композиция");
            }
            if (clean.contains("фотоовал") || clean.contains("фото на стекле") || clean.contains("портрет")
                    || clean.contains("изображен")) {
                result.add("фотоовал или портрет");
                result.add("качество изображения");
            }
            if (clean.contains("благоустрой") || clean.contains("цветник")
                    || clean.contains("отмостк") || clean.contains("плиты") || clean.contains("участк")) {
                result.add("благоустройство участка");
                result.add("аккуратность после монтажа");
            }
            if (clean.contains("образц") || clean.contains("материал") || clean.contains("выставк")
                    || clean.contains("гранит") || clean.contains("камн")) {
                result.add("выбор гранита и образцов");
            }
        }
        if (clean.contains("первичн") || clean.contains("диагност")) {
            result.add("первичная диагностика");
        }
        if (clean.contains("ходов") || clean.contains("стук") || clean.contains("скрип") || clean.contains("подвес")) {
            result.add("ремонт ходовой");
            result.add("диагностика подвески");
        }
        if (clean.contains("масл") || hasStandaloneToken(clean, "то") || clean.contains("техобслуж") || clean.contains("обслужив")) {
            result.add("замена масла");
            result.add("комплексное ТО");
        }
        if (clean.contains("двигател") || clean.contains("гбц")) {
            result.add("ремонт двигателя");
            result.add("дефектовка двигателя");
        }
        if (clean.contains("стартер")) {
            result.add("ремонт стартера");
        }
        if (clean.contains("генератор")) {
            result.add("ремонт генератора");
        }
        if (clean.contains("запчаст")) {
            result.add("подбор запчастей");
        }
        if (clean.contains("дальн") || clean.contains("поезд") || clean.contains("дорог")) {
            result.add("подготовка авто к дальней поездке");
        }
        if (clean.contains("день рождения") || clean.contains("дня рождения") || clean.contains("др ") || clean.contains("праздник")) {
            result.add("дни рождения под ключ");
        }
        if (clean.contains("квест") && (clean.contains("актер") || clean.contains("актёр") || clean.contains("персонаж"))) {
            result.add("квесты с актерами");
        }
        if (clean.contains("детск") || clean.contains("ребен") || clean.contains("ребён") || clean.contains("детей")) {
            result.add("детские квесты");
        }
        if (clean.contains("хоррор") || clean.contains("страх") || clean.contains("страш") || clean.contains("хардкор")) {
            result.add("хоррор-квесты");
        }
        if (clean.contains("лазертаг")) {
            result.add("лазертаг");
        }
        if (hasStandaloneToken(clean, "оно")) {
            result.add("квест «Оно»");
        }
        if (clean.contains("тюрьм")) {
            result.add("квест «Тюрьма»");
        }
        if (clean.contains("экзорц")) {
            result.add("квест «Экзорцизм»");
        }
        if (clean.contains("сталкер")) {
            result.add("квест «Сталкер»");
        }
        if (clean.contains("космическ")) {
            result.add("квест «Космический корабль»");
        }
        if (clean.contains("штукатур") || clean.contains("механизирован")) {
            result.add("механизированная штукатурка");
        }
        if (clean.contains("стяжк")) {
            result.add("полусухая стяжка пола");
        }
        if (clean.contains("white box") || clean.contains("вайт бокс")) {
            result.add("White Box");
        }
        if (clean.contains("замер") || clean.contains("смет")) {
            result.add("замер и смета");
        }
        if ((clean.contains("приемк") || clean.contains("приёмк") || clean.contains("застройщик"))
                && containsAny(clean, List.of("квартир", "застройщик", "новостройк", "white box", "вайт бокс"))) {
            result.add("приемка квартиры");
        }
        if ((clean.contains("приемк") || clean.contains("приёмк"))
                && !containsAny(clean, List.of("квартир", "застройщик", "новостройк", "white box", "вайт бокс"))
                && containsAny(clean, List.of("результат", "установк", "монтаж", "работ", "объект", "готов", "финальн", "участк"))) {
            result.add("приемка результата");
        }
        if (clean.contains("договор") || clean.contains("безнал")) {
            result.add("работа по договору");
        }
        if (clean.contains("охран")) {
            result.add("частная охранная организация");
        }
        if (clean.contains("сигнализац")) {
            result.add("охранная сигнализация");
        }
        if (clean.contains("видеонаблюден")) {
            result.add("видеонаблюдение");
        }
        if (clean.contains("маникюр") || clean.contains("ногт")) {
            result.add("маникюр");
        }
        if (clean.contains("педикюр")) {
            result.add("педикюр");
        }
        if (clean.contains("наращиван")) {
            result.add("наращивание ногтей");
        }
        if (clean.contains("памятник")) {
            result.add("изготовление памятников");
        }
        return result;
    }

    private boolean hasStandaloneToken(String value, String token) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        String quoted = java.util.regex.Pattern.quote(token.toLowerCase(Locale.ROOT).replace('ё', 'е'));
        return clean.matches(".*(^|[^\\p{L}\\p{N}])" + quoted + "([^\\p{L}\\p{N}]|$).*");
    }

    private boolean containsAny(String value, List<String> keywords) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return keywords.stream()
                .map(keyword -> keyword.toLowerCase(Locale.ROOT).replace('ё', 'е'))
                .anyMatch(clean::contains);
    }

    private boolean hasPrice(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return clean.matches(".*\\b\\d[\\d\\s]*(?:[,.]\\d+)?\\s*(?:₽|руб\\.?|р\\.?|тыс\\.?).*")
                || clean.matches(".*(цена|стоим|прайс|тариф|от\\s+\\d|до\\s+\\d).*");
    }

    private boolean isInternalReviewProduct(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return clean.contains("отзыв")
                || clean.contains("2гис")
                || clean.contains("2gis")
                || clean.contains("яндекс")
                || clean.contains("google")
                || clean.contains("карты")
                || clean.contains("репутац");
    }
    private String batchIdea(
            String baseIdea,
            String reportIdea,
            String commercialAnchor,
            String utpAnchor,
            int index
    ) {
        List<String> parts = new ArrayList<>();
        addNonBlank(parts, cleanReviewInputText(baseIdea));
        if (index % 3 == 0) {
            addNonBlank(parts, cleanReviewInputText(firstNonBlank(commercialAnchor, reportIdea)));
        } else if (index % 3 == 1) {
            addNonBlank(parts, cleanReviewInputText(firstNonBlank(utpAnchor, commercialAnchor)));
        } else {
            addNonBlank(parts, cleanReviewInputText(firstNonBlank(reportIdea, commercialAnchor)));
        }
        return parts.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> shortText(value, 260))
                .distinct()
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private List<String> commercialAnchors(ReputationContentPack pack, DeepCompanyResearchReport report) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (pack != null && pack.researchSnapshot() != null) {
            pack.researchSnapshot().products().forEach(value -> addCleanAnchor(values, value, 220));
        }
        if (pack != null && pack.companyProfile() != null) {
            pack.companyProfile().products().forEach(value -> addCleanAnchor(values, value, 220));
        }
        commercialFacts(report, "").forEach(value -> addCleanAnchor(values, value, 240));
        return values.stream().limit(24).toList();
    }

    private List<String> utpAnchors(ReputationContentPack pack, DeepCompanyResearchReport report) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (pack != null) {
            pack.utp().forEach(value -> addCleanAnchor(values, value, 220));
            if (pack.companyProfile() != null) {
                pack.companyProfile().advantages().forEach(value -> addCleanAnchor(values, value, 220));
            }
            if (pack.researchSnapshot() != null) {
                pack.researchSnapshot().advantages().forEach(value -> addCleanAnchor(values, value, 220));
            }
        }
        return values.stream().limit(24).toList();
    }

    private void addCleanAnchor(LinkedHashSet<String> values, String value, int limit) {
        String clean = cleanReviewInputText(cleanMarkdownText(value));
        if (clean.length() >= 10 && !isInternalRecommendation(clean)) {
            values.add(shortText(clean, limit));
        }
    }

    private List<String> localReviewFacts(List<String> facts, String idea) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (facts != null) {
            facts.stream()
                    .map(this::cleanReviewInputText)
                    .filter(value -> value.length() >= 10)
                    .filter(value -> !isInternalRecommendation(value))
                    .filter(value -> !isTechnicalContextFact(value))
                    .limit(8)
                    .forEach(values::add);
        }
        if (values.isEmpty()) {
            values.add(idea);
        }
        return values.stream().limit(8).toList();
    }

    private String localIdeaFocusSentence(String idea) {
        String focus = ideaFocusTail(idea);
        if (focus.isBlank()) {
            return "";
        }
        String sentence = naturalLocalExperienceSentence(focus);
        if (sentence.isBlank()) {
            return "";
        }
        return sentence;
    }

    private String naturalLocalExperienceSentence(String value) {
        String clean = naturalLocalExperienceFocus(value);
        if (clean.isBlank()) {
            return "";
        }
        String lower = clean.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (containsAny(lower, List.of("удобство ожидания", "объяснение хода работ", "возможность задать вопросы"))) {
            return "Пока ждал, было понятно, что происходит с машиной, и вопросы можно было задать без неловкости.";
        }
        if (containsAny(lower, List.of("наличие деталей", "согласование стоимости", "под заказ"))) {
            return "По запчастям сразу разделили, что есть в наличии, что нужно заказывать и когда это получится по срокам.";
        }
        if (containsAny(lower, List.of("фото или видео", "показ старой детали", "согласование дополнительных работ"))) {
            return "Показали, что нашли, и дополнительные работы не начинали без согласования.";
        }
        if (containsAny(lower, List.of("срочность обращения", "скорость приема машины", "объяснение результата"))) {
            return "Важно было попасть без долгого ожидания, а после проверки спокойно объяснили причину.";
        }
        if (containsAny(lower, List.of("что беспокоило", "причину", "варианты ремонта"))) {
            return "Сначала объяснили возможную причину, потом предложили варианты без давления на срочный ремонт.";
        }
        if (containsAny(lower, List.of("записались", "проверили дополнительно", "согласовали работы"))) {
            return "Запись и дополнительные проверки проговорили заранее, поэтому объем работ был понятен до начала.";
        }
        if (containsAny(lower, List.of("дефектовк", "показывали детали", "согласовывали запчасти"))) {
            return "На дефектовке показали проблемные детали и заранее согласовали, что брать из наличия, а что ждать под заказ.";
        }
        if (containsAny(lower, List.of("какие детали меняли", "сколько заняли работы", "как изменилась управляемость"))) {
            return "Перед работами объяснили, какие узлы вызывают вопросы, а после ремонта машина стала собраннее на неровностях.";
        }
        if (containsAny(lower, List.of("чистота объекта", "следующему этапу ремонта", "продолжить ремонт"))) {
            return "После работ объект оставили в понятном состоянии, поэтому следующий этап ремонта не пришлось переносить из-за уборки.";
        }
        if (containsAny(lower, List.of("контроль практики", "исправление ошибок"))) {
            return "На практике показывали ошибки и объясняли, как спокойно поправить работу руками.";
        }
        if (containsAny(lower, List.of("найденные недостатки", "список замечаний", "акт замечаний"))) {
            return "Замечания зафиксировали списком, чтобы потом было проще обсуждать их с застройщиком.";
        }
        if (containsAny(lower, List.of("процесс замера", "смета", "приемка стен"))) {
            return "После замера смету объяснили до работ, а на приемке было понятно, на что смотреть по стенам.";
        }
        if (containsAny(lower, List.of("скорость ответов", "согласование графика"))) {
            return "Коммуникация по графику и изменениям была без длинных пауз, спорные моменты быстро уточняли до работ.";
        }
        if (containsAny(lower, List.of("объединение перегородок", "стяжки и подготовки стен"))) {
            return "Этапы не разъехались между разными людьми: заранее объяснили, кто отвечает за перегородки, стяжку и стены.";
        }
        if (containsAny(lower, List.of("подбор формы", "надписей", "композиции"))) {
            return "Форму, надписи и общий вид сверяли вместе, чтобы памятник не выглядел случайным набором деталей.";
        }
        if (containsAny(lower, List.of("выбор варианта", "оценка качества изображения"))) {
            return "Помогло, что выбор варианта и оценка качества изображения были объяснены без спешки.";
        }
        if (containsAny(lower, List.of("соблюдение сроков", "сметы на крупной площади"))) {
            return "По большой площади заранее проговорили график и смету, чтобы изменения не всплывали в последний момент.";
        }
        return "";
    }

    private String naturalLocalExperienceFocus(String value) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (clean.isBlank()) {
            return "";
        }
        clean = clean
                .replaceAll("(?iu)^офис,\\s*коттедж,\\s*склад\\s+или\\s+несколько\\s+квартир\\s*[—-]\\s*как\\s+соблюдались\\s+сроки\\s+и\\s+смета$", "соблюдение сроков и сметы на крупной площади")
                .replaceAll("(?iu)(^|\\s)как\\s+прошли\\s+замер(?=$|\\s|,)", "$1процесс замера")
                .replaceAll("(?iu)(^|\\s)как\\s+быстро\\s+отвечали,\\s*согласовывали\\s+график\\s+и\\s+изменения(?=$|\\s|,)", "$1скорость ответов и согласование графика")
                .replaceAll("(?iu)(^|\\s)как\\s+(?:здесь\\s+)?объединил[аи]?\\s+перегородки,\\s*стяжку\\s+и\\s+подготовку\\s+стен(?=$|\\s|,)", "$1объединение перегородок, стяжки и подготовки стен")
                .replaceAll("(?iu)(^|\\s)как\\s+контролировали\\s+практику\\s+и\\s+исправляли\\s+ошибки(?=$|\\s|,)", "$1контроль практики и исправление ошибок")
                .replaceAll("(?iu)(^|\\s)как\\s+подбирали\\s+форму,\\s*надписи\\s+и\\s+общую\\s+композицию(?=$|\\s|,)", "$1подбор формы, надписей и общей композиции")
                .replaceAll("(?iu)(^|\\s)какие\\s+недостатки\\s+нашли\\s+и\\s+как\\s+помог\\s+акт/список\\s+замечаний(?=$|\\s|,)", "$1найденные недостатки и список замечаний")
                .replaceAll("(?iu)(^|\\s)чистота\\s+объекта\\s+и\\s+когда\\s+получилось\\s+продолжить\\s+ремонт(?=$|\\s|,)", "$1чистота объекта и переход к следующему этапу ремонта")
                .replaceAll("(?iu)(^|\\s)почему\\s+выбрали\\s+этот\\s+вариант\\s+и\\s+как\\s+оценили\\s+качество\\s+изображения(?=$|\\s|,)", "$1выбор варианта и оценка качества изображения")
                .replaceAll("(?iu)(^|\\s)было\\s+ли\\s+удобно\\s+ждать(?=$|\\s|,)", "$1удобство ожидания")
                .replaceAll("(?iu)(^|\\s)объясняли\\s+ли\\s+ход\\s+работ(?=$|\\s|,)", "$1объяснение хода работ")
                .replaceAll("(?iu)(^|\\s)можно\\s+ли\\s+было\\s+задать\\s+вопросы?\\s+мастеру(?=$|\\s|,)", "$1возможность задать вопросы мастеру")
                .replaceAll("(?iu)(^|\\s)были\\s+ли\\s+детали\\s+в\\s+наличии\\s+или\\s+под\\s+заказ(?=$|\\s|,)", "$1наличие деталей или заказ")
                .replaceAll("(?iu)(^|\\s)как\\s+согласовали\\s+стоимость\\s+и\\s+сроки(?=$|\\s|,)", "$1согласование стоимости и сроков")
                .replaceAll("(?iu)(^|\\s)присылали\\s+ли\\s+фото/видео(?=$|\\s|,)", "$1фото или видео по ремонту")
                .replaceAll("(?iu)(^|\\s)показывали\\s+ли\\s+старую\\s+деталь(?=$|\\s|,)", "$1показ старой детали")
                .replaceAll("(?iu)(^|\\s)согласовывали\\s+ли\\s+дополнительные\\s+работы(?=$|\\s|,)", "$1согласование дополнительных работ")
                .replaceAll("(?iu)(^|\\s)была\\s+ли\\s+срочность(?=$|\\s|,)", "$1срочность обращения")
                .replaceAll("(?iu)(^|\\s)как\\s+быстро\\s+приняли\\s+машину(?=$|\\s|,)", "$1скорость приема машины")
                .replaceAll("(?iu)(^|\\s)как\\s+объяснили\\s+результат(?=$|\\s|,)", "$1объяснение результата")
                .replaceAll("(?iu)(^|\\s)какие\\s+детали\\s+меняли(?=$|\\s|,)", "$1какие детали меняли")
                .replaceAll("(?iu)(^|\\s)сколько\\s+заняло\\s+времени(?=$|\\s|,)", "$1сколько заняли работы")
                .replaceAll("(?iu)(^|\\s)как\\s+изменилась\\s+управляемость(?=$|\\s|,)", "$1как изменилась управляемость")
                .replaceAll("(?iu)(^|\\s)почему\\s+выбрали\\s+этот\\s+вариант(?=$|\\s|,)", "$1выбор варианта")
                .replaceAll("(?iu)(^|\\s)как\\s+оценили\\s+качество\\s+изображения(?=$|\\s|,)", "$1оценка качества изображения")
                .replaceAll("\\s+", " ")
                .trim();
        return clean;
    }

    private String enrichLocalDraftWithIdeaFocus(String draft, String focusSentence) {
        if (draft == null || draft.isBlank() || focusSentence == null || focusSentence.isBlank()) {
            return draft == null ? "" : draft;
        }
        String normalizedDraft = draft.toLowerCase(Locale.ROOT).replace('ё', 'е');
        String normalizedFocus = focusSentence.toLowerCase(Locale.ROOT).replace('ё', 'е');
        List<String> focusTokens = java.util.Arrays.stream(normalizedFocus.split("[^\\p{L}\\p{N}]+"))
                .filter(token -> token.length() >= 5)
                .filter(token -> !List.of("отдельно", "уточнял", "просил", "объяснить", "детали").contains(token))
                .limit(3)
                .toList();
        if (!focusTokens.isEmpty() && focusTokens.stream().allMatch(normalizedDraft::contains)) {
            return draft;
        }
        int firstSentenceEnd = draft.indexOf('.');
        if (firstSentenceEnd > 0 && firstSentenceEnd < draft.length() - 1) {
            return draft.substring(0, firstSentenceEnd + 1).trim()
                    + " " + focusSentence.trim()
                    + " " + draft.substring(firstSentenceEnd + 1).trim();
        }
        return draft.trim() + " " + focusSentence.trim();
    }

    private String ideaFocusTail(String idea) {
        String clean = cleanReviewIdeaText(idea)
                .replaceAll("[\\u200B-\\u200D\\uFEFF]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.isBlank()) {
            return "";
        }
        String focus = "";
        int colonIndex = firstColonIndex(clean);
        if (colonIndex > 0 && colonIndex < clean.length() - 1) {
            focus = clean.substring(colonIndex + 1).trim();
        } else {
            java.util.regex.Matcher matcher = Pattern
                    .compile("(?iu)\\b(?:как|почему|что|какие|какой|какая|когда|насколько|была\\s+ли|были\\s+ли)\\b.+")
                    .matcher(clean);
            if (matcher.find() && matcher.start() > 12) {
                focus = matcher.group().trim();
            }
        }
        return focus
                .replaceAll("^[\\s:;,.!?]+", "")
                .replaceAll("[.。]+$", "")
                .replaceAll("(?iu)(^|\\s)как\\s+компания\\s+", "$1как здесь ")
                .replaceAll("(?iu)(^|\\s)компания\\s+", "$1")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String fallbackDraftPhrase(String value) {
        String clean = cleanReviewIdeaText(value)
                .replaceAll("[.。]+$", "")
                .trim();
        if (clean.isBlank()) {
            return "";
        }
        clean = clean
                .replaceAll("(?iu)^\\s*компания\\s+по\\s+", "")
                .replaceAll("(?iu)^\\s*по\\s+задаче\\s+\"?[^\"]+\"?\\s*", "")
                .replaceAll("(?iu)^\\s*опыт\\s+заказа\\s+", "")
                .trim();
        int colonIndex = firstColonIndex(clean);
        if (colonIndex > 0) {
            clean = clean.substring(0, colonIndex).trim();
        }
        String lower = clean.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (lower.contains("коммуникац") && lower.contains("менеджер")) {
            return "коммуникации с менеджером/прорабом";
        }
        if (lower.contains("первичн") && lower.contains("диагност")) {
            return "первичная диагностика";
        }
        if (lower.contains("диагност") && lower.contains("подвеск")) {
            return "диагностика подвески";
        }
        if (lower.contains("ремонт") && lower.contains("ходов")) {
            return "ремонт ходовой";
        }
        if ((lower.contains("ремонт") || lower.startsWith("ремонте"))
                && (lower.contains("стартер") || lower.contains("генератор"))) {
            return "ремонт стартера";
        }
        if ((lower.contains("ремонт") || lower.startsWith("ремонте"))
                && (lower.contains("двигател") || lower.contains("гбц"))) {
            return "ремонт двигателя";
        }
        if (lower.contains("подбор") && lower.contains("запчаст")) {
            return "подбор запчастей";
        }
        if (lower.contains("запчаст")) {
            return "запчасти";
        }
        if ((lower.contains("замен") && lower.contains("масл")) || (lower.contains("комплексн") && lower.contains("то"))) {
            return "замена масла";
        }
        if (lower.contains("клиентск") && lower.contains("ожидан")) {
            return "ожидание и вопросы мастеру";
        }
        if (lower.contains("прозрачност") && lower.contains("ремонт")) {
            return "прозрачность ремонта";
        }
        if (lower.contains("подготовк") && lower.contains("дальн") && containsAny(lower, List.of("поездк", "дорог"))) {
            return "подготовка авто к поездке";
        }
        if (lower.contains("повторн") && lower.contains("обращен")) {
            return "повторное обращение";
        }
        if (lower.contains("день рождения") || lower.contains("дня рождения") || lower.contains("праздник")) {
            return "дню рождения под ключ";
        }
        if (lower.contains("лазертаг")) {
            return "лазертагу";
        }
        if (lower.contains("чай") && lower.contains("зон")) {
            return "чайной зоне";
        }
        if (lower.contains("квест") && (lower.contains("актер") || lower.contains("актёр") || lower.contains("персонаж"))) {
            return "квесту с актерами";
        }
        if (lower.contains("детск") || lower.contains("ребен") || lower.contains("ребён") || lower.contains("детей")) {
            return "детскому квесту";
        }
        if (lower.contains("хоррор") || lower.contains("страх") || lower.contains("страш") || lower.contains("хардкор")) {
            return "хоррор-квесту";
        }
        if (lower.contains("квест")) {
            return "квесту";
        }
        if (lower.contains("гарантийн") || lower.contains("доработк")) {
            return "доработке";
        }
        if (lower.contains("white box") || lower.contains("вайт бокс")) {
            return "White Box";
        }
        if (lower.contains("механизирован") && lower.contains("штукатур")) {
            return "механизированной штукатурке";
        }
        if (lower.contains("полусух") && lower.contains("стяжк")) {
            return "полусухой стяжке пола";
        }
        if (lower.contains("замер") && lower.contains("смет")) {
            return "замеру и смете";
        }
        if ((lower.contains("приемк") || lower.contains("приёмк"))
                && containsAny(lower, List.of("квартир", "застройщик", "новостройк", "white box", "вайт бокс"))) {
            return "приемке квартиры";
        }
        if (lower.contains("приемк") || lower.contains("приёмк")) {
            return "приемке результата";
        }
        if (lower.contains("работ") && lower.contains("договор")) {
            return "работе по договору";
        }
        if (lower.contains("крупн") && lower.contains("площад")) {
            return "работе на крупной площади";
        }
        if (lower.contains("частн") && lower.contains("охран")) {
            return "частной охранной организации";
        }
        if (lower.contains("охран") && lower.contains("сигнализац")) {
            return "охранной сигнализации";
        }
        if (lower.contains("изготовлен") && lower.contains("памятник")) {
            return "изготовлению памятников";
        }
        if (lower.contains("семейн") && lower.contains("двойн") && lower.contains("памятник")) {
            return "семейный памятник";
        }
        if (lower.contains("фотоовал") && lower.contains("фото на стекле")) {
            return "фотоовал";
        }
        if (lower.contains("фотоовал") && lower.contains("портрет")) {
            return "фотоовал";
        }
        if (lower.contains("базов") && lower.contains("курс")) {
            return "базовому курсу";
        }
        if (lower.contains("маникюр") || lower.contains("ногт")) {
            return "маникюру";
        }
        if (lower.startsWith("работа ")) {
            clean = "работе " + clean.substring("работа ".length()).trim();
        } else if (lower.startsWith("коммуникация ")) {
            clean = "коммуникации " + clean.substring("коммуникация ".length()).trim();
        } else if (lower.startsWith("механизированная ")) {
            clean = "механизированной " + clean.substring("механизированная ".length()).trim();
        } else if (lower.startsWith("полусухая ")) {
            clean = "полусухой " + clean.substring("полусухая ".length()).trim();
        }
        return lowercaseFirst(clean);
    }

    private String fallbackTopicLabel(String value) {
        String clean = value == null ? "" : value
                .replaceAll("[.。]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.isBlank()) {
            return "";
        }
        String lower = clean.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (lower.equals("полусухой стяжке пола")) {
            return "полусухая стяжка пола";
        }
        if (lower.equals("механизированной штукатурке")) {
            return "механизированная штукатурка";
        }
        if (lower.equals("замеру и смете")) {
            return "замер и смета";
        }
        if (lower.equals("приемке квартиры")) {
            return "приемка квартиры";
        }
        if (lower.equals("приемке результата")) {
            return "приемка результата";
        }
        if (lower.equals("доработке")) {
            return "доработка";
        }
        if (lower.equals("изготовлению памятников")) {
            return "изготовление памятников";
        }
        if (lower.equals("базовому курсу")) {
            return "базовый курс";
        }
        if (lower.equals("маникюру")) {
            return "маникюр";
        }
        if (lower.equals("дню рождения под ключ")) {
            return "день рождения под ключ";
        }
        if (lower.equals("лазертагу")) {
            return "лазертаг";
        }
        if (lower.equals("чайной зоне")) {
            return "чайная зона";
        }
        if (lower.equals("квесту с актерами") || lower.equals("квесту с актёрами")) {
            return "квест с актерами";
        }
        if (lower.equals("детскому квесту")) {
            return "детский квест";
        }
        if (lower.equals("хоррор-квесту")) {
            return "хоррор-квест";
        }
        if (lower.equals("квесту")) {
            return "квест";
        }
        if (lower.equals("работе на крупной площади")) {
            return "работа на крупной площади";
        }
        if (lower.equals("работе по договору")) {
            return "работа по договору";
        }
        if (lower.equals("коммуникации с менеджером/прорабом")) {
            return "коммуникация с менеджером/прорабом";
        }
        if (lower.equals("частной охранной организации")) {
            return "частная охранная организация";
        }
        if (lower.startsWith("работе ")) {
            return "работа " + clean.substring("работе ".length()).trim();
        }
        if (lower.startsWith("коммуникации ")) {
            return "коммуникация " + clean.substring("коммуникации ".length()).trim();
        }
        return lowercaseFirst(clean);
    }

    private String firstDifferentTopic(String base, String... candidates) {
        for (String candidate : candidates) {
            String clean = fallbackTopicLabel(candidate);
            if (!clean.isBlank() && !sameLocalTopic(base, clean) && !isWeakLocalDetailTopic(clean)) {
                return clean;
            }
        }
        return fallbackTopicLabel(base);
    }

    private boolean sameLocalTopic(String left, String right) {
        String cleanLeft = normalizeLocalTopic(left);
        String cleanRight = normalizeLocalTopic(right);
        return cleanLeft.isBlank() || cleanRight.isBlank() || cleanLeft.equals(cleanRight);
    }

    private String normalizeLocalTopic(String value) {
        return value == null ? "" : value
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isWeakLocalDetailTopic(String value) {
        String lower = normalizeLocalTopic(value);
        return lower.isBlank()
                || lower.length() < 5
                || lower.equals("услуга компании")
                || lower.equals("городе")
                || lower.equals("компания")
                || lower.contains("восточный мжк")
                || lower.contains("октябрьский район")
                || lower.contains("владивосток")
                || lower.contains("новосибирск")
                || lower.contains("ставропол");
    }

    private String localDomainDraft(String topic, String detailTopic, String rawIdea, int variant) {
        String text = ((topic == null ? "" : topic) + " "
                + (rawIdea == null ? "" : rawIdea))
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е');
        if (text.isBlank()) {
            return "";
        }
        if (containsAny(text, List.of(
                "квест", "лазертаг", "день рождения", "праздник", "чайная зон", "чайн",
                "актер", "актёр", "аниматор", "хоррор", "экзорц", "сталкер", "тюрьм",
                "филиал", "навигац", "адрес", "локац", "как найти",
                "кейтеринг", "своей еде", "своя еда", "еда", "еду", "едой",
                "питан", "угощ", "пицц", "торт", "посуд"
        ))) {
            return localEntertainmentDraft(text, variant);
        }
        String autoText = (text + " " + (detailTopic == null ? "" : detailTopic))
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е');
        if (containsAny(autoText, List.of(
                "диагност", "подвес", "ходов", "запчаст", "масл", "комплексное то",
                "стартер", "генератор", "аккумулятор", "клемм", "электрик", "двигател", "гбц",
                "дальней поезд", "дальней дорог", "машин", "авто"
        ))) {
            return localAutoServiceDraft(autoText, variant);
        }
        return "";
    }

    private String localAutoServiceDraft(String text, int variant) {
        int index = Math.floorMod(variant, 5);
        if (text.contains("стартер") || text.contains("генератор")
                || text.contains("аккумулятор") || text.contains("клемм")) {
            return switch (index) {
                case 0 -> "После обращения стало спокойнее: по запуску или зарядке машины появилась понятная картина. Проверку не растянули, результат объяснили без лишних обещаний.";
                case 1 -> "С проблемой запуска или зарядки не стал тянуть, хотелось понять, можно ли ехать дальше. Машину приняли без лишней паузы, по проверке узла и деталям договорились отдельно.";
                case 2 -> "По стартеру или генератору важно было не гадать самому. После проверки объяснили, в какую сторону двигаться и какие детали нужно подтвердить перед ремонтом.";
                case 3 -> "Обращение было срочное по ощущениям, потому что машина могла подвести в дороге. Здесь сначала проверили узел, а потом спокойно объяснили, что делать дальше.";
                default -> "С запуском или зарядкой лучше не шутить, поэтому поехал на проверку сразу. Понравилось, что результат рассказали простыми словами и не стали давить на лишние работы.";
            };
        }
        if (text.contains("запчаст")) {
            return switch (index) {
                case 0 -> "С запчастями не хотелось промахнуться по совместимости. Варианты по наличию и заказу разобрали спокойно, без сюрпризов по дальнейшим действиям.";
                case 1 -> "Нужно было подобрать детали без самостоятельных поисков по каталогам. По наличию сразу сориентировали, а то, что требовало заказа, вынесли отдельно.";
                case 2 -> "По расходникам и деталям помогли выбрать нормальный вариант для машины. Главное, что до заказа стало понятно, что реально подходит.";
                case 3 -> "Самому подбирать запчасти не рискнул. Здесь объяснили разницу между вариантами и не стали оформлять заказ без подтверждения.";
                default -> "По подбору запчастей всё прошло ровно: где можно взять из наличия, где нужен заказ, стало понятно до ремонта.";
            };
        }
        if (text.contains("подвес") || text.contains("ходов")
                || text.contains("стойк") || text.contains("стабилизатор")) {
            return switch (index) {
                case 0 -> "После дороги по кочкам заехал проверить подвеску. По спорным местам ответили конкретно, лишние работы не навязывали.";
                case 1 -> "На диагностике ходовой хотелось увидеть причину стука, а не просто получить список деталей. Показали проблемные места, дальше решение уже было понятнее.";
                case 2 -> "Подвеску смотрели без долгих общих разговоров. По найденным моментам объяснили, что критично, а что можно планировать отдельно.";
                case 3 -> "Был стук на неровностях, поэтому просил начать с диагностики. После осмотра стало понятнее, где износ и какие работы обсуждать.";
                default -> "За диагностику подвески спасибо: по стойкам и другим узлам не гадали на словах, а объяснили по факту проверки.";
            };
        }
        if (text.contains("ожидан") || text.contains("клиентск")
                || text.contains("вопрос") || text.contains("мастер")) {
            return switch (index) {
                case 0 -> "Пока ждал результат первичной диагностики, было важно не сидеть в неизвестности. На вопросы по машине ответили спокойно, без раздражения.";
                case 1 -> "Заехал с непонятным симптомом и остался ждать на месте. По ходу проверки можно было задать вопросы, поэтому ожидание не выглядело потерянным временем.";
                case 2 -> "Ждал недолго по ощущениям, но главное не в этом. По машине дали нормальные пояснения, без попытки отделаться общими словами.";
                case 3 -> "В зоне ожидания не пришлось гадать, что происходит с авто. Когда появились вопросы по диагностике, на них ответили по делу.";
                default -> "Приехал на первичную диагностику и остался рядом. Пока ждал, получилось уточнить по машине то, что давно откладывал.";
            };
        }
        if (text.contains("масл") || text.contains("комплексное то") || text.contains("фильтр")) {
            return switch (index) {
                case 0 -> "На замену масла заехал без ожидания какого-то особенного сервиса, просто нужен был понятный список работ. По расходникам всё проговорили заранее.";
                case 1 -> "Комплексное ТО прошло спокойно: лишнего не добавляли молча, по машине оставили понятные замечания.";
                case 2 -> "По маслу и фильтрам было важно не купить неподходящее. Подсказали по расходникам и отдельно отметили, что стоит проверить позже.";
                case 3 -> "Плановое обслуживание сделал без суеты. После визита осталось понимание по расходникам и ближайшим вопросам по авто.";
                default -> "Заехал на обычное ТО, без больших ожиданий. Работы по маслу и фильтрам прошли ровно, по дополнительным моментам сказали отдельно.";
            };
        }
        if (text.contains("поезд") || text.contains("дорог")) {
            return switch (index) {
                case 0 -> "ТО перед дальней дорогой прошло спокойно: хотелось убрать лишнюю тревогу по машине. Проверили основные моменты и дали понятные рекомендации без страшилок.";
                case 1 -> "ТО перед дальней дорогой сделал заранее, потому что не хотелось ловить проблему уже в пути. После проверки стало спокойнее, слабые места обозначили отдельно.";
                case 2 -> "ТО перед дальней дорогой просил сделать без лишних замен на всякий случай. Получил список того, что важно проверить сейчас, и что можно держать в голове.";
                case 3 -> "ТО перед дальней дорогой было нужно для спокойствия перед выездом. После диагностики стало понятнее, на что обратить внимание.";
                default -> "ТО перед дальней дорогой получилось практичным: без громких обещаний, просто проверили машину и объяснили риски.";
            };
        }
        if (text.contains("двигател") || text.contains("гбц")) {
            return switch (index) {
                case 0 -> "По двигателю не хотелось принимать решение вслепую. После дефектовки стало понятнее, какие детали требуют внимания.";
                case 1 -> "К ремонту двигателя подошёл осторожно, потому что работа недешёвая по ощущениям. Объём объяснили после проверки, без резких заявлений.";
                case 2 -> "На дефектовке важно было увидеть не просто итоговую сумму, а причину работ. По деталям дали пояснения, дальше уже решал спокойно.";
                case 3 -> "С мотором не стал тянуть после первых признаков проблемы. Проверили, объяснили варианты и не обещали чудес.";
                default -> "По ремонту двигателя осталось нормальное впечатление: сначала разобрались с причиной, потом уже говорили про запчасти.";
            };
        }
        return switch (index) {
            case 0 -> "На первичную диагностику приехал без желания сразу оставлять машину в ремонт. После проверки стало понятнее, что делать дальше.";
            case 1 -> "Заехал с непонятным поведением машины, хотел сначала услышать причину. Объяснили спокойно, варианты ремонта не продавливали.";
            case 2 -> "По машине были вопросы, но самому гадать не хотелось. Диагностика дала понятный план без лишней суеты.";
            case 3 -> "Приехал скорее проверить, чем ремонтироваться сразу. После осмотра стало ясно, где проблема и какие расходы держать в голове.";
            default -> "Обращение получилось обычное, рабочее: машину посмотрели, причину объяснили, дальше уже можно было принимать решение.";
        };
    }

    private String localEntertainmentDraft(String text, int variant) {
        int index = Math.floorMod(variant, 6);
        if (containsAny(text, List.of("филиал", "навигац", "адрес", "локац", "как найти"))) {
            return switch (index) {
                case 0 -> "Сначала больше думал не про саму игру, а как нормально найти место и не опоздать к старту. Ориентиры уточнили заранее, поэтому на месте уже не метались.";
                case 1 -> "По филиалу и дороге были вопросы, особенно перед первым визитом. После уточнений стало понятно, куда приезжать и где начинается программа.";
                case 2 -> "Навигация оказалась важнее, чем казалось: не хотелось искать вход уже перед игрой. Заранее уточнили, как попасть на место, и это сняло лишнюю суету.";
                case 3 -> "Перед поездкой больше всего переживал, что компания разойдётся по разным входам. По адресу и ориентиру всё объяснили коротко, дальше уже спокойно пришли к началу.";
                case 4 -> "Филиал выбирал не только по сценарию, но и по тому, насколько понятно туда добраться. После пояснений маршрут стал нормальным, без лишних звонков по дороге.";
                default -> "Для первого визита важно было понять дорогу и вход, а не разбираться с этим в последний момент. С навигацией помогли заранее, поэтому старт прошёл спокойнее.";
            };
        }
        if (containsAny(text, List.of(
                "кейтеринг", "своей еде", "своя еда", "еда", "еду", "едой",
                "питан", "угощ", "пицц", "торт", "посуд"
        ))) {
            return switch (index) {
                case 0 -> "По еде заранее хотел понять, можно ли прийти со своими угощениями или лучше оформить отдельно. Условия проговорили до праздника, поэтому на месте это уже не отвлекало.";
                case 1 -> "Больше всего сомневался не по игре, а по угощениям после неё. Сразу уточнили, что можно принести с собой и что лучше решить заранее.";
                case 2 -> "С тортом и одноразовой посудой не хотелось бегать в последний момент. По формату еды всё объяснили заранее, и праздник из-за этого выглядел собраннее.";
                case 3 -> "Выбирали между своей едой и отдельным заказом, без лишней спешки. После пояснений стало понятно, какой вариант нам удобнее.";
                case 4 -> "Про пиццу и угощения спросили заранее, потому что после игры обычно уже не до организационных мелочей. Ответы получили понятные, без сюрпризов на месте.";
                default -> "Еду для праздника не стали оставлять на последний момент. Заранее разобрались, что можно принести и как это совместить с игрой.";
            };
        }
        if (text.contains("лазертаг")) {
            return switch (index) {
                case 0 -> "Лазертаг выбрали, чтобы дети не сидели за столом весь праздник. Перед игрой объяснили правила и ограничения, дальше уже спокойно отпустили их в активную часть.";
                case 1 -> "По лазертагу было важно понять, как проходит игра и кто следит за порядком. После короткого инструктажа стало спокойнее, дети быстро включились.";
                case 2 -> "Активная игра зашла лучше, чем просто посидеть в комнате ожидания. Правила дали коротко, без лишних разговоров, и дальше всё пошло живее.";
                case 3 -> "Сначала сомневался насчёт лазертага, думал, будет хаос. На месте объяснили порядок игры, и стало понятно, где дети находятся и что им делать.";
                case 4 -> "Лазертаг взяли как вторую активность после основной программы. Понравилось, что не пришлось самим придумывать, чем занять компанию дальше.";
                default -> "Для детской компании лазертаг оказался нормальным вариантом: много движения, понятные правила и без долгого ожидания между этапами.";
            };
        }
        if (text.contains("день рождения") || text.contains("праздник") || text.contains("пакет")) {
            return switch (index) {
                case 0 -> "На день рождения хотелось собрать всё в одном месте, без отдельного поиска кафе и развлечений. Формат с квестом и чайной зоной оказался удобным.";
                case 1 -> "Праздник планировали без лишней беготни, поэтому заранее уточнили, что входит в формат. После этого стало проще понять, чем занять гостей до и после игры.";
                case 2 -> "Главное было не растерять детей между разными местами. Квест, активная часть и зона для угощений сложились в один понятный сценарий.";
                case 3 -> "Сначала переживал, что день рождения получится кусками: тут игра, там еда, потом ещё что-то искать. По формату заранее объяснили порядок, и стало спокойнее.";
                case 4 -> "Для праздника понравилось, что не нужно было отдельно собирать программу. Сначала игра, потом спокойная часть для угощений, всё без суеты.";
                default -> "Выбирали формат дня рождения, где детям есть чем заняться, а взрослым не нужно всё вести самим. По этапам быстро стало понятно, как будет проходить праздник.";
            };
        }
        if (text.contains("чай")) {
            return switch (index) {
                case 0 -> "Чайная зона выручила после игры: можно было спокойно посидеть с угощениями и не искать место рядом.";
                case 1 -> "Больше всего волновало, где собраться после квеста. С зоной для чаепития этот вопрос закрыли без отдельной брони.";
                case 2 -> "После активной части детям нужно было немного выдохнуть. Удобно, что для этого была отдельная зона, а не просто коридор.";
                case 3 -> "Не хотелось тащить компанию в другое место сразу после игры. Чайная зона помогла спокойно завершить праздник.";
                case 4 -> "По зоне ожидания и чаепитию заранее уточнили детали. В итоге после квеста никто не метался, где поставить угощения.";
                default -> "Чайную зону брали как спокойную часть после квеста. Для праздника это оказалось практичнее, чем искать кафе отдельно.";
            };
        }
        if (containsAny(text, List.of("актер", "актёр", "хоррор", "экзорц", "сталкер", "тюрьм"))) {
            return switch (index) {
                case 0 -> "Квест с актерами выбирали осторожно, чтобы было атмосферно, но без лишнего перегиба. Перед началом объяснили ограничения, поэтому команда понимала, на что идет.";
                case 1 -> "По хоррор-квесту заранее уточнили уровень контакта и правила. После инструктажа было спокойнее заходить в игру.";
                case 2 -> "Атмосфера получилась именно за счет актеров и декораций, а не просто темной комнаты. При этом правила проговорили до старта.";
                case 3 -> "Сначала сомневался, не будет ли слишком жестко. После объяснения сценария и ограничений стало понятно, какой формат выбираем.";
                case 4 -> "Для команды хотелось квест не совсем простой. С актерами вышло живее, но без ощущения, что тебя бросили без правил.";
                default -> "Сценарий заранее объяснили настолько, чтобы не раскрыть игру, но убрать лишние вопросы по безопасности и ограничениям.";
            };
        }
        if (text.contains("детск") || text.contains("ребен") || text.contains("детей")) {
            return switch (index) {
                case 0 -> "Для детского квеста было важно, чтобы правила объяснили простыми словами. Дети быстро поняли, что делать, и не пришлось постоянно вмешиваться.";
                case 1 -> "Выбирали вариант, где детям будет интересно, но без лишней нервотрепки для взрослых. Инструктаж помог спокойно запустить игру.";
                case 2 -> "Детский формат оказался понятным: сначала правила, потом игра, потом можно спокойно собрать компанию после прохождения.";
                case 3 -> "Сначала переживали, что дети растеряются в сценарии. На месте всё объяснили коротко, и дальше они уже втянулись сами.";
                case 4 -> "Для компании детей квест подошёл тем, что не было длинных пауз. Объяснили порядок, и игра пошла без постоянных подсказок от взрослых.";
                default -> "Детям было проще включиться, когда перед стартом спокойно объяснили правила и что делать в локации.";
            };
        }
        return switch (index) {
            case 0 -> "Квест выбирали по сценарию, но больше всего смотрели на понятные правила перед стартом. После инструктажа команда уже спокойно зашла в игру.";
            case 1 -> "Искал вариант без лишней суматохи перед началом. Правила объяснили коротко, по ограничениям тоже не осталось вопросов.";
            case 2 -> "Понравилось, что не стали раскрывать сюжет заранее, но по порядку игры всё объяснили. Из-за этого старт прошёл без путаницы.";
            case 3 -> "Сначала были вопросы по формату квеста. После объяснения правил и ограничений стало понятно, подходит ли он нашей компании.";
            case 4 -> "Выбирали между несколькими сценариями и остановились на том, где понятнее формат. Перед игрой спокойно прошли инструктаж.";
            default -> "По квесту осталось нормальное впечатление: без долгого вступления, с понятными правилами и аккуратным запуском игры.";
        };
    }

    private String localTopicSentence(String topic, String detailTopic) {
        String cleanTopic = fallbackTopicLabel(topic);
        String lower = normalizeLocalTopic(cleanTopic);
        if (lower.contains("ремонт стартера") || lower.contains("генератор")) {
            return "Машину приняли без лишней паузы, проверили запуск и спокойно объяснили причину.";
        }
        if (lower.contains("первичная диагностика")) {
            return "Начали с диагностики: показали, откуда может идти проблема, и предложили варианты без давления.";
        }
        if (lower.contains("диагностика подвески")) {
            return "Подвеску проверили поэтапно, спорные места объяснили до решения по ремонту.";
        }
        if (lower.contains("ремонт ходовой")) {
            return "По ходовой сначала показали, что вызывает вопросы, а потом согласовали работы и запчасти.";
        }
        if (lower.contains("подбор запчастей")) {
            return "По запчастям сразу разделили, что есть в наличии, а что нужно ждать под заказ.";
        }
        if (lower.contains("замена масла") || lower.contains("комплексное то")) {
            return "Запись и замену масла согласовали заранее, заодно подсказали, что стоит проверить дополнительно.";
        }
        if (lower.contains("ремонт двигателя") || lower.contains("гбц")) {
            return "На дефектовке показали проблемные детали и отдельно согласовали запчасти.";
        }
        if (lower.contains("подготовка авто") || lower.contains("дальней поездке") || lower.contains("дальней дороге")) {
            return "Перед поездкой проверили основные узлы и дали понятные рекомендации.";
        }
        if (lower.contains("ожидание") && lower.contains("вопросы мастеру")) {
            return "Пока машину смотрели, можно было спокойно дождаться результата и задать вопросы мастеру.";
        }
        if (lower.contains("прозрачность ремонта")) {
            return "Не начинали дополнительные работы молча: сначала показали, что нашли, и согласовали следующий шаг.";
        }
        if (lower.contains("повторное обращение")) {
            return "Вернулся потому, что в прошлый раз нормально объясняли работу и не приходилось вытягивать детали.";
        }
        if (lower.contains("white box")) {
            return "По White Box заранее разложили этапы и объяснили, кто за что отвечает.";
        }
        if (lower.contains("механизированная штукатурка")) {
            return "Сначала прошли замер, затем объяснили смету и на приемке показали, на что смотреть по стенам.";
        }
        if (lower.contains("полусухая стяжка")) {
            return "По стяжке заранее обсудили подготовку объекта, порядок работ и переход к следующему этапу.";
        }
        if (lower.contains("замер") && lower.contains("смета")) {
            return "После замера смету объяснили без размытых формулировок.";
        }
        if (lower.contains("приемка квартиры")) {
            return "На приемке помогли зафиксировать замечания списком, чтобы потом не спорить по памяти.";
        }
        if (lower.contains("приемка результата")) {
            return "На приемке спокойно сверили результат с тем, о чем договаривались заранее.";
        }
        if (lower.contains("доработка")) {
            return "Замечание не стали заминать: передали его в работу и спокойно согласовали исправление.";
        }
        if (lower.contains("работа по договору")) {
            return "Работа по договору не осталась формальностью: документы и оплату проговорили заранее.";
        }
        if (lower.contains("коммуникация с менеджером") || lower.contains("коммуникации с менеджером")) {
            return "Коммуникация по графику и изменениям была без длинных пауз, спорные моменты быстро уточняли до работ.";
        }
        if (lower.contains("менеджер") || lower.contains("сопровожд")) {
            return "По выбору и бюджету помогли пройтись спокойно, без давления на самый дорогой вариант.";
        }
        if (lower.contains("крупной площади")) {
            return "По большой площади заранее обсудили график, смету и порядок проверки результата.";
        }
        if (lower.contains("дистанцион") || lower.contains("другого города")) {
            return "Заказ был из другого города, поэтому важно было заранее понять макет, смету и порядок установки.";
        }
        if (lower.contains("под ключ")) {
            return "Заказ под ключ был удобен тем, что основные этапы и приемку проговорили заранее.";
        }
        if (lower.contains("ремонт стар") || lower.contains("восстанов")) {
            return "По старому памятнику сначала оценили состояние, потом стало понятно, что реально восстановить.";
        }
        if (lower.contains("образц") || lower.contains("материал") || lower.contains("выбор гранита")
                || lower.contains("камн")) {
            return "Образцы камня сравнили без спешки, чтобы заранее понимать цвет, фактуру и общий вид.";
        }
        if (lower.contains("изготовление памятников") || lower.contains("гранитн") || lower.contains("памятник")) {
            return "По заказу заранее прошлись по макету, договору и установке, поэтому не осталось ощущения выбора наугад.";
        }
        if (lower.contains("фотоовал") || lower.contains("фото на стекле") || lower.contains("портрет")) {
            return "По изображению сначала сравнили варианты, потом стало понятнее, как оценивать качество результата.";
        }
        if (lower.contains("благоустройство")) {
            return "По участку заранее обсудили плиты, отмостку и аккуратность после монтажа.";
        }
        if (lower.contains("день рождения") || lower.contains("пакет")) {
            return "Формат праздника заранее разложили по этапам, поэтому было понятно, что делать до и после игры.";
        }
        if (lower.contains("квест") || lower.contains("лазертаг")) {
            return "По сценарию и правилам заранее объяснили ограничения, безопасность и порядок игры.";
        }
        if (lower.contains("маникюр") || lower.contains("ногт")) {
            return "По практике и результату объяснили, на что смотреть и что стоит поправить в технике.";
        }
        if (!sameLocalTopic(cleanTopic, detailTopic) && !isWeakLocalDetailTopic(detailTopic)) {
            return "Больше всего волновало: " + shortText(cleanTopic, 120)
                    + ". Отдельно проговорили момент, который больше всего влиял на результат.";
        }
        return "Больше всего волновало: " + shortText(cleanTopic, 130)
                + ". После разговора появился нормальный порядок действий.";
    }

    private int firstColonIndex(String value) {
        int colon = value.indexOf(':');
        int fullWidthColon = value.indexOf('：');
        if (colon < 0) {
            return fullWidthColon;
        }
        if (fullWidthColon < 0) {
            return colon;
        }
        return Math.min(colon, fullWidthColon);
    }

    private String lowercaseFirst(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String clean = value.trim();
        if (clean.matches("^[A-ZА-ЯЁ]{2,}.*") || clean.startsWith("White Box")) {
            return clean;
        }
        return clean.substring(0, 1).toLowerCase(Locale.ROOT) + clean.substring(1);
    }

    private String stripTrailingSentenceDot(String value) {
        return value == null ? "" : value.replaceAll("[.。]+$", "").trim();
    }

    private String cleanReviewInputText(String value) {
        return cleanMarkdownText(value)
                .replaceAll("(?iu)^\\s*(?:[-*]|\\d+[.)])\\s+", "")
                .replaceAll("(?iu)^\\s*живой\\s+отзыв\\s+клиента\\s+по\\s+заметке\\s+карточки\\s*:\\s*", "")
                .replaceAll("(?iu)^\\s*живой\\s+отзыв\\s+клиента\\s+(?:о|по\\s+направлению)\\s+", "")
                .replaceAll("(?iu)^\\s*живой\\s+отзыв\\s+клиента\\s+по\\s+фактам\\s+заказа\\.?\\s*", "")
                .replaceAll("(?iu)^\\s*(главный\\s+якорь\\s+карточки|дополнительный\\s+акцент\\s+из\\s+отч[её]та|идея\\s+из\\s+отч[её]та\\s+для\\s+этой\\s+карточки|конкретика\\s+для\\s+упоминания|услуга/товар\\s+для\\s+упоминания)\\s*:\\s*", "")
                .replaceAll("(?iu)^\\s*(товар/услуга(?:\\s+отзыва)?|категория(?:\\s+отзыва)?|подкатегория(?:\\s+отзыва)?|цена(?:\\s+отзыва)?|сумма\\s+заказа|количество\\s+в\\s+заказе)\\s*:\\s*[^;.!?]{0,160}[;.]?\\s*", "")
                .replaceAll("(?iu)^\\s*отзыв\\s+для\\s+карточки\\s*#?\\d+\\s*;?\\s*", "")
                .replaceAll("(?iu)^\\s*нужно\\s+написать\\s+новый\\s+вариант[^;.!?]*[;.]?\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String cleanReviewIdeaText(String value) {
        String clean = cleanReviewInputText(value);
        if (clean.isBlank()) {
            return "";
        }
        List<String> usefulParts = new ArrayList<>();
        for (String part : clean.split("\\s*;\\s*")) {
            String candidate = cleanReviewInputText(part);
            String lower = candidate.toLowerCase(Locale.ROOT).replace('ё', 'е');
            if (lower.startsWith("добавить")
                    || lower.startsWith("указать")
                    || lower.startsWith("упомянуть")
                    || lower.startsWith("если ")
                    || lower.startsWith("если...")) {
                break;
            }
            usefulParts.add(candidate);
        }
        clean = usefulParts.isEmpty() ? clean : String.join("; ", usefulParts);
        clean = clean
                .replaceAll("(?iu)^\\s*отзыв\\s+клиента\\s+(?:о|об|про)\\s+", "")
                .replaceAll("(?iu)^\\s*отзыв\\s+(?:о|об|про)\\s+", "")
                .replaceAll("(?iu)\\s*\\bдобавить\\b.*$", "")
                .replaceAll("(?iu)\\s*\\bуказать\\b.*$", "")
                .replaceAll("(?iu)\\s*\\bупомянуть\\b.*$", "")
                .replaceAll("\\.\\.\\.$", "")
                .trim();
        return stripBoundaryQuotes(clean);
    }

    private boolean requiresClientConfirmationFact(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return clean.contains("после подтвержд")
                || clean.contains("подтвердить ")
                || clean.contains("подтвердить:")
                || clean.contains("если подтверд")
                || clean.contains("подтверждения состава")
                || clean.contains("подтвержденного сотрудника")
                || clean.contains("подтверждённого сотрудника");
    }

    private boolean looksLikeWrittenReviewExample(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (clean.startsWith("выбирали ")
                || clean.startsWith("были ")
                || clean.startsWith("отмечали ")
                || clean.startsWith("понравилось,")
                || clean.startsWith("понравилось что")
                || clean.startsWith("понравилось, что")) {
            return true;
        }
        return clean.length() > 140
                && clean.contains(". ")
                && (clean.contains(" iquest")
                || clean.contains(" skr ")
                || clean.contains("компании")
                || clean.contains("у компании"));
    }

    private String stripBoundaryQuotes(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) {
            return "";
        }
        if (clean.matches("^[«\"'“”].*")) {
            return clean
                    .replaceAll("(?iu)^\\s*[«\"'“”]+\\s*", "")
                    .replaceAll("(?iu)\\s*[»\"'“”]+\\.?\\s*$", "")
                    .trim();
        }
        return clean;
    }

    private boolean isInternalRecommendation(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return clean.contains("лучше позиционировать")
                || clean.contains("позиционирован")
                || clean.contains("главный вывод")
                || clean.contains("смешанный бизнес")
                || clean.contains("операционный профиль")
                || clean.contains("клиентский путь")
                || clean.contains("репутационный вывод")
                || clean.contains("есть признаки формата")
                || clean.contains("сильная основа для текстов")
                || clean.contains("в отзывах несколько раз")
                || clean.contains("по отзывам")
                || clean.contains("для клиента это")
                || clean.contains("сервисный бокс/сто")
                || clean.contains("публичный дозбор")
                || clean.contains("что спросить у владельца")
                || clean.contains("уточнить")
                || clean.contains("после подтвержд")
                || clean.contains("подтвердить ")
                || clean.contains("подтвердить:")
                || clean.contains("подтверждения состава")
                || clean.contains("подтвержденного сотрудника")
                || clean.contains("подтверждённого сотрудника")
                || clean.contains("требует проверки")
                || clean.contains("требуют проверки")
                || clean.contains("возможно,")
                || clean.contains("наличие фото подтверждено")
                || clean.contains("содержание текстом не разобрано")
                || clean.startsWith("собрать фото")
                || clean.contains("интерьер/экстерьер")
                || clean.contains("отзыв для карточки")
                || clean.contains("нужно написать новый вариант")
                || clean.contains("не похожий на соседние карточки")
                || clean.matches(".*\\b(?:товар/услуга|категория|цена)\\s*:.*");
    }

    private boolean isTechnicalContextFact(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return clean.matches("^\\s*(заказ|отзыв|статус заказа|количество в заказе|сумма заказа)\\s*:.*")
                || clean.matches("^\\s*(товар/услуга отзыва|цена отзыва)\\s*:.*")
                || clean.contains("отзыв 2гис")
                || clean.contains("отзыв 2gis")
                || clean.contains("вписать имя фамилию");
    }

    private void addNonBlank(List<String> values, String value) {
        if (value != null && !value.isBlank() && !value.matches(".*:\\s*$")) {
            values.add(value);
        }
    }

    private String pick(List<String> values, int index) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.get(Math.floorMod(index, values.size()));
    }

    private String pickConcreteProduct(
            List<String> products,
            List<String> services,
            String theme,
            String service,
            int index
    ) {
        if (products == null || products.isEmpty()) {
            return "";
        }
        List<String> concreteProducts = products.stream()
                .filter(value -> value != null && !value.isBlank())
                .filter(value -> !looksLikeServiceDuplicate(value, services))
                .toList();
        String concrete = firstNonBlank(
                pickMatching(concreteProducts, theme),
                pickMatching(concreteProducts, service),
                pick(concreteProducts.stream()
                        .filter(value -> productFitsSlot(value, theme, service))
                        .toList(), index)
        );
        if (!concrete.isBlank()) {
            return concrete;
        }
        return firstNonBlank(
                pickMatching(products, theme),
                pickMatching(products, service)
        );
    }

    private boolean productFitsSlot(String product, String theme, String service) {
        String item = normalizeProductSignal(product);
        String rawTheme = theme == null ? "" : theme;
        String context = normalizeProductSignal(rawTheme + " " + service);
        if (item.isBlank()) {
            return false;
        }
        boolean contextLaserTag = context.contains("лазертаг");
        boolean productQuestScenario = item.matches(".*(квест|оно|психоз|экзорцизм|тюрьм|сталкер|космическ|артефакт).*");
        if (productQuestScenario && themeMentionsDifferentQuestScenario(rawTheme, item)) {
            return false;
        }
        if (contextLaserTag && productQuestScenario && !item.contains("лазертаг")) {
            return false;
        }
        if (context.contains("квест") && productQuestScenario) {
            return true;
        }
        if (context.matches(".*(деньрождения|праздник|подключ|чаин|пакет).*")
                && item.matches(".*(пакет|праздник|инфраструктур|чаин|квест|лазертаг).*")) {
            return true;
        }
        if (contextLaserTag && item.contains("лазертаг")) {
            return true;
        }
        return factScore(product, factKeywords(theme + " " + service)) >= 2;
    }

    private boolean themeMentionsDifferentQuestScenario(String theme, String productSignal) {
        String raw = theme == null ? "" : theme.trim();
        String clean = raw.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (clean.isBlank()) {
            return false;
        }
        if (raw.matches("(?iu).*\\bв\\s+стиле\\s+[А-ЯЁA-Z][\\p{L}A-Za-z-]+(?:\\s+[А-ЯЁA-Z][\\p{L}A-Za-z-]+){0,2}.*")
                && !normalizeProductSignal(clean).contains(productSignal)) {
            return true;
        }
        return questScenarioSignals(clean).stream()
                .anyMatch(signal -> !productSignal.contains(signal));
    }

    private List<String> questScenarioSignals(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        List<String> signals = new ArrayList<>();
        if (hasStandaloneToken(clean, "оно")) {
            signals.add("оно");
        }
        if (clean.contains("психоз")) {
            signals.add("психоз");
        }
        if (clean.contains("экзорц")) {
            signals.add("экзорц");
        }
        if (clean.contains("тюрьм")) {
            signals.add("тюрьм");
        }
        if (clean.contains("сталкер")) {
            signals.add("сталкер");
        }
        if (clean.contains("космическ")) {
            signals.add("космическ");
        }
        if (clean.contains("артефакт")) {
            signals.add("артефакт");
        }
        if (clean.contains("гарри поттер") || clean.contains("гарри") || clean.contains("поттер")) {
            signals.add("гаррипоттер");
        }
        return signals;
    }

    private String normalizeProductSignal(String value) {
        return (value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е'))
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private boolean looksLikeServiceDuplicate(String value, List<String> services) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (clean.matches(".*(пакет|программ|сценар|\\d|руб|₽|\"|«|»).*")) {
            return false;
        }
        String normalized = looseServiceNormalize(clean);
        if (normalized.isBlank()) {
            return false;
        }
        return services != null && services.stream()
                .map(this::looseServiceNormalize)
                .filter(service -> !service.isBlank())
                .anyMatch(service -> normalized.equals(service)
                        || (normalized.length() >= 10 && service.contains(normalized))
                        || (service.length() >= 10 && normalized.contains(service)));
    }

    private String looseServiceNormalize(String value) {
        return (value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е'))
                .replace("квесты", "квест")
                .replace("актеры", "актер")
                .replace("актерами", "актер")
                .replace("актёры", "актер")
                .replace("актёрами", "актер")
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private String pickMatching(List<String> values, String context) {
        if (values == null || values.isEmpty() || context == null || context.isBlank()) {
            return "";
        }
        Set<String> keywords = factKeywords(context);
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .filter(value -> factScore(value, keywords) > 0
                        || normalizedContains(context, value)
                        || normalizedContains(value, context))
                .findFirst()
                .orElse("");
    }

    private boolean normalizedContains(String haystack, String needle) {
        String cleanHaystack = haystack == null ? "" : haystack.toLowerCase(Locale.ROOT).replace('ё', 'е');
        String cleanNeedle = needle == null ? "" : needle.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return cleanNeedle.length() >= 5 && cleanHaystack.contains(cleanNeedle);
    }

    private AiSingleReviewDraftFactory.BatchDraftTarget batchTarget(
            ReputationContentPack pack,
            DeepCompanyResearchReport report,
            ReputationBatchReviewDraftTarget target
    ) {
        String idea = selectedIdea(pack, target.idea());
        List<String> facts = sourceFacts(pack, report, idea, target.orderContext());
        return new AiSingleReviewDraftFactory.BatchDraftTarget(
                new ReputationBatchReviewDraftTarget(target.reviewId(), idea, target.previousDraft(), target.orderContext()),
                facts
        );
    }

    private ReputationBatchReviewDraftResult localBatchResult(
            Long companyId,
            ReportEnvelope reportEnvelope,
            PackEnvelope packEnvelope,
            ReputationBatchReviewDraftRequest request,
            List<AiSingleReviewDraftFactory.BatchDraftTarget> targets
    ) {
        List<ReputationBatchReviewDraftItem> drafts = targets.stream()
                .map(target -> localBatchItem(companyId, reportEnvelope, packEnvelope, request, target))
                .toList();
        return new ReputationBatchReviewDraftResult(
                companyId,
                firstNonBlank(packEnvelope.pack().researchSnapshot().companyName(), reportEnvelope.report().companyName(), "компания"),
                reportEnvelope.jobId(),
                packEnvelope.jobId(),
                "local",
                "fallback",
                drafts,
                List.of("OpenAI недоступен или не вернул пакет: тексты собраны локально по глубокому отчёту."),
                LocalDateTime.now()
        );
    }

    private ReputationBatchReviewDraftResult openAiOnlyEmptyBatchResult(
            Long companyId,
            ReportEnvelope reportEnvelope,
            PackEnvelope packEnvelope
    ) {
        return new ReputationBatchReviewDraftResult(
                companyId,
                firstNonBlank(packEnvelope.pack().researchSnapshot().companyName(), reportEnvelope.report().companyName(), "компания"),
                reportEnvelope.jobId(),
                packEnvelope.jobId(),
                "openai",
                "",
                List.of(),
                List.of("OpenAI не вернул подходящие тексты: для недостающих карточек может быть использован короткий локальный rescue fallback."),
                LocalDateTime.now()
        );
    }

    private List<Long> missingTargetIds(
            List<AiSingleReviewDraftFactory.BatchDraftTarget> targets,
            ReputationBatchReviewDraftResult generated
    ) {
        Set<Long> present = generated.drafts().stream()
                .filter(draft -> draft.draft() != null && !draft.draft().isBlank())
                .map(ReputationBatchReviewDraftItem::reviewId)
                .collect(java.util.stream.Collectors.toSet());
        return targets.stream()
                .map(AiSingleReviewDraftFactory.BatchDraftTarget::reviewId)
                .filter(id -> !present.contains(id))
                .toList();
    }

    private List<ReviewGenerationSlot> missingSlots(List<ReviewGenerationSlot> slots, List<Long> missingIds) {
        if (slots == null || missingIds == null || missingIds.isEmpty()) {
            return List.of();
        }
        Set<Long> missing = new LinkedHashSet<>(missingIds);
        return slots.stream()
                .filter(slot -> missing.contains(slot.reviewId()))
                .toList();
    }

    private List<AiSingleReviewDraftFactory.BatchDraftTarget> missingTargets(
            List<AiSingleReviewDraftFactory.BatchDraftTarget> targets,
            List<Long> missingIds
    ) {
        if (targets == null || missingIds == null || missingIds.isEmpty()) {
            return List.of();
        }
        Set<Long> missing = new LinkedHashSet<>(missingIds);
        return targets.stream()
                .filter(target -> missing.contains(target.reviewId()))
                .toList();
    }

    private ReputationBatchReviewDraftResult shortFallbackBatchResult(
            Long companyId,
            ReportEnvelope reportEnvelope,
            PackEnvelope packEnvelope,
            ReputationBatchReviewDraftRequest request,
            ReviewGenerationBrief brief,
            List<AiSingleReviewDraftFactory.BatchDraftTarget> targets,
            ReputationBatchReviewDraftResult generated
    ) {
        List<ReputationBatchReviewDraftItem> drafts = targets.stream()
                .map(target -> shortFallbackBatchItem(reportEnvelope, packEnvelope, request, brief, target))
                .toList();
        List<String> safetyNotes = new ArrayList<>(generated.safetyNotes());
        safetyNotes.add("Для части карточек OpenAI не вернул подходящий текст: недостающие карточки закрыты коротким локальным rescue fallback.");
        return new ReputationBatchReviewDraftResult(
                companyId,
                firstNonBlank(generated.companyName(), packEnvelope.pack().researchSnapshot().companyName(), reportEnvelope.report().companyName(), "компания"),
                reportEnvelope.jobId(),
                packEnvelope.jobId(),
                firstNonBlank(generated.provider(), "openai"),
                firstNonBlank(generated.model(), "short-fallback"),
                drafts,
                safetyNotes.stream().filter(value -> value != null && !value.isBlank()).distinct().limit(12).toList(),
                LocalDateTime.now()
        );
    }

    private ReputationBatchReviewDraftItem shortFallbackBatchItem(
            ReportEnvelope reportEnvelope,
            PackEnvelope packEnvelope,
            ReputationBatchReviewDraftRequest request,
            ReviewGenerationBrief brief,
            AiSingleReviewDraftFactory.BatchDraftTarget target
    ) {
        String rawIdea = firstNonBlank(cleanReviewIdeaText(target.idea()), target.requiredAnchor(), "услуга компании");
        List<String> sourceFacts = localReviewFacts(target.facts(), rawIdea);
        String company = firstNonBlank(packEnvelope.pack().researchSnapshot().companyName(), reportEnvelope.report().companyName(), "");
        String draft = shortFallbackDraft(brief, rawIdea, sourceFacts, target.reviewId());
        draft = cleanGeneratedLocalDraft(draft, company);
        draft = applyEmojiMode(draft, request.emojiMode());
        return new ReputationBatchReviewDraftItem(
                target.reviewId(),
                rawIdea,
                draft,
                sourceFacts,
                List.of(
                        "Короткий локальный rescue fallback: OpenAI не вернул подходящий текст для этой карточки.",
                        "Перед публикацией клиент должен проверить, что реальный опыт совпадает с однофразовым текстом."
                )
        );
    }

    private String shortFallbackDraft(
            ReviewGenerationBrief brief,
            String rawIdea,
            List<String> sourceFacts,
            Long reviewId
    ) {
        String topic = fallbackTopicLabel(fallbackDraftPhrase(firstNonBlank(rawIdea, sourceFacts.isEmpty() ? "" : sourceFacts.getFirst())));
        if (topic.isBlank()) {
            topic = "услуга";
        }
        String context = (topic + " " + rawIdea + " " + String.join(" ", sourceFacts))
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е');
        String businessType = brief == null || brief.businessType() == null ? "" : brief.businessType().toLowerCase(Locale.ROOT);
        int variant = Math.floorMod(reviewId == null ? topic.hashCode() : reviewId.hashCode(), 4);

        if ("auto_service".equals(businessType) || containsAny(context, List.of(
                "машин", "авто", "подвес", "ходов", "масл", "фильтр", "запчаст", "диагност", "генератор", "стартер", "двигател"))) {
            return shortAutoFallback(topic, variant);
        }
        if ("entertainment".equals(businessType) || containsAny(context, List.of(
                "квест", "лазертаг", "день рождения", "праздник", "чайная зона", "аниматор", "актер", "актёр"))) {
            return switch (variant) {
                case 0 -> "Брали " + shortTopic(topic) + ", формат подошел, спасибо.";
                case 1 -> "По " + shortTopic(topic) + " всё прошло нормально, без лишней суеты.";
                case 2 -> shortCap(topic) + " оказался понятным вариантом, спасибо.";
                default -> "Для праздника " + shortTopic(topic) + " подошел нормально, дети были заняты.";
            };
        }
        if (containsAny(context, List.of("маникюр", "ногт", "педикюр", "курс", "обучен", "наращив", "моделирован"))) {
            return switch (variant) {
                case 0 -> "Была на " + shortTopic(topic) + ", получилось аккуратно, спасибо.";
                case 1 -> "По " + shortTopic(topic) + " вопросов не осталось, результат нормальный.";
                case 2 -> "Записывалась на " + shortTopic(topic) + ", всё прошло спокойно.";
                default -> shortCap(topic) + " устроил, без лишней суеты.";
            };
        }
        if (containsAny(context, List.of("памятник", "гранит", "надпис", "фотоовал", "портрет", "стела", "композици"))) {
            return switch (variant) {
                case 0 -> "По " + shortTopic(topic) + " помогли выбрать спокойный вариант, спасибо.";
                case 1 -> shortCap(topic) + " сделали аккуратно, по результату вопросов не осталось.";
                case 2 -> "С " + shortTopic(topic) + " разобрались без спешки, итог получился сдержанный.";
                default -> "Заказывали " + shortTopic(topic) + ", приняли спокойно, без лишних замечаний.";
            };
        }
        if (containsAny(context, List.of("штукатур", "стяжк", "white box", "договор", "смет", "приемк", "приёмк", "ремонт"))) {
            return switch (variant) {
                case 0 -> "По " + shortTopic(topic) + " всё прошло ровно, без лишних разговоров.";
                case 1 -> shortCap(topic) + " приняли нормально, по результату вопросов не осталось.";
                case 2 -> "Заказывал " + shortTopic(topic) + ", сделали спокойно и понятно.";
                default -> "С " + shortTopic(topic) + " вопрос закрыли, спасибо.";
            };
        }
        return switch (variant) {
            case 0 -> "Брал " + shortTopic(topic) + ", всё хорошо, спасибо.";
            case 1 -> "Заказывал " + shortTopic(topic) + ", всё нормально.";
            case 2 -> "По " + shortTopic(topic) + " вопросов не осталось, спасибо.";
            default -> shortCap(topic) + " устроил, без лишней суеты.";
        };
    }

    private String shortAutoFallback(String topic, int variant) {
        String lower = normalizeLocalTopic(topic);
        if (lower.contains("подбор") && lower.contains("запчаст")) {
            return "С подбором запчастей помогли нормально, без лишней путаницы, спасибо.";
        }
        if (lower.contains("диагност") && lower.contains("подвес")) {
            return "Заезжал на диагностику подвески, по машине стало понятнее, спасибо.";
        }
        if (lower.contains("первич") && lower.contains("диагност")) {
            return "Заезжал на первичную диагностику, всё объяснили коротко и по делу.";
        }
        if (lower.contains("масл") || lower.contains("то")) {
            return "Был на замене масла и ТО, всё прошло спокойно, спасибо.";
        }
        return switch (variant) {
            case 0 -> "Заезжал по машине, вопрос закрыли нормально, спасибо.";
            case 1 -> "По авто стало понятнее, лишнего не навязали.";
            case 2 -> "С ремонтом машины помогли спокойно, без лишних разговоров.";
            default -> "По машине всё нормально, спасибо.";
        };
    }

    private int countShortFallbackDrafts(ReputationBatchReviewDraftResult result) {
        if (result == null || result.drafts() == null || result.drafts().isEmpty()) {
            return 0;
        }
        return (int) result.drafts().stream()
                .filter(item -> item.safetyNotes().stream()
                        .anyMatch(note -> note != null && note.contains("Короткий локальный rescue fallback")))
                .count();
    }

    private String shortTopic(String value) {
        return shortText(singleConcreteTopicOption(value), 80)
                .replaceAll("[.。]+$", "")
                .trim();
    }

    private String singleConcreteTopicOption(String value) {
        String clean = value == null ? "" : value
                .replaceAll("[.。]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.isBlank()) {
            return "";
        }
        String lower = clean.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (lower.contains("фотоовал") && lower.contains("фото на стекле")) {
            return "фотоовал";
        }
        if (lower.contains("фотоовал") && lower.contains("портрет")) {
            return "фотоовал";
        }
        if (lower.contains("стартер") && lower.contains("генератор")) {
            return "ремонт стартера";
        }
        if (lower.contains("двигател") && lower.contains("гбц")) {
            return "ремонт двигателя";
        }
        if (lower.contains("замен") && lower.contains("масл") && lower.contains("то")) {
            return "замена масла";
        }
        if (lower.contains("семейн") && lower.contains("двойн") && lower.contains("памятник")) {
            return "семейный памятник";
        }
        String[] parts = clean.split("(?iu)\\s+или\\s+", 2);
        if (parts.length == 2 && parts[0].trim().length() >= 4) {
            return parts[0].trim();
        }
        return clean;
    }

    private String shortCap(String value) {
        String clean = shortTopic(value);
        if (clean.isBlank()) {
            return "Услуга";
        }
        return clean.substring(0, 1).toUpperCase(Locale.ROOT) + clean.substring(1);
    }

    private ReputationBatchReviewDraftResult openAiSingleRetryBatchResult(
            Long companyId,
            ReportEnvelope reportEnvelope,
            PackEnvelope packEnvelope,
            ReputationBatchReviewDraftRequest request,
            List<AiSingleReviewDraftFactory.BatchDraftTarget> targets
    ) {
        List<ReputationBatchReviewDraftItem> drafts = new ArrayList<>();
        List<String> safetyNotes = new ArrayList<>();
        String provider = "openai";
        String model = "";
        for (AiSingleReviewDraftFactory.BatchDraftTarget target : targets) {
            Optional<ReputationSingleReviewDraftResult> single = aiSingleReviewDraftFactory.create(
                    companyId,
                    reportEnvelope.jobId(),
                    packEnvelope.jobId(),
                    reportEnvelope.report(),
                    packEnvelope.pack(),
                    singleRequest(request, target),
                    target.idea(),
                    target.facts()
            );
            if (single.isEmpty()) {
                continue;
            }
            ReputationSingleReviewDraftResult result = single.orElseThrow();
            provider = firstNonBlank(provider, result.provider());
            model = firstNonBlank(model, result.model());
            safetyNotes.addAll(result.safetyNotes());
            drafts.add(new ReputationBatchReviewDraftItem(
                    target.reviewId(),
                    result.idea(),
                    result.draft(),
                    result.sourceFacts(),
                    result.safetyNotes()
            ));
        }
        return new ReputationBatchReviewDraftResult(
                companyId,
                firstNonBlank(packEnvelope.pack().researchSnapshot().companyName(), reportEnvelope.report().companyName(), "компания"),
                reportEnvelope.jobId(),
                packEnvelope.jobId(),
                provider,
                model,
                drafts,
                safetyNotes,
                LocalDateTime.now()
        );
    }

    private ReputationBatchReviewDraftResult mergeOpenAiBatchResults(
            ReputationBatchReviewDraftResult first,
            ReputationBatchReviewDraftResult retry,
            List<AiSingleReviewDraftFactory.BatchDraftTarget> targets
    ) {
        List<ReputationBatchReviewDraftItem> allDrafts = new ArrayList<>();
        allDrafts.addAll(first.drafts());
        allDrafts.addAll(retry.drafts());
        List<ReputationBatchReviewDraftItem> orderedDrafts = targets.stream()
                .map(AiSingleReviewDraftFactory.BatchDraftTarget::reviewId)
                .map(reviewId -> firstDraftForReviewId(allDrafts, reviewId))
                .filter(Optional::isPresent)
                .map(Optional::orElseThrow)
                .toList();
        return new ReputationBatchReviewDraftResult(
                first.companyId(),
                firstNonBlank(first.companyName(), retry.companyName()),
                first.deepReportJobId(),
                first.contentPackJobId(),
                firstNonBlank(first.provider(), retry.provider()),
                firstNonBlank(first.model(), retry.model()),
                orderedDrafts,
                mergePriorityFacts(first.safetyNotes(), retry.safetyNotes(), 10),
                retry.generatedAt()
        );
    }

    private Optional<ReputationBatchReviewDraftItem> firstDraftForReviewId(
            List<ReputationBatchReviewDraftItem> drafts,
            Long reviewId
    ) {
        if (reviewId == null) {
            return Optional.empty();
        }
        return drafts.stream()
                .filter(draft -> reviewId.equals(draft.reviewId()))
                .findFirst();
    }

    private ReputationBatchReviewDraftResult fillMissingBatchDrafts(
            Long companyId,
            ReportEnvelope reportEnvelope,
            PackEnvelope packEnvelope,
            ReputationBatchReviewDraftRequest request,
            List<AiSingleReviewDraftFactory.BatchDraftTarget> targets,
            ReputationBatchReviewDraftResult generated
    ) {
        Set<Long> present = generated.drafts().stream()
                .filter(draft -> draft.draft() != null && !draft.draft().isBlank())
                .map(ReputationBatchReviewDraftItem::reviewId)
                .collect(java.util.stream.Collectors.toSet());
        List<ReputationBatchReviewDraftItem> drafts = generated.drafts().stream()
                .filter(draft -> draft.draft() != null && !draft.draft().isBlank())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        targets.stream()
                .filter(target -> !present.contains(target.reviewId()))
                .map(target -> localBatchItem(companyId, reportEnvelope, packEnvelope, request, target))
                .forEach(drafts::add);
        List<Long> missingIds = targets.stream()
                .map(AiSingleReviewDraftFactory.BatchDraftTarget::reviewId)
                .filter(id -> !present.contains(id))
                .toList();
        if (!missingIds.isEmpty()) {
            log.info(
                    "REVIEW_BATCH_FALLBACK_MISSING source=fallback_missing companyId={} missingDrafts={} missingIds={} baseProvider={} baseModel={} fallbackProvider=local fallbackModel=fallback",
                    companyId,
                    missingIds.size(),
                    missingIds,
                    generated.provider(),
                    generated.model()
            );
        }
        return new ReputationBatchReviewDraftResult(
                companyId,
                generated.companyName(),
                generated.deepReportJobId(),
                generated.contentPackJobId(),
                generated.provider(),
                generated.model(),
                drafts,
                generated.safetyNotes(),
                generated.generatedAt()
        );
    }

    private ReputationBatchReviewDraftItem localBatchItem(
            Long companyId,
            ReportEnvelope reportEnvelope,
            PackEnvelope packEnvelope,
            ReputationBatchReviewDraftRequest request,
            AiSingleReviewDraftFactory.BatchDraftTarget target
    ) {
        ReputationSingleReviewDraftResult single = localResult(
                companyId,
                reportEnvelope,
                packEnvelope,
                singleRequest(request, target),
                target.idea(),
                target.facts()
        );
        return new ReputationBatchReviewDraftItem(
                target.reviewId(),
                single.idea(),
                single.draft(),
                single.sourceFacts(),
                single.safetyNotes()
        );
    }

    private ReputationSingleReviewDraftRequest singleRequest(
            ReputationBatchReviewDraftRequest request,
            AiSingleReviewDraftFactory.BatchDraftTarget target
    ) {
        return new ReputationSingleReviewDraftRequest(
                request.deepReportJobId(),
                request.contentPackJobId(),
                target.idea(),
                request.style(),
                request.authorType(),
                request.emojiMode(),
                request.manualNotes(),
                request.length(),
                request.contentPackProfile(),
                target.reviewId(),
                target.previousDraft(),
                target.orderContext()
        );
    }

    private ReputationSingleReviewDraftResult sanitizeCompanyName(
            ReputationSingleReviewDraftResult result,
            ReportEnvelope reportEnvelope,
            PackEnvelope packEnvelope
    ) {
        if (result == null) {
            return null;
        }
        String cleanDraft = removeCompanyNames(result.draft(), companyNames(result.companyName(), reportEnvelope, packEnvelope));
        if (cleanDraft.equals(result.draft())) {
            return result;
        }
        return new ReputationSingleReviewDraftResult(
                result.companyId(),
                result.companyName(),
                result.deepReportJobId(),
                result.contentPackJobId(),
                result.provider(),
                result.model(),
                result.idea(),
                result.style(),
                cleanDraft,
                result.sourceFacts(),
                result.safetyNotes(),
                reviewSafetyService.check(cleanDraft, result.sourceFacts()),
                result.generatedAt()
        );
    }

    private ReputationBatchReviewDraftResult sanitizeCompanyName(
            ReputationBatchReviewDraftResult result,
            ReportEnvelope reportEnvelope,
            PackEnvelope packEnvelope
    ) {
        if (result == null || result.drafts().isEmpty()) {
            return result;
        }
        List<String> names = companyNames(result.companyName(), reportEnvelope, packEnvelope);
        List<ReputationBatchReviewDraftItem> drafts = result.drafts().stream()
                .map(item -> new ReputationBatchReviewDraftItem(
                        item.reviewId(),
                        item.idea(),
                        removeCompanyNames(item.draft(), names),
                        item.sourceFacts(),
                        item.safetyNotes()
                ))
                .toList();
        return new ReputationBatchReviewDraftResult(
                result.companyId(),
                result.companyName(),
                result.deepReportJobId(),
                result.contentPackJobId(),
                result.provider(),
                result.model(),
                drafts,
                result.safetyNotes(),
                result.generatedAt()
        );
    }

    private List<String> companyNames(String resultCompanyName, ReportEnvelope reportEnvelope, PackEnvelope packEnvelope) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        addCompanyName(names, resultCompanyName);
        if (packEnvelope != null && packEnvelope.pack() != null && packEnvelope.pack().researchSnapshot() != null) {
            addCompanyName(names, packEnvelope.pack().researchSnapshot().companyName());
        }
        if (reportEnvelope != null && reportEnvelope.report() != null) {
            addCompanyName(names, reportEnvelope.report().companyName());
        }
        return names.stream().toList();
    }

    private void addCompanyName(LinkedHashSet<String> names, String value) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (clean.length() < 3) {
            return;
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        if (List.of("компания", "компанию", "компании", "организация", "организацию").contains(lower)) {
            return;
        }
        names.add(clean);
    }

    private String applyEmojiMode(String draft, String emojiMode) {
        String cleanMode = emojiMode == null ? "" : emojiMode.toLowerCase();
        if (cleanMode.contains("один")) {
            return draft + " 🙂";
        }
        if (cleanMode.contains("немного")) {
            return draft + " 👍";
        }
        return draft;
    }

    private String cleanGeneratedLocalDraft(String draft, String companyName) {
        String clean = cleanReviewInputText(draft);
        clean = clean
                .replaceAll("(?iu)\\b(?:По теме|Отзыв для карточки|товар/услуга|категория|цена|нужно написать|Главный вывод|Главный якорь|акцент из отч[её]та)\\b\\s*:?\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        return removeCompanyName(clean, companyName);
    }

    private String removeCompanyNames(String value, List<String> companyNames) {
        String clean = value == null ? "" : value;
        if (companyNames == null || companyNames.isEmpty()) {
            return clean.trim();
        }
        for (String companyName : companyNames) {
            clean = removeCompanyName(clean, companyName);
        }
        return clean.trim();
    }

    private String removeCompanyName(String value, String companyName) {
        if (value == null || value.isBlank() || companyName == null || companyName.isBlank()) {
            return value == null ? "" : value;
        }
        String clean = value;
        for (String alias : companyAliases(companyName)) {
            String aliasPattern = "(?:[«\"“”']\\s*)?"
                    + Pattern.quote(alias)
                    + "(?:\\s*[»\"“”'])?(?![\\p{L}\\p{N}])";
            String withPreposition = "(?iu)(?<![\\p{L}\\p{N}])(?:в|во|у|об|о|про|от|для|с|со|по)\\s+"
                    + aliasPattern;
            clean = clean.replaceAll(withPreposition, " ");
            String pattern = "(?iu)(?<![\\p{L}\\p{N}])" + aliasPattern;
            clean = clean.replaceAll(pattern, " ");
        }
        return clean
                .replaceAll("\\s+([,.;:!?])", "$1")
                .replaceAll("(^|[.!?]\\s*)[,;:]\\s*", "$1")
                .replaceAll("(?iu)(^|[.!?]\\s*)[Вв]\\s+(?=понравилось|приш[её]л|обратил|выбрал|искал|смотрел|наш[её]л)", "$1")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private List<String> companyAliases(String companyName) {
        String clean = companyName == null ? "" : companyName.replaceAll("\\s+", " ").trim();
        if (clean.isBlank()) {
            return List.of();
        }
        List<String> aliases = new ArrayList<>();
        aliases.add(clean);
        String unquoted = clean
                .replaceAll("(?iu)\\b(ооо|ип|ао|пао|зао)\\b\\s*", "")
                .replaceAll("[«»\"“”']", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (!unquoted.isBlank() && !unquoted.equalsIgnoreCase(clean)) {
            aliases.add(unquoted);
        }
        return aliases.stream().distinct().toList();
    }

    private String selectedIdea(ReputationContentPack pack, String manualIdea) {
        if (manualIdea != null && !manualIdea.isBlank()) {
            return firstNonBlank(safeSlotTheme(manualIdea), "живой отзыв по фактам заказа");
        }
        List<String> candidates = new ArrayList<>();
        candidates.addAll(pack.honestReviewTopics());
        candidates.addAll(pack.reviewDraftTemplates());
        candidates.addAll(pack.utp());
        candidates.addAll(pack.socialPostTopics());
        candidates.addAll(pack.adTexts());
        candidates.addAll(pack.socialPosts().stream().map(value -> shortText(value, 260)).toList());
        candidates = candidates.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(this::safeSlotTheme)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (candidates.isEmpty()) {
            return "Показать одну конкретную услугу или УТП компании и оставить место для личного опыта клиента.";
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private List<String> reviewIdeasFromReport(DeepCompanyResearchReport report) {
        List<String> explicitIdeas = report.reviewIdeas().stream()
                .filter(value -> !value.isBlank())
                .filter(value -> !isWeakTheme(value))
                .distinct()
                .limit(30)
                .toList();
        if (!explicitIdeas.isEmpty()) {
            return explicitIdeas;
        }
        return report.sections().stream()
                .flatMap(section -> reviewIdeaItems(section.title(), section.body()).stream())
                .filter(value -> !value.isBlank())
                .filter(value -> !isWeakTheme(value))
                .distinct()
                .limit(30)
                .toList();
    }

    private List<String> reviewIdeaItems(String title, String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        List<String> ideas = new ArrayList<>();
        boolean collecting = isReviewIdeaHeading(title);
        for (String rawLine : body.lines().toList()) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            if (isReviewIdeaHeading(line)) {
                collecting = true;
                continue;
            }
            if (!collecting) {
                continue;
            }
            if (!line.matches("^(?:\\d+[.)]|[-*])\\s+.*")) {
                continue;
            }
            String idea = cleanMarkdownText(line.replaceFirst("^(?:\\d+[.)]|[-*])\\s+", ""));
            if (!idea.isBlank() && !ideas.contains(idea)) {
                ideas.add(idea);
            }
        }
        return ideas.stream().limit(30).toList();
    }

    private boolean isReviewIdeaHeading(String value) {
        String clean = value == null ? "" : value.toLowerCase();
        return clean.contains("иде")
                && clean.contains("честн")
                && clean.contains("отзыв")
                && !clean.matches(".*(пост|faq|карточ|контент|коммент|дозбор|спросить|уточн).*");
    }

    private String firstSectionBody(DeepCompanyResearchReport report) {
        return report.sections().stream()
                .filter(section -> section.body() != null && !section.body().isBlank())
                .map(section -> shortText(section.body(), 360))
                .findFirst()
                .orElse("");
    }

    private List<String> sourceFacts(
            ReputationContentPack pack,
            DeepCompanyResearchReport report,
            String idea,
            String orderContext
    ) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        List<String> commercial = commercialFacts(report, idea);
        commercial.forEach(value -> addFactFragments(values, value, 280));
        addFactFragments(values, idea, 220);
        addFactFragments(values, orderContext, 220);
        pack.utp().forEach(value -> addFactFragments(values, value, 220));
        pack.adTexts().forEach(value -> addFactFragments(values, value, 220));
        pack.socialPostTopics().forEach(value -> addFactFragments(values, value, 180));
        pack.honestReviewTopics().forEach(value -> addFactFragments(values, value, 180));
        pack.reviewDraftTemplates().forEach(value -> addFactFragments(values, value, 180));
        pack.companyProfile().advantages().forEach(value -> addFactFragments(values, value, 180));
        report.sections().stream()
                .filter(section -> importantSection(section.title(), section.body()))
                .filter(section -> !isInternalRecommendation(section.title() + " " + section.body()))
                .forEach(section -> {
                    addFactFragments(values, section.body(), 220);
                });
        report.warnings().stream().limit(3).map(value -> shortText(value, 180)).forEach(values::add);
        Set<String> keywords = factKeywords(idea + "\n" + orderContext);
        List<String> sorted = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .filter(value -> !isInternalRecommendation(value))
                .filter(value -> !isTechnicalContextFact(value))
                .distinct()
                .toList();
        List<String> relevant = new ArrayList<>(sorted);
        relevant.sort((left, right) -> Integer.compare(factScore(right, keywords), factScore(left, keywords)));
        List<String> strong = relevant.stream()
                .filter(value -> factScore(value, keywords) > 0)
                .limit(12)
                .toList();
        if (!strong.isEmpty()) {
            return mergePriorityFacts(commercial, strong, 12);
        }
        return mergePriorityFacts(commercial, sorted, 12);
    }

    private List<String> mergePriorityFacts(List<String> priority, List<String> fallback, int limit) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (priority != null) {
            priority.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .filter(value -> !requiresClientConfirmationFact(value))
                    .forEach(result::add);
        }
        if (fallback != null) {
            fallback.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .filter(value -> !requiresClientConfirmationFact(value))
                    .forEach(result::add);
        }
        return result.stream().limit(limit).toList();
    }

    private List<String> commercialFacts(DeepCompanyResearchReport report, String idea) {
        if (report == null || report.sections() == null) {
            return List.of();
        }
        List<String> facts = new ArrayList<>();
        report.sections().stream()
                .filter(section -> commercialSection(section.title(), section.body()))
                .forEach(section -> addCommercialFacts(facts, section.body()));
        report.sections().stream()
                .filter(section -> !commercialSection(section.title(), section.body()))
                .forEach(section -> addCommercialFacts(facts, section.body()));
        addCommercialFacts(facts, report.reportMarkdown());
        List<String> result = facts.stream()
                .map(value -> shortText(value, 280))
                .filter(value -> !value.isBlank())
                .filter(value -> !isInternalRecommendation(value))
                .filter(value -> !isTechnicalContextFact(value))
                .distinct()
                .toList();
        List<String> sorted = new ArrayList<>(result);
        Set<String> keywords = factKeywords(idea);
        sorted.sort((left, right) -> Integer.compare(factScore(right, keywords), factScore(left, keywords)));
        return sorted.stream().limit(10).toList();
    }

    private List<String> priceFactsFromReport(DeepCompanyResearchReport report) {
        if (report == null || report.sections() == null) {
            return List.of();
        }
        LinkedHashSet<String> facts = new LinkedHashSet<>();
        report.sections().stream()
                .filter(section -> commercialSection(section.title(), section.body()))
                .forEach(section -> addPriceFacts(facts, section.body()));
        report.sections().stream()
                .filter(section -> !commercialSection(section.title(), section.body()))
                .forEach(section -> addPriceFacts(facts, section.body()));
        addPriceFacts(facts, report.reportMarkdown());
        return facts.stream()
                .map(value -> shortText(value, 260))
                .filter(value -> !value.isBlank())
                .filter(value -> !isInternalRecommendation(value))
                .filter(value -> !isTechnicalContextFact(value))
                .distinct()
                .limit(40)
                .toList();
    }

    private void addPriceFacts(LinkedHashSet<String> facts, String body) {
        if (facts == null || body == null || body.isBlank()) {
            return;
        }
        for (String rawLine : body.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank() || line.matches("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)+\\|?$")) {
                continue;
            }
            String candidate = line.contains("|") ? tableFact(line) : cleanFact(line);
            if (!candidate.isBlank()
                    && hasPrice(candidate)
                    && !headerLike(candidate)
                    && !isInternalRecommendation(candidate)
                    && !isTechnicalContextFact(candidate)) {
                facts.add(candidate);
            }
        }
    }

    private boolean commercialSection(String title, String body) {
        String text = ((title == null ? "" : title) + " " + (body == null ? "" : body)).toLowerCase(Locale.ROOT);
        return text.matches(".*(услуг|товар|цен|стоим|прайс|тариф|пакет|программ|формат|меню|абонемент|комплект).*");
    }

    private void addCommercialFacts(List<String> facts, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        for (String rawLine : body.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank() || line.matches("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)+\\|?$")) {
                continue;
            }
            String candidate = line.contains("|") ? tableFact(line) : cleanFact(line);
            if (candidate.isBlank() || headerLike(candidate)) {
                continue;
            }
            if (commercialDetail(candidate) && !isInternalRecommendation(candidate) && !isTechnicalContextFact(candidate)) {
                facts.add(candidate);
            }
        }
    }

    private String tableFact(String line) {
        List<String> cells = java.util.Arrays.stream(line.split("\\|"))
                .map(this::cleanFact)
                .filter(value -> !value.isBlank())
                .filter(value -> !value.matches("(?i).*(источник|уверенность|source|confidence).*"))
                .toList();
        if (cells.isEmpty()) {
            return "";
        }
        if (cells.size() == 1) {
            return cells.getFirst();
        }
        String title = cells.getFirst();
        String details = String.join("; ", cells.subList(1, cells.size()));
        return title + " — " + details;
    }

    private String cleanFact(String value) {
        return value == null
                ? ""
                : value
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replaceAll("^(?:[-*]|\\d+[.)])\\s+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean headerLike(String value) {
        String clean = value.toLowerCase(Locale.ROOT);
        return clean.matches(".*(позиция|название|описание).*цена.*")
                || clean.matches(".*(товар|услуга).*описание.*")
                || clean.matches(".*(условия|сроки).*источник.*");
    }

    private boolean commercialDetail(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return clean.matches(".*\\b\\d[\\d\\s]*(?:[,.]\\d+)?\\s*(?:₽|руб\\.?|р\\.?|тыс\\.?).*")
                || clean.matches(".*(от\\s+\\d|до\\s+\\d|цена|стоим|прайс|тариф|абонемент|билет).*")
                || clean.matches(".*(пакет|программ|формат|квест|праздник|день рождения|меню|комплект|доставка|самовывоз|бронь|предоплат).*");
    }

    private void addFactFragments(LinkedHashSet<String> values, String value, int limit) {
        String raw = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (raw.isBlank() || isInternalRecommendation(raw) || isTechnicalContextFact(raw)) {
            return;
        }
        if (raw.contains("\n") || raw.contains("|")) {
            for (String rawLine : raw.split("\n")) {
                String line = rawLine == null ? "" : rawLine.trim();
                if (line.isBlank() || line.matches("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)+\\|?$")) {
                    continue;
                }
                String candidate = line.contains("|") ? tableFact(line) : cleanFact(line);
                if (!candidate.isBlank()
                        && !headerLike(candidate)
                        && !isInternalRecommendation(candidate)
                        && !isTechnicalContextFact(candidate)
                        && candidate.length() >= 12) {
                    values.add(shortText(candidate, limit));
                }
            }
        }
        String clean = raw.replaceAll("\\s+", " ").trim();
        if (clean.isBlank() || isInternalRecommendation(clean) || isTechnicalContextFact(clean)) {
            return;
        }
        if (clean.length() <= limit) {
            values.add(clean);
            return;
        }
        for (String part : clean.split("(?<=[.!?])\\s+|[;•]")) {
            String fragment = shortText(part, limit);
            if (fragment.length() >= 12) {
                values.add(fragment);
            }
        }
    }

    private Set<String> factKeywords(String value) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String token : clean.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() < 4 || isFactStopWord(token)) {
                continue;
            }
            keywords.add(token);
            if (keywords.size() >= 24) {
                break;
            }
        }
        return keywords;
    }

    private int factScore(String value, Set<String> keywords) {
        if (value == null || value.isBlank() || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        String clean = value.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : keywords) {
            if (clean.contains(keyword)) {
                score += keyword.length() >= 6 ? 2 : 1;
            }
        }
        return score;
    }

    private boolean isFactStopWord(String value) {
        return Set.of(
                "отзыв", "отзыва", "клиент", "клиента", "компания", "компании",
                "можно", "нужно", "после", "перед", "через", "чтобы", "когда",
                "если", "есть", "было", "были", "будет", "такой", "также",
                "какие", "какой", "какая", "который", "которые", "почему"
        ).contains(value);
    }

    private boolean importantSection(String title, String body) {
        String text = ((title == null ? "" : title) + " " + (body == null ? "" : body)).toLowerCase();
        return text.matches(".*(сводк|профил|услуг|утп|сценари|довер|отзыв|филиал|логист|адрес|парков|вход|этаж|режим|цена|срок|заказ|огранич|качества).*");
    }

    private DeepCompanyResearchReport readDeepReport(String json) {
        try {
            return objectMapper.readValue(json, DeepCompanyResearchReport.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось прочитать сохраненный глубокий отчет компании", exception);
        }
    }

    private ReputationContentPack readPack(String json) {
        try {
            return objectMapper.readValue(json, ReputationContentPack.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось прочитать сохраненный AI-пакет компании", exception);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String shortText(String value, int limit) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (clean.length() <= limit) {
            return clean;
        }
        return clean.substring(0, limit - 3).trim() + "...";
    }

    private String cleanMarkdownText(String value) {
        return shortText(value, 360)
                .replaceAll("[\\u200B\\u200C\\u200D\\uFEFF]", "")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .trim();
    }

    private record PackEnvelope(Long jobId, ReputationContentPack pack) {
    }

    private record ReportEnvelope(Long jobId, DeepCompanyResearchReport report) {
    }
}

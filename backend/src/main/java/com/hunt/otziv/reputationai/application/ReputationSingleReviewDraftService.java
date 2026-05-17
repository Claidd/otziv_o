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
import java.util.regex.Pattern;

@Service
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

        ReputationSingleReviewDraftResult result = aiSingleReviewDraftFactory.create(
                        companyId,
                        reportEnvelope.jobId(),
                        packEnvelope.jobId(),
                        reportEnvelope.report(),
                        packEnvelope.pack(),
                        safeRequest,
                        idea,
                        facts
                )
                .orElseGet(() -> localResult(companyId, reportEnvelope, packEnvelope, safeRequest, idea, facts));
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

        ReputationBatchReviewDraftResult generated = aiSingleReviewDraftFactory.createBatch(
                        companyId,
                        reportEnvelope.jobId(),
                        packEnvelope.jobId(),
                        packEnvelope.pack(),
                        safeRequest,
                        brief,
                        slots
                )
                .orElseGet(() -> localBatchResult(companyId, reportEnvelope, packEnvelope, safeRequest, targets));
        ReputationBatchReviewDraftResult result = fillMissingBatchDrafts(companyId, reportEnvelope, packEnvelope, safeRequest, targets, generated);
        return sanitizeCompanyName(result, reportEnvelope, packEnvelope);
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
        int variant = Math.floorMod((request.targetReviewId() == null ? ThreadLocalRandom.current().nextInt() : request.targetReviewId().hashCode()), 10);
        String draft = switch (variant) {
            case 0 -> "Нужно было разобраться с " + shortText(cleanIdea, 130) + ", без долгих общих разговоров. Спокойно объяснили, что важно учесть, и отдельно уточнили про " + shortText(first, 120) + ". В итоге стало понятнее, как двигаться дальше.";
            case 1 -> "Сначала сомневался, стоит ли сразу договариваться. После разговора про " + shortText(first, 130) + " и " + shortText(second, 110) + " стало проще принять решение.";
            case 2 -> "Не стали уходить в общие обещания: по " + shortText(cleanIdea, 120) + " быстро разложили основные моменты. Особенно помогло, что заранее проговорили " + shortText(firstNonBlank(first, second), 140) + ".";
            case 3 -> "Искал нормальный вариант без лишней суеты, поэтому сначала уточнил детали. По " + shortText(first, 130) + " ответили понятно, осталось спокойное впечатление.";
            case 4 -> "Понравилось, что разговор был предметный. Мне было важно " + shortText(cleanIdea, 120) + ", а в ответ дали конкретику по " + shortText(firstNonBlank(first, second), 130) + ".";
            case 5 -> "До обращения были вопросы по " + shortText(cleanIdea, 120) + ". Нормально сориентировали по " + shortText(first, 120) + ", без давления и лишних обещаний.";
            case 6 -> "Выбирал между несколькими вариантами и после уточнения деталей уже спокойнее определился. По " + shortText(first, 120) + " всё объяснили достаточно понятно.";
            case 7 -> "Пришёл не за красивыми словами, а за понятными ответами. Спросил про " + shortText(cleanIdea, 110) + ", отдельно обсудили " + shortText(firstNonBlank(first, second), 130) + ".";
            case 8 -> "После работы было не до долгих переписок, хотелось быстро понять условия. По " + shortText(first, 120) + " ответили по делу, так что вопросов стало меньше.";
            default -> "По итогу осталось ровное впечатление. По " + shortText(cleanIdea, 110) + " дали понятные ориентиры, а детали вроде " + shortText(firstNonBlank(first, second, third), 120) + " можно было уточнить сразу.";
        };
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
            addIdeaValues(ideas, pack.honestReviewTopics(), 180);
            addIdeaValues(ideas, pack.reviewDraftTemplates(), 180);
        }

        List<String> reportIdeas = reviewIdeasFromReport(report);
        reportIdeas.forEach(value -> addIdeaValue(ideas, value, 180));
        reportIdeas.forEach(value -> addBriefValues(services, serviceHintsFromIdea(value), 80));

        commercialFacts(report, "").forEach(value -> {
            if (!isCustomerFacingBriefFact(value)) {
                return;
            }
            if (hasPrice(value) && isUsablePriceFact(value)) {
                addBriefValue(prices, value, 160);
            }
            if (commercialDetail(value) && isConcreteCommercialItem(value)) {
                addBriefValue(products, value, 180);
            } else {
                addBriefValue(services, value, 140);
            }
        });

        if (requestTargets != null) {
            for (ReputationBatchReviewDraftTarget target : requestTargets) {
                if (!isWeakTheme(target.idea())) {
                    addIdeaValue(ideas, target.idea(), 180);
                }
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
                ideas.stream().limit(24).toList(),
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
                "ходов", "подвес", "стартер", "генератор", "гбц", "замена масла", "техобслуж"
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
                "начать с личной причины клиента, потом одна конкретная деталь",
                "начать с результата после обращения, потом коротко объяснить повод",
                "начать с сомнения или осторожности, потом показать что стало понятно",
                "начать с бытовой детали: дорога, запись, ожидание или удобство",
                "начать сразу с услуги/товара, без названия компании",
                "мини-история: задача, деталь общения, итог"
        );
        List<String> lengths = List.of(
                "2 коротких предложения",
                "3-4 предложения",
                "1-2 предложения, очень лаконично",
                "5-6 предложений, мини-история",
                "3 предложения без рекламных оценок"
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
        for (int index = 0; index < targets.size(); index++) {
            ReputationBatchReviewDraftTarget target = targets.get(index);
            String theme = firstNonBlank(
                    isWeakTheme(target.idea()) ? "" : cleanReviewIdeaText(target.idea()),
                    pick(randomizedIdeas, index),
                    "живой отзыв по фактам заказа"
            );
            List<String> themeServiceHints = serviceHintsFromIdea(theme);
            String service = firstNonBlank(
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
            List<String> mustUse = mergePriorityFacts(
                    List.of(service, product, price, advantage, extra),
                    List.of(),
                    5
            );
            List<String> mayUse = mergePriorityFacts(
                    List.of(service, product, price, advantage, extra),
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
                    tones.get(index % tones.size()),
                    lengths.get(index % lengths.size()),
                    structures.get(index % structures.size()),
                    mustUse,
                    mayUse,
                    clientMustConfirm,
                    target.previousDraft()
            ));
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
        String clean = cleanReviewIdeaText(value);
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

    private boolean isSimplePrice(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        return clean.matches("^\\d[\\d\\s]*(?:[,.]\\d+)?\\s*(?:₽|руб\\.?|р\\.?)$");
    }

    private boolean isUsablePriceFact(String value) {
        String clean = value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return hasPrice(clean)
                && clean.length() <= 160
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
        if (clean.contains("приемк") || clean.contains("приёмк") || clean.contains("застройщик")) {
            result.add("приемка квартиры");
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
        if (lower.contains("приемк") || lower.contains("приёмк")) {
            return "приемке квартиры";
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
                .replaceAll("(?iu)^\\s*отзыв\\s+(?:о|об|про)\\s+", "")
                .replaceAll("(?iu)\\s*\\bдобавить\\b.*$", "")
                .replaceAll("(?iu)\\s*\\bуказать\\b.*$", "")
                .replaceAll("(?iu)\\s*\\bупомянуть\\b.*$", "")
                .replaceAll("\\.\\.\\.$", "")
                .trim();
        return stripBoundaryQuotes(clean);
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
        String context = normalizeProductSignal(theme + " " + service);
        if (item.isBlank()) {
            return false;
        }
        boolean contextLaserTag = context.contains("лазертаг");
        boolean productQuestScenario = item.matches(".*(квест|оно|психоз|экзорцизм|тюрьм|сталкер|космическ|артефакт).*");
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
            return manualIdea.trim();
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
                .map(String::trim)
                .distinct()
                .toList();
        if (candidates.isEmpty()) {
            return "Показать одну конкретную услугу или УТП компании и оставить место для личного опыта клиента.";
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private List<String> reviewIdeasFromReport(DeepCompanyResearchReport report) {
        return report.sections().stream()
                .flatMap(section -> reviewIdeaItems(section.title(), section.body()).stream())
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(12)
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
                if (!ideas.isEmpty()) {
                    break;
                }
                continue;
            }
            String idea = cleanMarkdownText(line.replaceFirst("^(?:\\d+[.)]|[-*])\\s+", ""));
            if (!idea.isBlank() && !ideas.contains(idea)) {
                ideas.add(idea);
            }
        }
        return ideas.stream().limit(20).toList();
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
                    .forEach(result::add);
        }
        if (fallback != null) {
            fallback.stream()
                    .filter(value -> value != null && !value.isBlank())
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

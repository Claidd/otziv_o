package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewTemplatesApplyRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewTemplatesRequest;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ReputationReviewTemplatesResult;
import com.hunt.otziv.reputationai.persistence.ReputationContentPackJobEntity;
import com.hunt.otziv.reputationai.persistence.ReputationContentPackJobRepository;
import com.hunt.otziv.reputationai.persistence.ReputationDeepReportJobEntity;
import com.hunt.otziv.reputationai.persistence.ReputationDeepReportJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReputationReviewTemplateService {

    private final ReputationContentPackJobRepository contentPackJobRepository;
    private final ReputationDeepReportJobRepository deepReportJobRepository;
    private final AiReviewTemplateFactory aiReviewTemplateFactory;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ReputationReviewTemplatesResult generate(Long companyId, ReputationReviewTemplatesRequest request) {
        ReputationReviewTemplatesRequest safeRequest = request == null
                ? new ReputationReviewTemplatesRequest(null, null, null, null, null, null, null)
                : request;
        PackEnvelope packEnvelope = contentPack(companyId, safeRequest.contentPackJobId())
                .orElseThrow(() -> new IllegalStateException("Сначала соберите или загрузите готовый AI-пакет компании."));
        ReportEnvelope reportEnvelope = deepReport(companyId, safeRequest.deepReportJobId())
                .orElseThrow(() -> new IllegalStateException("Сначала соберите готовый глубокий отчет компании."));

        return aiReviewTemplateFactory.create(
                        companyId,
                        reportEnvelope.jobId(),
                        packEnvelope.jobId(),
                        reportEnvelope.report(),
                        packEnvelope.pack(),
                        safeRequest
                )
                .orElseGet(() -> {
                    if (aiReviewTemplateFactory.isOpenAiAvailable()) {
                        throw new IllegalStateException("AI-провайдер не подготовил улучшенные отзывы. Проверьте маршрут, модель и лимиты активного провайдера.");
                    }
                    return localResult(companyId, reportEnvelope, packEnvelope, safeRequest);
                });
    }

    @Transactional
    public ReputationContentPack apply(Long companyId, ReputationReviewTemplatesApplyRequest request) {
        if (request == null || request.honestReviewTopics().isEmpty() || request.reviewDraftTemplates().isEmpty()) {
            throw new IllegalStateException("Нет улучшенных тем или черновиков отзывов для замены.");
        }
        ReputationContentPackJobEntity entity = contentPackEntity(companyId, request.contentPackJobId())
                .orElseThrow(() -> new IllegalStateException("Готовый AI-пакет для замены отзывов не найден."));
        ReputationContentPack pack = readPack(entity.getPackJson());
        ReputationContentPack updated = new ReputationContentPack(
                pack.researchSnapshot(),
                pack.companyProfile(),
                pack.utp(),
                pack.adTexts(),
                pack.socialPostTopics(),
                pack.socialPosts(),
                request.honestReviewTopics(),
                request.reviewDraftTemplates(),
                pack.positiveReviewReplies(),
                pack.negativeReviewReplies(),
                pack.sourceUrls(),
                mergedSafetyNotes(pack.safetyNotes())
        );
        entity.setPackJson(writeJson(updated));
        contentPackJobRepository.save(entity);
        return updated;
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

    private ReputationReviewTemplatesResult localResult(
            Long companyId,
            ReportEnvelope reportEnvelope,
            PackEnvelope packEnvelope,
            ReputationReviewTemplatesRequest request
    ) {
        ReputationContentPack pack = packEnvelope.pack();
        List<String> facts = factBank(pack, reportEnvelope.report());
        List<String> topics = reviewTopics(pack, facts, request.topicsCount());
        List<String> drafts = reviewDrafts(pack, facts, request.draftsCount());
        return new ReputationReviewTemplatesResult(
                companyId,
                pack.researchSnapshot().companyName(),
                reportEnvelope.jobId(),
                packEnvelope.jobId(),
                "local",
                "fallback",
                topics,
                drafts,
                List.of(
                        "AI-провайдер недоступен или не вернул результат: создан локальный вариант на основе текущего AI-пакета.",
                        "Перед публикацией клиент должен добавить только реальный личный опыт."
                ),
                LocalDateTime.now()
        );
    }

    private List<String> reviewTopics(ReputationContentPack pack, List<String> facts, int count) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        String company = firstNonBlank(pack.researchSnapshot().companyName(), "компании");
        String product = firstNonBlank(firstItem(pack.companyProfile().products()), firstItem(pack.researchSnapshot().products()), "услуги");
        values.add("Выбор " + product + " в " + company + ": какая конкретная задача была у клиента и какой факт помог принять решение.");
        for (int i = 0; i < Math.max(count, facts.size()); i++) {
            String fact = facts.isEmpty() ? product : facts.get(i % facts.size());
            values.add(switch (i % 6) {
                case 0 -> "Услуга или пакет: " + fact + ". Клиент добавляет, что именно выбрал и почему это подошло.";
                case 1 -> "Логистика первого визита: " + fact + ". Клиент добавляет, насколько удобно было добраться, войти или сориентироваться.";
                case 2 -> "Доверие перед обращением: " + fact + ". Клиент добавляет, что подтвердилось в общении или на месте.";
                case 3 -> "Сравнение условий: " + fact + ". Клиент добавляет, какие условия заранее уточнил и что оказалось важным.";
                case 4 -> "Сценарий из контента: " + fact + ". Клиент добавляет свой повод, состав компании и итог.";
                default -> "Деталь, которая помогает выбрать: " + fact + ". Клиент добавляет живой результат без выдуманных эмоций.";
            });
        }
        return values.stream().limit(count).toList();
    }

    private List<String> reviewDrafts(ReputationContentPack pack, List<String> facts, int count) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        String company = firstNonBlank(pack.researchSnapshot().companyName(), "компанию");
        String city = firstNonBlank(pack.researchSnapshot().city(), "городе");
        List<String> safeFacts = facts.isEmpty()
                ? List.of(firstNonBlank(firstItem(pack.utp()), firstItem(pack.adTexts()), "есть несколько подтвержденных услуг и условий"))
                : facts;
        for (int i = 0; i < Math.max(count, safeFacts.size()); i++) {
            String first = safeFacts.get(i % safeFacts.size());
            String second = safeFacts.get((i + 2) % safeFacts.size());
            values.add(switch (i % 7) {
                case 0 -> "Искали вариант в " + city + " под конкретный повод, поэтому смотрели не только название, но и детали. У " + company + " полезно заранее проверить: " + first + ". В отзыве клиенту стоит добавить, какую услугу выбрал, кто был с ним и что на месте подтвердилось.";
                case 1 -> "Перед обращением в " + company + " важно было понять условия без лишней переписки. Из найденного хорошо работает такой факт: " + first + ". Отдельно можно упомянуть " + second + ", а личную часть дописать через реальный итог визита.";
                case 2 -> company + " удобно описывать не общей фразой, а через один конкретный сценарий: " + first + ". Такой отзыв будет сильнее, если клиент добавит, что именно заказывал, что уточнял заранее и какая деталь оказалась самой полезной.";
                case 3 -> "Для первого визита помогает, когда заранее понятны адрес, вход, условия или состав услуги. По " + company + " можно опереться на факт: " + first + ". В личной части достаточно добавить реальную ситуацию и короткий вывод, подошел ли формат.";
                case 4 -> "Если сравнивать варианты в " + city + ", у " + company + " стоит подсветить не эмоции ради эмоций, а практическую пользу: " + first + ". Клиент может дополнить отзыв тем, что выбрал, как прошла запись и что оказалось удобным.";
                case 5 -> "В отзыве о " + company + " лучше взять одну понятную деталь, например: " + first + ". Потом добавить свой опыт общения, визита или заказа, чтобы текст не выглядел рекламным и оставался честным.";
                default -> "Хорошая основа для отзыва - связать личный повод с проверенным фактом. Для " + company + " таким фактом может быть: " + first + ". Дальше клиент добавляет, что было важно именно ему и какой результат он получил.";
            });
        }
        return values.stream().limit(count).toList();
    }

    private List<String> factBank(ReputationContentPack pack, DeepCompanyResearchReport report) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        addAll(values, pack.utp(), 6, 180);
        addAll(values, pack.adTexts(), 4, 170);
        addAll(values, pack.socialPostTopics(), 4, 150);
        addAll(values, pack.companyProfile().advantages(), 4, 150);
        addAll(values, pack.companyProfile().factualWarnings(), 4, 160);
        report.sections().stream()
                .filter(section -> importantSection(section.title(), section.body()))
                .limit(10)
                .map(section -> shortText(section.title() + ": " + section.body(), 220))
                .forEach(values::add);
        addAll(values, report.warnings(), 5, 170);
        report.sources().stream()
                .limit(5)
                .map(source -> shortText(source.title() + ": " + source.note(), 140))
                .forEach(values::add);
        return values.stream().filter(value -> !value.isBlank()).limit(18).toList();
    }

    private void addAll(LinkedHashSet<String> target, List<String> values, int limit, int textLimit) {
        if (values == null) {
            return;
        }
        values.stream().limit(limit).map(value -> shortText(value, textLimit)).forEach(target::add);
    }

    private boolean importantSection(String title, String body) {
        String text = ((title == null ? "" : title) + " " + (body == null ? "" : body)).toLowerCase();
        return text.matches(".*(сводк|профил|услуг|утп|сценари|довер|отзыв|филиал|логист|адрес|парков|вход|этаж|режим|цена|срок|заказ|огранич|качества).*");
    }

    private List<String> mergedSafetyNotes(List<String> current) {
        LinkedHashSet<String> values = new LinkedHashSet<>(current == null ? List.of() : current);
        values.add("Блок отзывов был улучшен отдельной AI-генерацией на основе deep report, УТП, рекламы и постов.");
        values.add("Черновики отзывов нельзя публиковать от имени несуществующих клиентов: клиент должен добавить свой реальный опыт.");
        return values.stream().toList();
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

    private String writeJson(ReputationContentPack pack) {
        try {
            return objectMapper.writeValueAsString(pack);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось сохранить обновленный AI-пакет компании", exception);
        }
    }

    private String firstItem(List<String> values) {
        return values == null || values.isEmpty() ? "" : values.getFirst();
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

    private record PackEnvelope(Long jobId, ReputationContentPack pack) {
    }

    private record ReportEnvelope(Long jobId, DeepCompanyResearchReport report) {
    }
}

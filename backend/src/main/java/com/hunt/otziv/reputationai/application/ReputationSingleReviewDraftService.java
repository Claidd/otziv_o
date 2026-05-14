package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.api.dto.ReputationSingleReviewDraftRequest;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ReputationSingleReviewDraftResult;
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
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

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
        PackEnvelope packEnvelope = contentPack(companyId, safeRequest.contentPackJobId())
                .orElseThrow(() -> new IllegalStateException("Сначала соберите или загрузите готовый AI-пакет компании."));
        ReportEnvelope reportEnvelope = deepReport(companyId, safeRequest.deepReportJobId())
                .orElseThrow(() -> new IllegalStateException("Сначала соберите или загрузите готовый глубокий отчет компании."));
        String idea = selectedIdea(packEnvelope.pack(), safeRequest.idea());
        List<String> facts = sourceFacts(packEnvelope.pack(), reportEnvelope.report(), idea);

        return aiSingleReviewDraftFactory.create(
                        companyId,
                        reportEnvelope.jobId(),
                        packEnvelope.jobId(),
                        reportEnvelope.report(),
                        packEnvelope.pack(),
                        safeRequest,
                        idea,
                        facts
                )
                .orElseGet(() -> {
                    if (aiSingleReviewDraftFactory.isOpenAiAvailable()) {
                        throw new IllegalStateException("OpenAI не подготовил точечный черновик отзыва. Проверьте маршрут, модель и лимиты OpenAI.");
                    }
                    return localResult(companyId, reportEnvelope, packEnvelope, safeRequest, idea, facts);
                });
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
        String company = firstNonBlank(pack.researchSnapshot().companyName(), "компанию");
        String city = firstNonBlank(pack.researchSnapshot().city(), "городе");
        List<String> safeFacts = facts.isEmpty() ? List.of(idea) : facts;
        String first = safeFacts.getFirst();
        String second = safeFacts.get(Math.min(1, safeFacts.size() - 1));
        String third = safeFacts.get(Math.min(2, safeFacts.size() - 1));
        int variant = Math.floorMod((idea + request.style()).hashCode(), 5);
        String draft = switch (variant) {
            case 0 -> "Для выбора в " + city + " важнее всего была понятная задача, а не просто громкое название. У " + company + " можно опереться на такой факт: " + shortText(first, 190) + ". Ещё стоит заранее проверить: " + shortText(second, 160) + ". В личной части клиенту достаточно добавить, что именно он выбрал, как прошёл контакт с компанией и какая деталь реально помогла.";
            case 1 -> "Сначала смотрел(а) не эмоции, а практические детали: где находится компания, что входит в услугу и какие условия лучше уточнить заранее. По " + company + " сильная основа для отзыва такая: " + shortText(first, 190) + ". Дополнительно можно упомянуть " + shortText(third, 150) + ". Реальный опыт лучше дописать одной конкретной ситуацией из визита или заказа.";
            case 2 -> company + " в " + city + " лучше описывать через конкретный сценарий: " + shortText(idea, 190) + ". Чтобы отзыв был полезен следующим клиентам, в него стоит добавить факт: " + shortText(first, 170) + ", а также личный итог: что выбрали, что уточнили и совпали ли ожидания.";
            case 3 -> "Хороший отзыв здесь может строиться вокруг одной детали, которая помогает принять решение. Для " + company + " это: " + shortText(first, 190) + ". Если клиент расскажет, как эта деталь проявилась в реальном обращении, текст получится и честным, и полезным, без лишней рекламности.";
            default -> "Перед обращением в " + company + " полезно заранее понимать не только услугу, но и условия вокруг неё. В основе черновика можно использовать: " + shortText(first, 190) + ". Затем клиент добавляет свой повод, выбранный формат и одну живую деталь результата.";
        };
        List<String> safetyNotes = List.of(
                "OpenAI недоступен или не вернул результат: создан локальный черновик по текущему AI-пакету.",
                "Клиент должен добавить свой реальный опыт перед публикацией."
        );
        return new ReputationSingleReviewDraftResult(
                companyId,
                company,
                reportEnvelope.jobId(),
                packEnvelope.jobId(),
                "local",
                "fallback",
                idea,
                request.style(),
                draft,
                facts,
                safetyNotes,
                reviewSafetyService.check(draft, facts),
                LocalDateTime.now()
        );
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

    private List<String> sourceFacts(ReputationContentPack pack, DeepCompanyResearchReport report, String idea) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add(shortText(idea, 220));
        pack.utp().stream().limit(2).map(value -> shortText(value, 220)).forEach(values::add);
        pack.adTexts().stream().limit(1).map(value -> shortText(value, 220)).forEach(values::add);
        pack.socialPostTopics().stream().limit(1).map(value -> shortText(value, 180)).forEach(values::add);
        pack.companyProfile().advantages().stream().limit(2).map(value -> shortText(value, 180)).forEach(values::add);
        report.sections().stream()
                .filter(section -> importantSection(section.title(), section.body()))
                .limit(4)
                .map(section -> shortText(section.title() + ": " + section.body(), 260))
                .forEach(values::add);
        report.warnings().stream().limit(3).map(value -> shortText(value, 180)).forEach(values::add);
        return values.stream().filter(value -> !value.isBlank()).limit(9).toList();
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

    private record PackEnvelope(Long jobId, ReputationContentPack pack) {
    }

    private record ReportEnvelope(Long jobId, DeepCompanyResearchReport report) {
    }
}

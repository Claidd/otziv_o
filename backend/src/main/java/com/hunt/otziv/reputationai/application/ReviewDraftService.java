package com.hunt.otziv.reputationai.application;

import com.hunt.otziv.reputationai.api.dto.ReputationReviewDraftRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewRewriteRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewRewriteResponse;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import com.hunt.otziv.reputationai.domain.ReviewDraftResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewDraftService {

    private final CompanyResearchService researchService;
    private final ReviewSafetyService reviewSafetyService;

    public ReviewDraftResult createDraft(Long companyId, ReputationReviewDraftRequest request) {
        ResearchSnapshot snapshot = researchService.findLatestSnapshot(companyId)
                .orElseGet(() -> researchService.createSnapshot(companyId, null));
        ReputationReviewDraftRequest safeRequest = request == null
                ? new ReputationReviewDraftRequest(null, List.of(), "естественный", "medium")
                : request;

        List<String> points = cleanList(safeRequest.realExperiencePoints());
        String product = firstNonBlank(
                safeRequest.productOrService(),
                snapshot.products().isEmpty() ? null : snapshot.products().getFirst(),
                "товар или услугу"
        );

        String draft = points.isEmpty()
                ? buildTemplateDraft(product, snapshot.companyName())
                : buildFactBasedDraft(product, snapshot.companyName(), points, safeRequest.length());
        String warning = "Перед публикацией клиент должен отредактировать черновик под свой реальный опыт. Не добавляйте факты, которых не было.";

        return new ReviewDraftResult(
                draft,
                warning,
                points,
                reviewSafetyService.check(draft, points)
        );
    }

    public ReputationReviewRewriteResponse rewrite(ReputationReviewRewriteRequest request) {
        String source = request == null ? "" : request.text();
        String rewritten = normalizeReviewText(source);
        return new ReputationReviewRewriteResponse(
                rewritten,
                reviewSafetyService.check(rewritten, List.of())
        );
    }

    private String buildFactBasedDraft(String product, String companyName, List<String> points, String length) {
        StringBuilder draft = new StringBuilder();
        draft.append("Покупал(а) ").append(product);
        if (!companyName.isBlank()) {
            draft.append(" в ").append(companyName);
        }
        draft.append(". ");

        for (int i = 0; i < points.size(); i++) {
            String point = points.get(i);
            if (i == 0) {
                draft.append("Из того, что запомнилось: ").append(point).append(". ");
            } else {
                draft.append("Еще отмечу: ").append(point).append(". ");
            }
        }

        if (!"short".equalsIgnoreCase(length)) {
            draft.append("В целом опыт получился понятный, без лишней сложности. ");
        }
        draft.append("Перед публикацией я бы еще уточнил(а) детали, чтобы текст точно отражал мой опыт.");
        return draft.toString();
    }

    private String buildTemplateDraft(String product, String companyName) {
        return "Покупал(а) " + product
                + (companyName == null || companyName.isBlank() ? "" : " в " + companyName)
                + ". Понравилось, что [укажите реальный плюс: консультация, доставка, качество, цена или удобство]. "
                + "Отдельно отмечу [добавьте конкретный факт из вашего опыта].";
    }

    private String normalizeReviewText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        return text.trim()
                .replaceAll("\\s+", " ")
                .replace("просто супер", "в целом понравилось")
                .replace("Просто супер", "В целом понравилось")
                .replace("в восторге", "остался(лась) доволен(на)")
                .replace("В восторге", "Остался(лась) доволен(на)")
                .replace("на высшем уровне", "хорошо организовано");
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return "";
    }
}

package com.hunt.otziv.reputationai.application;

import com.hunt.otziv.reputationai.api.dto.ReputationReviewReplyRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewReplyResponse;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewReplyService {

    private final CompanyResearchService researchService;

    public ReputationReviewReplyResponse positive(Long companyId, ReputationReviewReplyRequest request) {
        ResearchSnapshot snapshot = researchService.findLatestSnapshot(companyId)
                .orElseGet(() -> researchService.createSnapshot(companyId, null));
        int count = clamp(request == null ? null : request.count(), 1, 10, 3);
        String companyName = firstNonBlank(snapshot.companyName(), "нашу компанию");

        List<String> base = List.of(
                "Спасибо за отзыв! Рады, что вы остались довольны обращением в " + companyName + ". Будем рады видеть вас снова.",
                "Благодарим за теплые слова. Нам приятно, что покупка прошла удобно и результат оправдал ожидания.",
                "Спасибо, что поделились впечатлениями. Передадим вашу обратную связь команде."
        );

        return new ReputationReviewReplyResponse(repeat(base, count), "Ответы можно адаптировать под конкретный отзыв перед публикацией.");
    }

    public ReputationReviewReplyResponse negative(Long companyId, ReputationReviewReplyRequest request) {
        ResearchSnapshot snapshot = researchService.findLatestSnapshot(companyId)
                .orElseGet(() -> researchService.createSnapshot(companyId, null));
        int count = clamp(request == null ? null : request.count(), 1, 10, 3);
        String companyName = firstNonBlank(snapshot.companyName(), "компании");

        List<String> base = List.of(
                "Спасибо, что написали. Нам жаль, что опыт обращения в " + companyName + " оказался не таким, как ожидалось. Пожалуйста, уточните детали, чтобы мы могли разобраться.",
                "Примем обратную связь в работу. Уточните, пожалуйста, дату обращения и детали ситуации, чтобы мы проверили информацию.",
                "Нам важно разобраться в ситуации. Если вы оставите контакт или номер заказа, менеджер сможет проверить детали и вернуться с ответом."
        );

        return new ReputationReviewReplyResponse(repeat(base, count), "В ответах на негатив лучше не спорить, а запросить детали и предложить разбор ситуации.");
    }

    private List<String> repeat(List<String> base, int count) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(base.get(i % base.size()));
        }
        return result;
    }

    private int clamp(Integer value, int min, int max, int fallback) {
        int actual = value == null ? fallback : value;
        return Math.max(min, Math.min(max, actual));
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

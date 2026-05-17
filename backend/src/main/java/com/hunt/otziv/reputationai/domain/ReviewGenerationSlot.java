package com.hunt.otziv.reputationai.domain;

import java.util.List;

public record ReviewGenerationSlot(
        Long reviewId,
        String theme,
        String service,
        String product,
        String price,
        String advantage,
        String extraDetail,
        String tone,
        String length,
        String structure,
        List<String> mustUse,
        List<String> mayUse,
        List<String> clientMustConfirm,
        String previousDraft
) {
    public ReviewGenerationSlot {
        theme = theme == null ? "" : theme.trim();
        service = service == null ? "" : service.trim();
        product = product == null ? "" : product.trim();
        price = price == null ? "" : price.trim();
        advantage = advantage == null ? "" : advantage.trim();
        extraDetail = extraDetail == null ? "" : extraDetail.trim();
        tone = tone == null ? "" : tone.trim();
        length = length == null ? "" : length.trim();
        structure = structure == null ? "" : structure.trim();
        previousDraft = previousDraft == null ? "" : previousDraft.trim();
        mustUse = clean(mustUse, 6);
        mayUse = clean(mayUse, 10);
        clientMustConfirm = clean(clientMustConfirm, 8);
    }

    private static List<String> clean(List<String> values, int limit) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(limit)
                .toList();
    }
}

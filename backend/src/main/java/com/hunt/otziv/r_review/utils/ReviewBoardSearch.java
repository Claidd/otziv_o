package com.hunt.otziv.r_review.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ReviewBoardSearch {

    private ReviewBoardSearch() {
    }

    public static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public static Long parseKeywordLong(String keyword) {
        if (!hasText(keyword)) {
            return null;
        }
        String trimmed = keyword.trim();
        if (!trimmed.matches("\\d+")) {
            return null;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public static UUID parseKeywordUuid(String keyword) {
        if (!hasText(keyword)) {
            return null;
        }
        try {
            return UUID.fromString(keyword.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static String keywordPredicate(boolean hasKeywordLong, boolean hasKeywordUuid) {
        List<String> parts = new ArrayList<>(List.of(
                "LOWER(COALESCE(r.text, '')) LIKE :keyword",
                "LOWER(COALESCE(r.answer, '')) LIKE :keyword",
                "LOWER(COALESCE(c.title, '')) LIKE :keyword",
                "LOWER(COALESCE(c.commentsCompany, '')) LIKE :keyword",
                "LOWER(COALESCE(o.zametka, '')) LIKE :keyword",
                "LOWER(COALESCE(os.title, '')) LIKE :keyword",
                "LOWER(COALESCE(city.title, '')) LIKE :keyword",
                "LOWER(COALESCE(f.title, '')) LIKE :keyword",
                "LOWER(COALESCE(b.fio, '')) LIKE :keyword",
                "LOWER(COALESCE(wu.fio, '')) LIKE :keyword",
                "LOWER(COALESCE(mu.fio, '')) LIKE :keyword",
                "LOWER(COALESCE(rp.title, '')) LIKE :keyword",
                "LOWER(COALESCE(dp.title, '')) LIKE :keyword",
                "LOWER(COALESCE(cat.categoryTitle, '')) LIKE :keyword",
                "LOWER(COALESCE(sub.subCategoryTitle, '')) LIKE :keyword",
                "LOWER(COALESCE(d.comment, '')) LIKE :keyword"
        ));

        if (hasKeywordLong) {
            parts.add("r.id = :keywordLong");
            parts.add("o.id = :keywordLong");
            parts.add("c.id = :keywordLong");
        }
        if (hasKeywordUuid) {
            parts.add("d.id = :keywordUuid");
        }

        return "(" + String.join(" OR ", parts) + ")";
    }
}

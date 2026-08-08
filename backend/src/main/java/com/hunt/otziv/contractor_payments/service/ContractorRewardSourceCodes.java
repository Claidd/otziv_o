package com.hunt.otziv.contractor_payments.service;

import java.util.List;

/**
 * Stable internal identifiers for contractor reward sources. They are kept
 * deliberately separate from UI labels and fit the existing VARCHAR(64)
 * columns in both {@code zp} and the contractor ledger.
 */
public final class ContractorRewardSourceCodes {

    public static final String LEGACY_ORDER_MANAGER = "ORDER_MANAGER_REWARD";
    public static final String LEGACY_ORDER_SPECIALIST = "ORDER_SPECIALIST_REWARD";
    public static final String LEGACY_PERFORMER_PRODUCT = "PERFORMER_PRODUCT_REWARD";

    public static final String ORDER_COMPLETION_MANAGER = "ORDER_COMPLETION_MANAGER";
    public static final String ORDER_COMPLETION_SPECIALIST = "ORDER_COMPLETION_SPECIALIST";
    public static final String PERFORMER_PRODUCT_COMPLETION = "PERFORMER_PRODUCT_COMPLETION";
    public static final String BAD_REVIEW_DONE_MARKER_PREFIX = "BAD_REVIEW_DONE:";
    public static final String BAD_REVIEW_CANCEL_MARKER_PREFIX = "BAD_REVIEW_CANCEL:";

    public static final String BAD_REVIEW_MANAGER_PREFIX = "BAD_REVIEW_DONE_MANAGER:";
    public static final String BAD_REVIEW_SPECIALIST_PREFIX = "BAD_REVIEW_DONE_SPECIALIST:";
    private static final String BAD_REVIEW_CANCEL_MANAGER_PREFIX = "BAD_REVIEW_CANCEL_MANAGER:";
    private static final String BAD_REVIEW_CANCEL_SPECIALIST_PREFIX = "BAD_REVIEW_CANCEL_SPECIALIST:";

    public static final List<String> REQUIRED_ORDER_COMPLETION_MARKERS = List.of(
            ORDER_COMPLETION_MANAGER,
            ORDER_COMPLETION_SPECIALIST,
            PERFORMER_PRODUCT_COMPLETION
    );

    private ContractorRewardSourceCodes() {
    }

    public static String badReviewManager(Long taskId) {
        return taskSource(BAD_REVIEW_MANAGER_PREFIX, taskId);
    }

    public static String badReviewSpecialist(Long taskId) {
        return taskSource(BAD_REVIEW_SPECIALIST_PREFIX, taskId);
    }

    public static String badReviewCancelManager(Long taskId) {
        return taskSource(BAD_REVIEW_CANCEL_MANAGER_PREFIX, taskId);
    }

    public static String badReviewCancelSpecialist(Long taskId) {
        return taskSource(BAD_REVIEW_CANCEL_SPECIALIST_PREFIX, taskId);
    }

    public static String badReviewDoneMarker(Long taskId) {
        return taskSource(BAD_REVIEW_DONE_MARKER_PREFIX, taskId);
    }

    public static String badReviewCancelMarker(Long taskId) {
        return taskSource(BAD_REVIEW_CANCEL_MARKER_PREFIX, taskId);
    }

    public static boolean isCompletionBased(String source) {
        return ORDER_COMPLETION_MANAGER.equals(source)
                || ORDER_COMPLETION_SPECIALIST.equals(source)
                || PERFORMER_PRODUCT_COMPLETION.equals(source)
                || startsWith(source, BAD_REVIEW_MANAGER_PREFIX)
                || startsWith(source, BAD_REVIEW_SPECIALIST_PREFIX)
                || startsWith(source, BAD_REVIEW_CANCEL_MANAGER_PREFIX)
                || startsWith(source, BAD_REVIEW_CANCEL_SPECIALIST_PREFIX);
    }

    public static boolean isOrderSpecialistAttributionSource(String source) {
        return LEGACY_ORDER_SPECIALIST.equals(source)
                || ORDER_COMPLETION_SPECIALIST.equals(source);
    }

    public static boolean isPerformerProductAttributionSource(String source) {
        return LEGACY_PERFORMER_PRODUCT.equals(source)
                || PERFORMER_PRODUCT_COMPLETION.equals(source);
    }

    public static boolean isLegacyOrderReward(String source) {
        return LEGACY_ORDER_MANAGER.equals(source)
                || LEGACY_ORDER_SPECIALIST.equals(source);
    }

    public static boolean isLegacyEarnedReward(String source) {
        return isLegacyOrderReward(source) || LEGACY_PERFORMER_PRODUCT.equals(source);
    }

    private static String taskSource(String prefix, Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw new IllegalArgumentException("Task id is required for contractor reward source");
        }
        String source = prefix + taskId;
        if (source.length() > 64) {
            throw new IllegalArgumentException("Contractor reward source exceeds 64 characters");
        }
        return source;
    }

    private static boolean startsWith(String source, String prefix) {
        return source != null && source.startsWith(prefix);
    }
}

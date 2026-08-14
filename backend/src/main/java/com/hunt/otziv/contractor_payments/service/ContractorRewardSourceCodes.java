package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorRole;
import java.util.List;
import java.util.OptionalLong;

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
                || completionTaskId(source).isPresent();
    }

    /**
     * Parses only source identifiers that this application can generate.
     * Prefix lookalikes, blank suffixes, non-numeric values and overflows stay
     * unclassified so financial transitions fail closed.
     */
    public static OptionalLong completionTaskId(String source) {
        for (String prefix : List.of(
                BAD_REVIEW_MANAGER_PREFIX,
                BAD_REVIEW_SPECIALIST_PREFIX,
                BAD_REVIEW_CANCEL_MANAGER_PREFIX,
                BAD_REVIEW_CANCEL_SPECIALIST_PREFIX
        )) {
            if (!startsWith(source, prefix)) {
                continue;
            }
            String suffix = source.substring(prefix.length());
            if (suffix.isEmpty()
                    || suffix.charAt(0) < '1'
                    || suffix.charAt(0) > '9'
                    || !suffix.chars().allMatch(character -> character >= '0' && character <= '9')) {
                return OptionalLong.empty();
            }
            try {
                long taskId = Long.parseLong(suffix);
                return taskId > 0L ? OptionalLong.of(taskId) : OptionalLong.empty();
            } catch (NumberFormatException ignored) {
                return OptionalLong.empty();
            }
        }
        return OptionalLong.empty();
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

    /**
     * The ledger accepts only application-owned source/role pairs. A persisted
     * role or final-attribution flag is not enough to turn an unknown source
     * into contractor debt.
     */
    public static boolean isLedgerSourceCompatible(String source, ContractorRole role) {
        if (source == null || role == null) {
            return false;
        }
        if (role == ContractorRole.MANAGER) {
            return LEGACY_ORDER_MANAGER.equals(source)
                    || LEGACY_PERFORMER_PRODUCT.equals(source)
                    || ORDER_COMPLETION_MANAGER.equals(source)
                    || PERFORMER_PRODUCT_COMPLETION.equals(source)
                    || validTaskSource(source, BAD_REVIEW_MANAGER_PREFIX)
                    || validTaskSource(source, BAD_REVIEW_CANCEL_MANAGER_PREFIX);
        }
        if (role == ContractorRole.SPECIALIST) {
            return LEGACY_ORDER_SPECIALIST.equals(source)
                    || LEGACY_PERFORMER_PRODUCT.equals(source)
                    || ORDER_COMPLETION_SPECIALIST.equals(source)
                    || PERFORMER_PRODUCT_COMPLETION.equals(source)
                    || validTaskSource(source, BAD_REVIEW_SPECIALIST_PREFIX)
                    || validTaskSource(source, BAD_REVIEW_CANCEL_SPECIALIST_PREFIX);
        }
        return false;
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

    private static boolean validTaskSource(String source, String prefix) {
        return startsWith(source, prefix) && completionTaskId(source).isPresent();
    }
}

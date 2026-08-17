package com.hunt.otziv.contractor_payments.service;

/**
 * Canonical form and validation for the phone-or-card transfer destination
 * stored in a contractor payment profile.
 *
 * <p>The API/database field keeps its historical {@code paymentPhone} name for
 * backwards compatibility. New values may contain either an E.164-sized phone
 * number (10-15 digits, with an optional leading {@code +}) or a payment-card
 * number (16-19 digits). Formatting spaces, parentheses and hyphens are not
 * significant and are removed before the value is stored or snapshotted.</p>
 */
public final class ContractorPaymentTransferNumber {

    private static final int PHONE_MIN_DIGITS = 10;
    private static final int PHONE_MAX_DIGITS = 15;
    private static final int CARD_MIN_DIGITS = 16;
    private static final int CARD_MAX_DIGITS = 19;

    private ContractorPaymentTransferNumber() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char symbol = value.charAt(index);
            if (isFormattingSpace(symbol)
                    || isHyphen(symbol)
                    || symbol == '('
                    || symbol == ')') {
                continue;
            }
            normalized.append(symbol);
        }
        return normalized.toString();
    }

    public static boolean isValid(String value) {
        String normalized = normalize(value);
        if (normalized.startsWith("+")) {
            String digits = normalized.substring(1);
            return hasOnlyAsciiDigits(digits)
                    && digits.length() >= PHONE_MIN_DIGITS
                    && digits.length() <= PHONE_MAX_DIGITS;
        }
        if (!hasOnlyAsciiDigits(normalized)) {
            return false;
        }
        int digits = normalized.length();
        return (digits >= PHONE_MIN_DIGITS && digits <= PHONE_MAX_DIGITS)
                || (digits >= CARD_MIN_DIGITS && digits <= CARD_MAX_DIGITS);
    }

    private static boolean hasOnlyAsciiDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char symbol = value.charAt(index);
            if (symbol < '0' || symbol > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isFormattingSpace(char symbol) {
        return Character.isWhitespace(symbol) || Character.isSpaceChar(symbol);
    }

    private static boolean isHyphen(char symbol) {
        return symbol == '-' || Character.getType(symbol) == Character.DASH_PUNCTUATION;
    }
}

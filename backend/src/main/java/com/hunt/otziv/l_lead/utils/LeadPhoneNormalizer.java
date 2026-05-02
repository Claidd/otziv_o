package com.hunt.otziv.l_lead.utils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class LeadPhoneNormalizer {

    private LeadPhoneNormalizer() {
    }

    public static String normalize(String phone) {
        if (phone == null) {
            return "";
        }

        String digits = phone.replaceAll("\\D", "");
        if (digits.isBlank()) {
            return phone.trim();
        }

        if (digits.length() == 10 && digits.startsWith("9")) {
            return "7" + digits;
        }

        if (digits.length() == 11 && digits.startsWith("8")) {
            return "7" + digits.substring(1);
        }

        return digits;
    }

    public static List<String> variants(String phone) {
        String normalized = normalize(phone);
        Set<String> variants = new LinkedHashSet<>();
        variants.add(normalized);

        if (normalized.startsWith("7") && normalized.length() > 1) {
            variants.add("+" + normalized);
            variants.add("8" + normalized.substring(1));
        }

        return List.copyOf(variants);
    }
}

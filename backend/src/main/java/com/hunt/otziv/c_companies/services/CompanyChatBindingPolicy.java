package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_companies.model.Company;
import java.util.Locale;

public final class CompanyChatBindingPolicy {

    private static final String STATUS_STOP = "на стопе";
    private static final String STATUS_BAN = "бан";

    private CompanyChatBindingPolicy() {
    }

    public static boolean isRequired(Company company) {
        if (company == null || company.getStatus() == null || company.getStatus().getTitle() == null) {
            return true;
        }
        String status = company.getStatus().getTitle().trim().toLowerCase(Locale.ROOT);
        return !STATUS_STOP.equals(status) && !STATUS_BAN.equals(status);
    }
}

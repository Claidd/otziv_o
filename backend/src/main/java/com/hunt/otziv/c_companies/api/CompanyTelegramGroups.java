package com.hunt.otziv.c_companies.api;

/** Tells whether a Telegram group is linked to a client company. */
public interface CompanyTelegramGroups {
    boolean isCompanyGroup(long chatId);
}

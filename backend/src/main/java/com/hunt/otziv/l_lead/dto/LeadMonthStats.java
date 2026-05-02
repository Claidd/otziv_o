package com.hunt.otziv.l_lead.dto;

public record LeadMonthStats(
        long currentMonthAll,
        long currentMonthInWork,
        long previousMonthAll,
        long previousMonthInWork
) {}
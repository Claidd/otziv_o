package com.hunt.otziv.c_companies.api;

import java.time.LocalDate;
import java.util.List;

/** Preserves the existing ordered manager/count projection consumed by monthly payment statistics. */
public interface CompanyStatisticsOperations {
    List<Object[]> countNewCompaniesByManager(LocalDate firstDay, LocalDate lastDay);
}

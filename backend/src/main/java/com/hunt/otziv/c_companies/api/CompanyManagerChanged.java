package com.hunt.otziv.c_companies.api;

/**
 * Companies-owned event published synchronously inside an authorized company save when the
 * company's manager changes. The orders module moves the company's open orders to the new
 * manager in the same transaction; paid and finished orders keep the manager who sold them.
 */
public record CompanyManagerChanged(Long companyId, Long previousManagerId, Long newManagerId) { }

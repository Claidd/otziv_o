package com.hunt.otziv.c_companies.api;

import java.util.Map;

/** Scalar chat/sender projection; authorized billing callers freeze this before any network request. */
public interface PaperInvoiceCompanyData { Map<String,Object> read(long companyId); }

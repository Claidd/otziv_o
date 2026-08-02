ALTER TABLE common_invoices
    ADD UNIQUE INDEX uk_common_invoices_token_hash (token_hash),
    ALGORITHM=INPLACE,
    LOCK=NONE;

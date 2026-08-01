ALTER TABLE archive_common_invoices
    ADD UNIQUE INDEX uk_archive_common_invoices_token_hash (token_hash),
    ALGORITHM=INPLACE,
    LOCK=NONE;

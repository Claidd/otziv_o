ALTER TABLE archive_payment_links
    ADD UNIQUE INDEX uk_archive_payment_links_token_hash (token_hash),
    ALGORITHM=INPLACE,
    LOCK=NONE;

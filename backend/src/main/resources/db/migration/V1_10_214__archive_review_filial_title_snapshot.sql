ALTER TABLE archive_reviews
    ADD COLUMN review_filial_title_snapshot VARCHAR(255) NULL AFTER review_filial;

UPDATE archive_reviews ar
JOIN archive_order_details aod
  ON aod.order_detail_id = ar.review_order_details
JOIN archive_orders ao
  ON ao.order_id = aod.order_detail_order
LEFT JOIN filial review_filial
  ON review_filial.filial_id = ar.review_filial
LEFT JOIN filial order_filial
  ON order_filial.filial_id = ao.order_filial
SET ar.review_filial_title_snapshot = CASE
    WHEN NULLIF(TRIM(review_filial.filial_title), '') IS NOT NULL
        THEN TRIM(review_filial.filial_title)
    WHEN ar.review_filial IS NULL OR ar.review_filial = ao.order_filial
        THEN COALESCE(
            NULLIF(TRIM(ao.filial_title_snapshot), ''),
            NULLIF(TRIM(order_filial.filial_title), ''),
            ''
        )
    ELSE NULL
END
WHERE ar.review_filial_title_snapshot IS NULL
   OR CHAR_LENGTH(TRIM(ar.review_filial_title_snapshot)) = 0;

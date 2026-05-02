-- Сначала удаляем старые внешние ключи
ALTER TABLE reviews
    DROP FOREIGN KEY review_filial,
    DROP FOREIGN KEY reviews_order_details;

-- Затем добавляем новые внешние ключи
ALTER TABLE reviews
    ADD CONSTRAINT `reviews_order_details`
        FOREIGN KEY (`review_order_details`)
        REFERENCES `order_details` (`order_detail_id`)
        ON DELETE CASCADE
        ON UPDATE CASCADE;






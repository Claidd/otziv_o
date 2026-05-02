-- Добавление столбца c заметкой в таблицу ботов и установка связи
ALTER TABLE orders
    ADD COLUMN order_zametka VARCHAR(5000) NULL;




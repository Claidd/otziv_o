-- Делаем столбец названия города в таблице городов уникальным
ALTER TABLE cities
    ADD UNIQUE INDEX unique_city_title (city_title);

-- Добавление столбца c отметкой выгула в таблицу с отзывами
ALTER TABLE reviews
    ADD COLUMN review_vigul BOOLEAN DEFAULT 0;

-- Делаем столбец устанавливаем всем текущим записям в таблице выгул, что не выгулен
UPDATE reviews
SET review_vigul = 0;



-- V1_3_0__add_last_nagul_time_to_worker.sql
-- Добавляем поле last_nagul_time в таблицу workers для MySQL

-- 1. Проверяем, существует ли колонка, и добавляем если нет
-- Используем хранимую процедуру для условного добавления
DELIMITER $$

DROP PROCEDURE IF EXISTS add_last_nagul_time_column_if_not_exists $$
CREATE PROCEDURE add_last_nagul_time_column_if_not_exists()
BEGIN
    DECLARE column_exists INT DEFAULT 0;

    -- Проверяем существование колонки
SELECT COUNT(*) INTO column_exists
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'workers'
  AND column_name = 'last_nagul_time';

-- Добавляем колонку если не существует
IF column_exists = 0 THEN
ALTER TABLE workers
    ADD COLUMN last_nagul_time TIMESTAMP NULL
        COMMENT 'Время последнего выгула аккаунта работником';
END IF;
END $$

DELIMITER ;

-- 2. Вызываем процедуру
CALL add_last_nagul_time_column_if_not_exists();

-- 3. Удаляем процедуру
DROP PROCEDURE add_last_nagul_time_column_if_not_exists;

-- 4. Устанавливаем начальное значение
UPDATE workers SET last_nagul_time = NULL;

-- 5. Создаем индекс если не существует
DELIMITER $$

DROP PROCEDURE IF EXISTS create_last_nagul_time_index_if_not_exists $$
CREATE PROCEDURE create_last_nagul_time_index_if_not_exists()
BEGIN
    DECLARE index_exists INT DEFAULT 0;

    -- Проверяем существование индекса
SELECT COUNT(*) INTO index_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'workers'
  AND index_name = 'idx_workers_last_nagul_time';

-- Создаем индекс если не существует
IF index_exists = 0 THEN
CREATE INDEX idx_workers_last_nagul_time ON workers(last_nagul_time);
END IF;
END $$

DELIMITER ;

-- 6. Вызываем процедуру
CALL create_last_nagul_time_index_if_not_exists();

-- 7. Удаляем процедуру
DROP PROCEDURE create_last_nagul_time_index_if_not_exists;
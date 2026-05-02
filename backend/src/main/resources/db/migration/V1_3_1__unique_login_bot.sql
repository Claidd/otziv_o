-- Только добавление индекса
-- Удаление дубликатов
DELETE b1 FROM bots b1
INNER JOIN bots b2
WHERE
    b1.bot_login = b2.bot_login
    AND b1.bot_login IS NOT NULL
    AND b1.bot_login != ''
    AND b1.bot_id > b2.bot_id;

-- Проверяем, существует ли уже индекс
SET @index_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'bots'
    AND INDEX_NAME = 'idx_unique_bot_login'
);

-- Если индекс не существует, создаем его
SET @sql = IF(@index_exists = 0,
    'ALTER TABLE bots ADD UNIQUE INDEX idx_unique_bot_login (bot_login)',
    'SELECT "Index already exists"');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
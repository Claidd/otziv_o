-- 1. Добавляем временную колонку
ALTER TABLE leads ADD COLUMN update_status_new DATETIME;

-- 2. Копируем данные из старой в новую, устанавливая время в 00:00:00
UPDATE leads SET update_status_new = CAST(update_status AS DATETIME);

-- 3. Удаляем старую колонку
ALTER TABLE leads DROP COLUMN update_status;

-- 4. Переименовываем новую в прежнее имя
ALTER TABLE leads CHANGE update_status_new update_status DATETIME;





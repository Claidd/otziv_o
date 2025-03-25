-- Добавление столбца telegram ID для сущности User
ALTER TABLE users ADD COLUMN telegram_chat_id BIGINT UNIQUE;






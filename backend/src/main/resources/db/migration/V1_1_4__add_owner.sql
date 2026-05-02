-- Вставляем роль, если её ещё нет
INSERT IGNORE INTO roles (name) VALUES ('ROLE_OWNER');

-- Вставляем пользователей, проверяя на уникальность логина
-- Вставляем пользователей, игнорируя дубликаты по username
INSERT IGNORE INTO users (username, password, fio, email, phone_number, activate_code, active, create_time)
VALUES
('mia', '$2a$10$CBere4wkN3qfJH/ZZYXRcOGe9XdtMuUHwLwEpt5/9qeq56kQQci56', 'Мия Ригз', 'maw-rad@mail.ru', '89041256288', 'f9869242-3fd0-4464-a299-56f4e2c60e42', 1, '2024-06-12'),
('hunt', '$2a$10$CBere4wkN3qfJH/ZZYXRcOGe9XdtMuUHwLwEpt5/9qeq56kQQci56', 'Иван Хант', 'iquest38@mail.ru', '89016668881', 'f9869242-3fd0-4464-a299-56f4e2c60e42', 1, '2024-06-12'),
('marketolog2', '$2a$10$IfEDIgjdQ0Ox634r2gBZF.k2ORbI7h85LyS4m7S3qm9aI5AWtGcSG', 'Маркетолог Маркетолог 2.', '2.12nps30@mail.ru', '89086431065', 'f9869242-3fd0-4464-a299-56f4e2c60e42', 1, '2024-06-12'),
('operator2', '$2a$10$IfEDIgjdQ0Ox634r2gBZF.k2ORbI7h85LyS4m7S3qm9aI5AWtGcSG', 'Оператор Оператор 2.', '2.12nps31@mail.ru', '89086431057', 'f9869242-3fd0-4464-a299-56f4e2c60e42', 1, '2024-06-12'),
('vika', '$2a$10$KCRjn2ueePe/oCFnA7ZrVelC0AoMIgdrahJ9XIrRsQmueGUW23t7K', 'Вика Ц.', 'iquest138@mail.ru', '89016668882', 'f9869242-3fd0-4464-a299-56f4e2c60e42', 1, '2024-06-12'),
('bella', '$2a$10$eYem8fNT/ly60nV1ZT7cKes4rCPY.0/PHJ82x6FufmAvzkSVLODy6', 'Изабелла В.', 'iquest238@mail.ru', '89016668883', 'f9869242-3fd0-4464-a299-56f4e2c60e42', 1, '2024-06-12'),
('maks', '$2a$10$eYem8fNT/ly60nV1ZT7cKes4rCPY.0/PHJ82x6FufmAvzkSVLODy6', 'Максим Р.', 'iquest338@mail.ru', '89231861039', 'f9869242-3fd0-4464-a299-56f4e2c60e42', 1, '2024-06-12'),
('masha', '$2a$10$CBere4wkN3qfJH/ZZYXRcOGe9XdtMuUHwLwEpt5/9qeq56kQQci56', 'Мария Р.', 'maw-rad@пmail.сщь', '89041256289', 'f9869242-3fd0-4464-a299-56f4e2c60e42', 1, '2024-06-12');


-- insert into roles (name)
-- values ('ROLE_OWNER');
--
-- insert into users (username, password, fio, email, phone_number, activate_code, active, create_time)
-- values  ('mia', '$2a$10$CBere4wkN3qfJH/ZZYXRcOGe9XdtMuUHwLwEpt5/9qeq56kQQci56', 'Мия Ригз', 'maw-rad@mail.ru', '89041256288', 'f9869242-3fd0-4464-a299-56f4e2c60e42', 1, '2024-06-12'),
-- ('hunt', '$2a$10$CBere4wkN3qfJH/ZZYXRcOGe9XdtMuUHwLwEpt5/9qeq56kQQci56', 'Иван Хант', 'iquest38@mail.ru', '89016668881', 'f9869242-3fd0-4464-a299-56f4e2c60e42', 1, '2024-06-12'),
-- ('marketolog2', '$2a$10$IfEDIgjdQ0Ox634r2gBZF.k2ORbI7h85LyS4m7S3qm9aI5AWtGcSG', 'Маркетолог Маркетолог 2.', '2.12nps30@mail.ru', '89086431065', 'f9869242-3fd0-4464-a299-56f4e2c60e42', 1, '2024-06-12'),
-- ('operator2', '$2a$10$IfEDIgjdQ0Ox634r2gBZF.k2ORbI7h85LyS4m7S3qm9aI5AWtGcSG', 'Оператор Оператор 2.', '2.12nps31@mail.ru', '89086431057', 'f9869242-3fd0-4464-a299-56f4e2c60e42', 1, '2024-06-12'),
-- ('vika', '$2a$10$KCRjn2ueePe/oCFnA7ZrVelC0AoMIgdrahJ9XIrRsQmueGUW23t7K', 'Вика Ц.', 'iquest138@mail.ru', '89016668882', 'f9869242-3fd0-4464-a299-56f4e2c60e42', 1, '2024-06-12'),
-- ('bella', '$2a$10$eYem8fNT/ly60nV1ZT7cKes4rCPY.0/PHJ82x6FufmAvzkSVLODy6', 'Изабелла В.', 'iquest238@mail.ru', '89016668883', 'f9869242-3fd0-4464-a299-56f4e2c60e42', 1, '2024-06-12'),
-- ('maks', '$2a$10$eYem8fNT/ly60nV1ZT7cKes4rCPY.0/PHJ82x6FufmAvzkSVLODy6', 'Максим Р.', 'iquest338@mail.ru', '89231861039', 'f9869242-3fd0-4464-a299-56f4e2c60e42', 1, '2024-06-12');



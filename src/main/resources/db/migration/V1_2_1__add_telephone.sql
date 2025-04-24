-- Сначала создаём таблицу telephones, от неё зависят device_tokens и leads
CREATE TABLE IF NOT EXISTS telephones (
    telephone_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    telephone_number VARCHAR(20) NOT NULL UNIQUE,
    telephone_fio VARCHAR(100),

    telephone_operator BIGINT,
    CONSTRAINT fk_telephone_operator
    FOREIGN KEY (telephone_operator)
    REFERENCES operators (operator_id)
    ON DELETE SET NULL
    ON UPDATE CASCADE,

    telephone_amount_allowed INT,
    telephone_amount_sent INT,
    telephone_block_time INT,
    telephone_timer DATETIME,

    telephone_google_login VARCHAR(100),
    telephone_google_password VARCHAR(100),
    telephone_avito_password VARCHAR(100),
    telephone_mail_login VARCHAR(100),
    telephone_mail_password VARCHAR(100),

    telephone_create_date DATE NOT NULL,
    telephone_update_status DATETIME,

    telephone_foto_instagram VARCHAR(255),
    telephone_active BOOLEAN NOT NULL DEFAULT TRUE
    ) ENGINE=InnoDB;

-- Индексы для ускорения выборок и фильтраций
CREATE INDEX idx_telephone_operator ON telephones(telephone_operator);
CREATE INDEX idx_telephone_active ON telephones(telephone_active);
CREATE INDEX idx_telephone_number ON telephones(telephone_number);
CREATE INDEX idx_telephone_create_date ON telephones(telephone_create_date);

-- Затем device_tokens, у которой внешний ключ на telephones
CREATE TABLE IF NOT EXISTS device_tokens (
    token VARCHAR(255) PRIMARY KEY,
    telephone_id BIGINT NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_device_telephone
    FOREIGN KEY (telephone_id)
    REFERENCES telephones (telephone_id)
    ON DELETE CASCADE
    ON UPDATE CASCADE
    );

-- И наконец — ALTER TABLE leads, добавляем внешний ключ
ALTER TABLE leads
    ADD COLUMN telephone_id BIGINT NULL,
ADD CONSTRAINT fk_telephone
    FOREIGN KEY (telephone_id)
    REFERENCES telephones (telephone_id)
    ON DELETE SET NULL
    ON UPDATE CASCADE;

INSERT INTO telephones (
    telephone_number,
    telephone_fio,
    telephone_operator,
    telephone_amount_allowed,
    telephone_amount_sent,
    telephone_block_time,
    telephone_timer,
    telephone_google_login,
    telephone_google_password,
    telephone_avito_password,
    telephone_mail_login,
    telephone_mail_password,
    telephone_create_date,
    telephone_update_status,
    telephone_foto_instagram,
    telephone_active
) VALUES
      (
          '+79998887766',
          'Иванов Иван Иванович',
          1,
          5,
          0,
          3600,
          '2025-04-22 10:00:00',
          'ivanov@gmail.com',
          'pass123',
          'avito123',
          'ivanov@mail.ru',
          'mailpass123',
          '2025-04-22',
          NOW(),
          'https://instagram.com/ivanov',
          TRUE
      ),
      (
          '+79991112233',
          'Петров Петр Петрович',
          2,
          1,
          0,
          1800,
          '2025-04-22 11:30:00',
          'petrov@gmail.com',
          'pass456',
          'avito456',
          'petrov@mail.ru',
          'mailpass456',
          '2025-04-22',
          NOW(),
          'https://instagram.com/petrov',
          TRUE
      ),
      (
          '+78889990000',
          'Сидоров Сидор Сидорович',
          2,
          5,
          0,
          900,
          '2025-04-22 12:00:00',
          'sidorov@gmail.com',
          'pass789',
          'avito789',
          'sidorov@mail.ru',
          'mailpass789',
          '2025-04-22',
          NOW(),
          NULL,
          TRUE
      );













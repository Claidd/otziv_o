-- Сначала создаём таблицу telephones, от неё зависят device_tokens и leads
CREATE TABLE IF NOT EXISTS telephones (
    telephone_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    telephone_number VARCHAR(20) NOT NULL UNIQUE,
    telephone_fio VARCHAR(100),
    telephone_birthday DATE NULL,

    telephone_operator BIGINT NULL,
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

-- Вносим изменения в таблицу операторов
ALTER TABLE operators
    ADD COLUMN operator_count INT NOT NULL DEFAULT 0;

-- Вносим изменения в таблицу продуктов
ALTER TABLE products
    ADD COLUMN product_photo BIT NOT NULL DEFAULT false;




-- 1. Добавим поля в reviews
ALTER TABLE reviews
    ADD COLUMN review_product BIGINT,
    ADD COLUMN review_price DECIMAL(10, 2),
    ADD COLUMN review_url VARCHAR(2048) DEFAULT NULL;

-- 2. Пронесём данные из order_details
UPDATE reviews r
    JOIN order_details od ON r.review_order_details = od.order_detail_id
    SET r.review_product = od.order_detail_product,
        r.review_price = CASE
        WHEN od.order_detail_amount > 0 THEN ROUND(od.order_detail_price / od.order_detail_amount, 2)
        ELSE NULL
END;

-- 3. Создадим индекс и внешний ключ
CREATE INDEX review_product_idx ON reviews (review_product);

ALTER TABLE reviews
    ADD CONSTRAINT fk_review_product
        FOREIGN KEY (review_product)
            REFERENCES products(product_id)
            ON DELETE SET NULL
            ON UPDATE CASCADE;


-- 4. (опционально) Удалим старые поля из order_details
-- ALTER TABLE order_details
-- DROP FOREIGN KEY order_detail_product;

-- ALTER TABLE order_details
-- DROP COLUMN order_detail_product,
-- DROP COLUMN order_detail_price;

     -- Теперь применяем изменения
-- ALTER TABLE reviews
--     MODIFY COLUMN review_product BIGINT NOT NULL,
--     MODIFY COLUMN review_price DECIMAL(10, 2) NOT NULL;


INSERT INTO telephones (
    telephone_number,
    telephone_fio,
    telephone_birthday,
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
          '+89996416889',
          'Ульяна Егорова',
          '1989-04-01',
          1,
          5,
          0,
          3,
          NOW(),
          'ocompanysales1',
          'O-companY1',
          'AvitosalesYota1',
          'ulya.ygorova.89@mail.ru',
          'Через ВК',
          CURDATE(),
          NOW(),
          'https://instagram.com/ulyana_nikola',
          TRUE
      ),
      (
          '+89964354051',
          'Елена Петрова',
          '1995-04-19',
          1,
          5,
          0,
          3,
          NOW(),
          'ocompanysales2',
          'O-companY2',
          'AvitosalesYota2',
          'elena.petrovka95@mail.ru',
          'Через ВК',
          CURDATE(),
          NOW(),
          'https://instagram.com/lenalenowa',
          TRUE
      ),
      (
          '+89964353243',
          'Ольга Кривощекова',
          '1999-04-23',
          1,
          5,
          0,
          3,
          NOW(),
          'ocompanysales3',
          'O-companY3',
          'AvitosalesYota3',
          'olya.krivoshekova99@mail.ru',
          'Через ВК',
          CURDATE(),
          NOW(),
          'https://instagram.com/olga_vekvert',
          TRUE
      ),
      (
          '+89964355489',
          'Екатерина Олененко',
          '2000-04-25',
          1,
          5,
          0,
          3,
          NOW(),
          'ocompanysales4',
          'O-companY4',
          'AvitosalesYota4',
          'yolenenko2000@mail.ru',
          'Через ВК',
          CURDATE(),
          NOW(),
          'https://instagram.com/eto_prosto_katya',
          TRUE
      ),
      (
          '+89964353837',
          'Олеся Абромосова',
          '1998-05-13',
          1,
          5,
          0,
          3,
          NOW(),
          'ocompanysales5',
          'O-companY5',
          'AvitosalesYota5',
          'oabromosova@mail.ru',
          'Через ВК',
          CURDATE(),
          NOW(),
          'https://instagram.com/olesya__chernyavskaya',
          TRUE
      ),
      (
          '+89964354143',
          'Анна Иванова',
          '1999-07-20',
          1,
          5,
          0,
          3,
          NOW(),
          'ocompanysales6',
          'O-companY6',
          'AvitosalesYota5',
          'anna98ivanka@mail.ru',
          'Через ВК',
          CURDATE(),
          NOW(),
          'https://instagram.com/anna_khatskevich86',
          TRUE
      )
;













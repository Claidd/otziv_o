-- При удалении категории или субкатегории не должны удаляться все связи по цепочке
ALTER TABLE companies DROP FOREIGN KEY company_category;
ALTER TABLE companies DROP FOREIGN KEY company_subcategory;


-- Обновляем внешние ключи с ON DELETE SET NULL
ALTER TABLE companies
    ADD CONSTRAINT company_category
        FOREIGN KEY (company_category)
            REFERENCES categorys (category_id)
            ON DELETE SET NULL
            ON UPDATE CASCADE;

ALTER TABLE companies
    ADD CONSTRAINT company_subcategory
        FOREIGN KEY (company_subcategory)
            REFERENCES subcategoryes (subcategory_id)
            ON DELETE SET NULL
            ON UPDATE CASCADE;

-- Создание индексов для улучшения производительности
CREATE INDEX idx_company_phone ON companies (company_phone);
CREATE INDEX idx_company_email ON companies (company_email);
CREATE INDEX idx_company_city ON companies (company_city);






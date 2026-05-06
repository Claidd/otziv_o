INSERT INTO text_promo (promo_text)
SELECT 'Здравствуйте, Текст отзывов для новых отзывов на следующий месяц готов.lineSepЕсли замечаний нет, нажмите кнопку «РАЗРЕШИТЬ ПУБЛИКАЦИЮ».'
WHERE NOT EXISTS (
    SELECT 1
    FROM text_promo
    WHERE promo_text LIKE '%Текст отзывов для новых отзывов на следующий месяц готов%'
);

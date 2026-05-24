UPDATE payment_links
SET description = 'Репутационное сопровождение компании в сети Интернет'
WHERE description IS NULL
   OR description = ''
   OR description = 'Оплата рекламных и информационных услуг'
   OR description LIKE 'Оплата услуг для %';

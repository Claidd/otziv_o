UPDATE reviews r
JOIN bots b ON b.bot_id = r.review_bot
SET r.review_vigul = 0
WHERE COALESCE(r.review_publish, 0) = 0
  AND COALESCE(r.review_vigul, 0) = 1
  AND COALESCE(b.bot_counter, 0) < 3
  AND LOWER(TRIM(b.bot_fio)) IN (
      'впишите имя фамилию',
      'впиши имя фамилию',
      'впишите фамилию имя',
      'нет доступных аккаунтов'
  );

UPDATE archive_reviews ar
JOIN bots b ON b.bot_id = ar.review_bot
SET ar.review_vigul = 0
WHERE COALESCE(ar.review_publish, 0) = 0
  AND COALESCE(ar.review_vigul, 0) = 1
  AND COALESCE(b.bot_counter, 0) < 3
  AND LOWER(TRIM(b.bot_fio)) IN (
      'впишите имя фамилию',
      'впиши имя фамилию',
      'впишите фамилию имя',
      'нет доступных аккаунтов'
  );

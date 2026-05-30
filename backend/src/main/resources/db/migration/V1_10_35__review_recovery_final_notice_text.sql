INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    (
        'client.messages.review-recovery-notice-text',
        '{companyAndFilial}\n\nВсе отзывы по заказу №{orderId} восстановлены. Продолжаем работу.',
        CURRENT_TIMESTAMP(6)
    )
ON DUPLICATE KEY UPDATE
    setting_value = CASE
        WHEN setting_value IS NULL
            OR TRIM(setting_value) = ''
            OR setting_value = '{companyAndFilial}\n\nОтзыв восстановлен. Продолжаем работу по заказу №{orderId}.'
        THEN VALUES(setting_value)
        ELSE setting_value
    END,
    updated_at = CASE
        WHEN setting_value IS NULL
            OR TRIM(setting_value) = ''
            OR setting_value = '{companyAndFilial}\n\nОтзыв восстановлен. Продолжаем работу по заказу №{orderId}.'
        THEN CURRENT_TIMESTAMP(6)
        ELSE updated_at
    END;

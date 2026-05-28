INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    (
        'client.messages.payment-link-copy-text',
        CONCAT(
            '{companyAndFilial}', CHAR(10), CHAR(10),
            'Здравствуйте, ваш заказ выполнен. К оплате: {sum} руб.', CHAR(10), CHAR(10),
            '{paymentInstruction}', CHAR(10), CHAR(10),
            '{paymentAfterword}'
        ),
        CURRENT_TIMESTAMP(6)
    )
ON DUPLICATE KEY UPDATE setting_value = setting_value;

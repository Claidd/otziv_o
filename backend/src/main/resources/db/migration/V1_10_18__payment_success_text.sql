INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    (
        'client.messages.payment-success-text',
        CONCAT(
            'Оплата прошла успешно.', CHAR(10), CHAR(10),
            'Новый заказ принят в работу.', CHAR(10),
            '{orderLine}{companyLine}',
            'Сумма: {sum}', CHAR(10),
            'Страница оплаты: {paymentPage}', CHAR(10), CHAR(10),
            '{receiptText}'
        ),
        CURRENT_TIMESTAMP(6)
    )
ON DUPLICATE KEY UPDATE setting_value = setting_value;

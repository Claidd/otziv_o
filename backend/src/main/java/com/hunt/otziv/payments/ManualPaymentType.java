package com.hunt.otziv.payments;

public enum ManualPaymentType {
    MOBILE_BANK,
    EXTERNAL_LINK;

    public static final String DEFAULT_MANUAL_RECIPIENT_NAME = "Сивохин И.И.";
    public static final String DEFAULT_EXTERNAL_PAYMENT_URL = "https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR";
    public static final String DEFAULT_EXTERNAL_PAYMENT_BUTTON_LABEL = "Оплатить через Альфа-Банк";
}

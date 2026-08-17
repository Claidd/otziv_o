package com.hunt.otziv.client_messages.dto;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentTransferNumber;
import java.util.Objects;
import java.util.Optional;

/**
 * A validated Telegram copy button for a frozen contractor-payment transfer
 * destination. Instances can only be created from a valid phone/card value so
 * arbitrary message text cannot accidentally be exposed through the button.
 */
public final class TelegramTransferCopyButton {

    private static final String COPY_CARD = "Скопировать номер карты";
    private static final String COPY_PHONE = "Скопировать номер телефона";

    private final String text;
    private final String copyText;

    private TelegramTransferCopyButton(String text, String copyText) {
        this.text = text;
        this.copyText = copyText;
    }

    public static Optional<TelegramTransferCopyButton> fromFrozenTransferNumber(String rawValue) {
        String normalized = ContractorPaymentTransferNumber.normalize(rawValue);
        if (!ContractorPaymentTransferNumber.isValid(normalized)) {
            return Optional.empty();
        }
        boolean card = normalized.matches("[0-9]{16,19}");
        return Optional.of(new TelegramTransferCopyButton(card ? COPY_CARD : COPY_PHONE, normalized));
    }

    public String text() {
        return text;
    }

    public String copyText() {
        return copyText;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof TelegramTransferCopyButton other)) {
            return false;
        }
        return Objects.equals(text, other.text) && Objects.equals(copyText, other.copyText);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, copyText);
    }
}

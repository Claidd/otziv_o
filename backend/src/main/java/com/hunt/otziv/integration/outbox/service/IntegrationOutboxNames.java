package com.hunt.otziv.integration.outbox.service;

import com.hunt.otziv.integration.outbox.config.IntegrationOutboxProperties;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEvent;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxEventDraft;
import com.hunt.otziv.integration.outbox.dto.IntegrationOutboxStatusResponse;
import com.hunt.otziv.integration.outbox.repository.IntegrationOutboxRepository;
import java.util.regex.Pattern;

final class IntegrationOutboxNames {

    private static final Pattern TYPE = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9_.:-]*"
    );

    private IntegrationOutboxNames() {
    }

    static String requiredType(String value, int maximumLength, String label) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || !TYPE.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value;
    }

    static String requiredIdentifier(String value, int maximumLength, String label) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value;
    }

    static String safeForLog(String value, int maximumLength) {
        return value != null
                && value.length() <= maximumLength
                && TYPE.matcher(value).matches()
                ? value
                : "invalid";
    }
}

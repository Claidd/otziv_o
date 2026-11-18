package com.hunt.otziv.t_telegrambot.service;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.ResponseParameters;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class TelegramDeliveryFailureTest {
    @Test void providerFailureRetainsOnlySafeDiagnosticLabels() {
        var failure = mock(TelegramApiRequestException.class);
        when(failure.getErrorCode()).thenReturn(403);
        when(failure.getApiResponse()).thenReturn("Forbidden: bot was kicked; secret-token; client message");
        var diagnostic = TelegramDeliveryFailure.describe(failure);
        assertEquals(403, diagnostic.apiCode());
        assertEquals("bot_removed", diagnostic.reason());
        assertFalse(diagnostic.toString().contains("secret-token"));
        assertFalse(diagnostic.toString().contains("client message"));
        when(failure.getApiResponse()).thenReturn("Forbidden: bot was blocked by the user");
        assertEquals("bot_blocked", TelegramDeliveryFailure.describe(failure).reason());
        when(failure.getErrorCode()).thenReturn(400);
        when(failure.getApiResponse()).thenReturn("Bad Request: chat not found");
        assertEquals("chat_not_found", TelegramDeliveryFailure.describe(failure).reason());
        when(failure.getParameters()).thenReturn(new ResponseParameters(-100123L, null));
        assertEquals("chat_migrated", TelegramDeliveryFailure.describe(failure).reason());
    }

    @Test void rateLimitServerAndUnreadableResponsesStayDistinct() {
        var failure = mock(TelegramApiRequestException.class);
        when(failure.getErrorCode()).thenReturn(429);
        assertEquals("rate_limited", TelegramDeliveryFailure.describe(failure).reason());
        when(failure.getErrorCode()).thenReturn(502);
        assertEquals("provider_error", TelegramDeliveryFailure.describe(failure).reason());
        when(failure.getErrorCode()).thenReturn(null);
        assertEquals(new TelegramDeliveryFailure(null, "request_failed"), TelegramDeliveryFailure.describe(failure));
        assertEquals(new TelegramDeliveryFailure(null, "transport_or_response_failure"),
                TelegramDeliveryFailure.describe(new TelegramApiException("secret response")));
    }
}

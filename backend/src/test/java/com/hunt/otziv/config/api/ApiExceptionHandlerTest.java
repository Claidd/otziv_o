package com.hunt.otziv.config.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void handleDataIntegrityExplainsTooLongLeadImportField() {
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> response = handler.handleDataIntegrity(
                new DataIntegrityViolationException(
                        "could not execute statement",
                        new RuntimeException("Data truncation: Data too long for column 'industries' at row 1")
                )
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(
                "Поле \"Отрасли\" слишком длинное для текущей схемы базы. Примените последние миграции и повторите импорт.",
                response.getBody().message()
        );
    }
}

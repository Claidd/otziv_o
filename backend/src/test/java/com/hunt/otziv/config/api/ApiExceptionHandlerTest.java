package com.hunt.otziv.config.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApiExceptionHandlerTest {
    @Test
    void handleCodedConflictKeepsStableCodeWithoutChangingMessage() {
        ResponseStatusException failure = new CodedResponseStatusException(
                HttpStatus.CONFLICT,
                "PAYMENT_ROUTE_STALE",
                "Маршрут изменился"
        );

        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> response = handler.handleResponseStatus(failure);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Маршрут изменился", response.getBody().message());
        assertEquals("PAYMENT_ROUTE_STALE", response.getBody().code());
    }


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

    @Test
    void handleDataIntegrityExplainsLinkedBusinessData() {
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> response = handler.handleDataIntegrity(
                new DataIntegrityViolationException(
                        "could not execute statement",
                        new RuntimeException("Cannot delete or update a parent row: a foreign key constraint fails")
                )
        );

        assertNotNull(response.getBody());
        assertEquals(
                "Изменение не сохранено: запись связана с рабочими данными. История сохранена, ничего не удалено.",
                response.getBody().message()
        );
    }

    @Test
    void handleOptimisticLockingReturnsRetryableConflict() {
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> response = handler.handleOptimisticLocking(
                new OptimisticLockingFailureException("stale row")
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(
                "Данные уже изменились в другой сессии. Обновите страницу и повторите действие.",
                response.getBody().message()
        );
    }
}

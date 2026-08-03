package com.hunt.otziv.config.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestControllerAdvice(annotations = RestController.class)
public class ApiExceptionHandler {
    private static final Pattern DATA_TOO_LONG_COLUMN = Pattern.compile("Data too long for column '([^']+)'");
    private static final Pattern DUPLICATE_ENTRY = Pattern.compile("Duplicate entry '([^']*)' for key '([^']*)'");
    private static final Pattern FOREIGN_KEY_CONSTRAINT = Pattern.compile("foreign key constraint fails", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("company_name", "Наименование"),
            Map.entry("phones", "Телефоны"),
            Map.entry("mobile_phones", "Мобильные"),
            Map.entry("whatsapp_phones", "WhatsApp"),
            Map.entry("emails", "Емейлы"),
            Map.entry("websites", "Сайты"),
            Map.entry("vk_url", "VK"),
            Map.entry("telegram_url", "TG"),
            Map.entry("industries", "Отрасли"),
            Map.entry("company_type", "Тип"),
            Map.entry("region", "Регион"),
            Map.entry("address", "Адрес"),
            Map.entry("comments_lead", "Комментарий")
    );

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        String message = ex.getReason() == null || ex.getReason().isBlank()
                ? "Ошибка запроса"
                : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode()).body(new ApiErrorResponse(message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage() == null ? "Проверьте заполнение формы" : error.getDefaultMessage())
                .orElse("Проверьте заполнение формы");
        return ResponseEntity.badRequest().body(new ApiErrorResponse(message));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiErrorResponse("Сервер не поддерживает этот способ запроса. Обновите страницу и попробуйте снова."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse(dataIntegrityMessage(ex)));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLocking(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(
                "Данные уже изменились в другой сессии. Обновите страницу и повторите действие."
        ));
    }

    private String dataIntegrityMessage(DataIntegrityViolationException ex) {
        String message = mostSpecificMessage(ex);
        Matcher matcher = DATA_TOO_LONG_COLUMN.matcher(message);
        if (matcher.find()) {
            String column = matcher.group(1);
            String label = FIELD_LABELS.getOrDefault(column, column);
            return "Поле \"" + label + "\" слишком длинное для текущей схемы базы. Примените последние миграции и повторите импорт.";
        }


        Matcher duplicateMatcher = DUPLICATE_ENTRY.matcher(message);
        if (duplicateMatcher.find()) {
            return "Изменение не сохранено: значение \"" + duplicateMatcher.group(1) + "\" уже используется.";
        }

        if (FOREIGN_KEY_CONSTRAINT.matcher(message).find()) {
            return "Изменение не сохранено: запись связана с рабочими данными. История сохранена, ничего не удалено.";
        }

        return "Изменение не сохранено из-за ограничения базы данных. Обновите страницу и повторите действие.";
    }

    private String mostSpecificMessage(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause != null && cause.getMessage() != null) {
            return cause.getMessage();
        }
        return ex.getMessage() == null ? "" : ex.getMessage();
    }

    public record ApiErrorResponse(String message) {
    }
}

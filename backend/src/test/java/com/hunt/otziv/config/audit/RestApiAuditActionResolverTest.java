package com.hunt.otziv.config.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class RestApiAuditActionResolverTest {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^/}]+)}");

    private final RestApiAuditActionResolver resolver = new RestApiAuditActionResolver();

    @Test
    void resolvesStatusFromRequestAttribute() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/manager/orders/21022/status"
        );
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/manager/orders/{orderId}/status"
        );
        request.setAttribute("status", "Архив");

        String action = resolver.resolve(request, null);

        assertEquals("смена статуса заказа на \"Архив\"", action);
    }

    @Test
    void resolvesMetricSnapshotSeenFromRequestBody() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/metric-snapshots/seen"
        );
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/metric-snapshots/seen"
        );
        request.setAttribute(
                RestApiAuditActionResolver.REQUEST_BODY_ATTRIBUTE,
                Map.of(
                        "page", "worker",
                        "section", "publish",
                        "value", 12
                )
        );

        String action = resolver.resolve(request, null);

        assertEquals("отметка счетчика \"publish\" на странице \"worker\" как просмотренного, значение 12", action);
    }

    @Test
    void resolvesMetricSnapshotSeenFromRequestAttributes() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/metric-snapshots/seen"
        );
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/metric-snapshots/seen"
        );
        request.setAttribute("page", "worker");
        request.setAttribute("section", "correct");
        request.setAttribute("value", 3);

        String action = resolver.resolve(request, null);

        assertEquals("отметка счетчика \"correct\" на странице \"worker\" как просмотренного, значение 3", action);
    }

    @Test
    void resolvesPersonalReminderList() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/personal-reminders"
        );
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/personal-reminders"
        );

        String action = resolver.resolve(request, null);

        assertEquals("загрузка личных заметок и напоминаний", action);
    }

    @Test
    void resolvesPersonalReminderCreateFromRequestBody() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/personal-reminders"
        );
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/personal-reminders"
        );
        request.setAttribute(
                RestApiAuditActionResolver.REQUEST_BODY_ATTRIBUTE,
                Map.of("title", "Позвонить клиенту")
        );

        String action = resolver.resolve(request, null);

        assertEquals("создание личной заметки или напоминания \"Позвонить клиенту\"", action);
    }

    @ParameterizedTest
    @MethodSource("previouslyMissingRoutes")
    void resolvesPreviouslyMissingRoutes(String method, String pattern, String expected) {
        String action = resolve(method, pattern);

        assertEquals(expected, action);
    }

    private String resolve(String method, String pattern) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uriFromPattern(pattern));
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, pathVariables(pattern));
        request.setParameter("month", "2026-05");
        request.setParameter("username", "alex");
        request.setParameter("date", "2026-05-10");
        request.setParameter("userId", "6");
        request.setAttribute(
                RestApiAuditActionResolver.REQUEST_BODY_ATTRIBUTE,
                Map.of(
                        "title", "Позвонить клиенту",
                        "login", "bot-login",
                        "number", "+79000000000"
                )
        );

        return resolver.resolve(request, null);
    }

    private static String uriFromPattern(String pattern) {
        Matcher matcher = PATH_VARIABLE.matcher(pattern);
        return matcher.replaceAll("1");
    }

    private static Map<String, String> pathVariables(String pattern) {
        Map<String, String> variables = new HashMap<>();
        Matcher matcher = PATH_VARIABLE.matcher(pattern);
        while (matcher.find()) {
            String name = matcher.group(1);
            variables.put(name, sampleValue(name));
        }
        return variables;
    }

    private static String sampleValue(String name) {
        return switch (name) {
            case "batchId" -> "4";
            case "botId" -> "11";
            case "buttonKey" -> "pay";
            case "companyId" -> "3";
            case "filialId" -> "9";
            case "managerId" -> "22";
            case "orderDetailId" -> "11111111-1111-1111-1111-111111111111";
            case "orderId" -> "17";
            case "reminderId" -> "7";
            case "reviewId" -> "25";
            case "section" -> "managerOrders";
            case "taskId" -> "8";
            case "token" -> "device-token";
            case "userId" -> "6";
            default -> "15";
        };
    }

    private static Stream<Arguments> previouslyMissingRoutes() {
        return Stream.of(
                arguments("GET", "/api/admin/analytics/aggregates/compare-admin-month", "сверка аналитики за месяц 2026-05"),
                arguments("GET", "/api/admin/analytics/aggregates/compare-cabinet-analyse", "сверка аналитики кабинета пользователя \"alex\""),
                arguments("GET", "/api/admin/analytics/aggregates/compare-score", "сверка рейтинга пользователей за дату 2026-05-10"),
                arguments("GET", "/api/admin/analytics/aggregates/compare-team", "сверка команды пользователя \"alex\""),
                arguments("GET", "/api/admin/analytics/aggregates/compare-user-stats", "сверка статистики пользователя 6"),
                arguments("POST", "/api/admin/analytics/aggregates/rebuild-month", "пересчет аналитики за месяц 2026-05"),
                arguments("GET", "/api/admin/analytics/aggregates/source-range", "проверка доступного периода данных для аналитики"),
                arguments("GET", "/api/admin/archive/batches/{batchId}", "загрузка запуска архиватора 4"),
                arguments("GET", "/api/admin/archive/orders/candidates", "предпросмотр заказов для архива"),
                arguments("GET", "/api/admin/archive/orders/lock", "проверка блокировки архиватора заказов"),
                arguments("GET", "/api/admin/bots", "загрузка аккаунтов"),
                arguments("GET", "/api/admin/bots/{id}", "загрузка аккаунта 15"),
                arguments("POST", "/api/admin/bots/import", "импорт аккаунтов из файла"),
                arguments("POST", "/api/admin/bots", "создание аккаунта \"bot-login\""),
                arguments("PUT", "/api/admin/bots/{id}", "редактирование аккаунта 15"),
                arguments("DELETE", "/api/admin/bots/{id}", "удаление аккаунта 15"),
                arguments("GET", "/api/admin/manager-texts", "загрузка шаблонов сообщений менеджеров"),
                arguments("PUT", "/api/admin/manager-texts/{managerId}", "редактирование шаблонов сообщений менеджера 22"),
                arguments("GET", "/api/admin/phones", "загрузка телефонов операторов"),
                arguments("POST", "/api/admin/phones", "создание телефона оператора \"+79000000000\""),
                arguments("PUT", "/api/admin/phones/{id}", "редактирование телефона оператора 15"),
                arguments("DELETE", "/api/admin/phones/{id}", "удаление телефона оператора 15"),
                arguments("DELETE", "/api/admin/phones/{id}/device-tokens/{token}", "удаление токена устройства телефона 15"),
                arguments("GET", "/api/admin/promo-texts", "загрузка промо-текстов"),
                arguments("GET", "/api/admin/promo-texts/management", "загрузка управления промо-текстами"),
                arguments("POST", "/api/admin/promo-texts", "создание промо-текста"),
                arguments("PUT", "/api/admin/promo-texts/{id}", "редактирование промо-текста 15"),
                arguments("PUT", "/api/admin/promo-text-assignments", "назначение промо-текста менеджеру"),
                arguments("DELETE", "/api/admin/promo-text-assignments/{managerId}/{section}/{buttonKey}", "сброс промо-текста менеджера 22"),
                arguments("POST", "/api/admin/users/{id}/photo", "обновление фото пользователя 15"),
                arguments("PUT", "/api/admin/users/{id}/password", "смена пароля пользователя 15"),
                arguments("POST", "/api/leads/file-import", "импорт лидов из файла"),
                arguments("PUT", "/api/manager/companies/{companyId}/filials/{filialId}", "редактирование филиала 9 компании 3"),
                arguments("PUT", "/api/manager/companies/{companyId}/note", "редактирование заметки компании 3"),
                arguments("POST", "/api/manager/orders/{orderId}/reviews/{reviewId}/new-account", "назначение нового аккаунта отзыву 25"),
                arguments("GET", "/api/manager/overdue-orders", "проверка заказов, которые давно не менялись"),
                arguments("PUT", "/api/review-check/{orderDetailId}/reviews/{reviewId}/answer", "редактирование ответа отзыва 25 при проверке"),
                arguments("PUT", "/api/review-check/{orderDetailId}/reviews/{reviewId}/text", "редактирование текста отзыва 25 при проверке"),
                arguments("GET", "/api/worker/overdue-orders", "проверка заказов специалиста, которые давно не менялись"),
                arguments("POST", "/api/worker/reviews/{reviewId}/copy-click", "нажатие кнопки копирования данных аккаунта в отзыве 25"),
                arguments("GET", "/logs", "просмотр логов системы"),
                arguments("GET", "/logs/tail", "загрузка новых строк лога"),
                arguments("POST", "/logs/clear", "очистка текущего файла лога"),
                arguments("POST", "/logs/clear/all", "очистка основных файлов логов"),
                arguments("GET", "/logs/ui", "открытие страницы живых логов")
        );
    }
}

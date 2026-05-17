package com.hunt.otziv.config.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RestApiAuditActionResolver {

    static final String REQUEST_BODY_ATTRIBUTE = RestApiAuditActionResolver.class.getName() + ".requestBody";

    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{([a-zA-Z0-9_]+)}");
    private static final String ALL = "Все";
    private static final Map<ActionKey, String> ACTIONS = actions();

    public String resolve(HttpServletRequest request, HandlerMethod handlerMethod) {
        String method = request.getMethod();
        String pattern = bestPattern(request);

        Optional<String> specialAction = resolveSpecialAction(method, pattern, request);
        if (specialAction.isPresent()) {
            return specialAction.get();
        }

        String action = ACTIONS.get(new ActionKey(method, pattern));
        if (action != null) {
            return render(action, request);
        }

        return fallbackAction(method, pattern, handlerMethod);
    }

    private Optional<String> resolveSpecialAction(String method, String pattern, HttpServletRequest request) {
        if ("GET".equals(method) && "/api/manager/board".equals(pattern)) {
            return Optional.of(managerBoardAction(request));
        }

        if ("GET".equals(method) && "/api/worker/board".equals(pattern)) {
            return Optional.of(workerBoardAction(request));
        }

        if ("GET".equals(method) && "/api/leads/board".equals(pattern)) {
            return Optional.of(leadsBoardAction(request));
        }

        if ("GET".equals(method) && "/api/operator/board".equals(pattern)) {
            return Optional.of("загрузка доски оператора");
        }

        return Optional.empty();
    }

    private String managerBoardAction(HttpServletRequest request) {
        String section = requestParam(request, "section").orElse("companies");
        String status = requestParam(request, "status").orElse(ALL);

        if ("orders".equalsIgnoreCase(section)) {
            return ALL.equals(status)
                    ? "загрузка всех заказов"
                    : "загрузка заказов со статусом \"" + status + "\"";
        }

        return ALL.equals(status)
                ? "загрузка всех компаний"
                : "загрузка компаний со статусом \"" + status + "\"";
    }

    private String workerBoardAction(HttpServletRequest request) {
        String section = requestParam(request, "section").orElse("new").toLowerCase(Locale.ROOT);
        return switch (section) {
            case "correct" -> "загрузка заказов специалиста в коррекции";
            case "nagul" -> "загрузка отзывов на выгул";
            case "publish" -> "загрузка отзывов на публикацию";
            case "bad" -> "загрузка плохих отзывов";
            case "all" -> "загрузка всех заказов специалиста";
            default -> "загрузка новых заказов специалиста";
        };
    }

    private String leadsBoardAction(HttpServletRequest request) {
        String section = requestParam(request, "section").orElse("");
        if (section.isBlank()) {
            return "загрузка всей доски лидов";
        }

        return switch (section) {
            case "toWork" -> "загрузка лидов в работу";
            case "new", "newLeads" -> "загрузка новых лидов";
            case "send" -> "загрузка лидов на отправку";
            case "archive" -> "загрузка архива лидов";
            case "inWork" -> "загрузка лидов в работе";
            case "all" -> "загрузка всех лидов";
            default -> "загрузка доски лидов";
        };
    }

    private String render(String template, HttpServletRequest request) {
        Matcher matcher = TEMPLATE_VARIABLE.matcher(template);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String name = matcher.group(1);
            String value = value(name, request).orElse("не указано");
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }

        matcher.appendTail(result);
        return result.toString();
    }

    private Optional<String> value(String name, HttpServletRequest request) {
        return pathVariable(name, request)
                .or(() -> requestParam(request, name))
                .or(() -> requestAttribute(name, request))
                .or(() -> bodyValue(name, request));
    }

    @SuppressWarnings("unchecked")
    private Optional<String> pathVariable(String name, HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(variables instanceof Map<?, ?> map)) {
            return Optional.empty();
        }

        Object value = map.get(name);
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }

    private Optional<String> requestParam(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    private Optional<String> requestAttribute(String name, HttpServletRequest request) {
        Object value = request.getAttribute(name);
        if (value == null) {
            return Optional.empty();
        }

        String text = value.toString();
        return text.isBlank() ? Optional.empty() : Optional.of(text.trim());
    }

    private Optional<String> bodyValue(String name, HttpServletRequest request) {
        Object body = request.getAttribute(REQUEST_BODY_ATTRIBUTE);
        if (body == null) {
            return Optional.empty();
        }

        if (body instanceof Map<?, ?> map) {
            Object value = map.get(name);
            return value == null ? Optional.empty() : Optional.of(value.toString());
        }

        return invokeAccessor(body, name)
                .or(() -> readField(body, name))
                .map(Object::toString)
                .filter(value -> !value.isBlank());
    }

    private Optional<Object> invokeAccessor(Object target, String name) {
        for (String methodName : accessorNames(name)) {
            try {
                Method method = target.getClass().getMethod(methodName);
                return Optional.ofNullable(method.invoke(target));
            } catch (ReflectiveOperationException ignored) {
                // Try the next accessor form.
            }
        }

        return Optional.empty();
    }

    private Optional<Object> readField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return Optional.ofNullable(field.get(target));
            } catch (ReflectiveOperationException ignored) {
                type = type.getSuperclass();
            }
        }

        return Optional.empty();
    }

    private String[] accessorNames(String name) {
        String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return new String[]{name, "get" + capitalized, "is" + capitalized};
    }

    private String bestPattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? request.getRequestURI() : pattern.toString();
    }

    private String fallbackAction(String method, String pattern, HandlerMethod handlerMethod) {
        return "REST API запрос " + method + " " + pattern
                + " (" + handlerMethod.getBeanType().getSimpleName()
                + "#" + handlerMethod.getMethod().getName() + ")";
    }

    private static Map<ActionKey, String> actions() {
        Map<ActionKey, String> actions = new HashMap<>();

        put(actions, "GET", "/api/cabinet/profile", "загрузка профиля кабинета");
        put(actions, "GET", "/api/cabinet/user-info", "загрузка информации пользователя {userId}");
        put(actions, "GET", "/api/cabinet/team", "загрузка команды");
        put(actions, "GET", "/api/cabinet/score", "загрузка рейтинга");
        put(actions, "GET", "/api/cabinet/analyse", "загрузка аналитики");
        put(actions, "POST", "/api/metric-snapshots/seen", "отметка счетчика \"{section}\" на странице \"{page}\" как просмотренного, значение {value}");
        put(actions, "GET", "/api/personal-reminders", "загрузка личных заметок и напоминаний");
        put(actions, "POST", "/api/personal-reminders", "создание личной заметки или напоминания \"{title}\"");
        put(actions, "PUT", "/api/personal-reminders/{reminderId}", "редактирование личной заметки или напоминания {reminderId}");
        put(actions, "POST", "/api/personal-reminders/{reminderId}/complete", "отметка личной заметки или напоминания {reminderId} выполненным");
        put(actions, "DELETE", "/api/personal-reminders/{reminderId}", "удаление личной заметки или напоминания {reminderId}");

        put(actions, "GET", "/api/admin/categories", "загрузка категорий");
        put(actions, "POST", "/api/admin/categories", "создание категории \"{title}\"");
        put(actions, "PUT", "/api/admin/categories/{id}", "редактирование категории {id}");
        put(actions, "DELETE", "/api/admin/categories/{id}", "удаление категории {id}");
        put(actions, "GET", "/api/admin/subcategories", "загрузка подкатегорий");
        put(actions, "POST", "/api/admin/subcategories", "создание подкатегории \"{title}\"");
        put(actions, "PUT", "/api/admin/subcategories/{id}", "редактирование подкатегории {id}");
        put(actions, "DELETE", "/api/admin/subcategories/{id}", "удаление подкатегории {id}");
        put(actions, "GET", "/api/admin/cities", "загрузка городов");
        put(actions, "POST", "/api/admin/cities", "создание города \"{title}\"");
        put(actions, "PUT", "/api/admin/cities/{id}", "редактирование города {id}");
        put(actions, "DELETE", "/api/admin/cities/{id}", "удаление города {id}");
        put(actions, "GET", "/api/admin/products", "загрузка продуктов");
        put(actions, "POST", "/api/admin/products", "создание продукта \"{title}\"");
        put(actions, "PUT", "/api/admin/products/{id}", "редактирование продукта {id}");
        put(actions, "DELETE", "/api/admin/products/{id}", "удаление продукта {id}");
        put(actions, "GET", "/api/admin/cities/board", "загрузка статистики городов");
        put(actions, "GET", "/api/admin/cities/export-all", "экспорт статистики городов");
        put(actions, "GET", "/api/admin/users", "загрузка пользователей");
        put(actions, "GET", "/api/admin/users/assignment-options", "загрузка вариантов назначений пользователей");
        put(actions, "POST", "/api/admin/users", "создание пользователя \"{username}\"");
        put(actions, "PUT", "/api/admin/users/{id}", "редактирование пользователя {id}");
        put(actions, "DELETE", "/api/admin/users/{id}", "удаление пользователя {id}");
        put(actions, "GET", "/api/admin/users/{id}/assignments", "загрузка назначений пользователя {id}");
        put(actions, "PUT", "/api/admin/users/{id}/assignments", "редактирование назначений пользователя {id}");
        put(actions, "POST", "/api/admin/users/{id}/photo", "обновление фото пользователя {id}");
        put(actions, "PUT", "/api/admin/users/{id}/password", "смена пароля пользователя {id}");
        put(actions, "GET", "/api/admin/bots", "загрузка аккаунтов");
        put(actions, "GET", "/api/admin/bots/{id}", "загрузка аккаунта {id}");
        put(actions, "POST", "/api/admin/bots/import", "импорт аккаунтов из файла");
        put(actions, "POST", "/api/admin/bots", "создание аккаунта \"{login}\"");
        put(actions, "PUT", "/api/admin/bots/{id}", "редактирование аккаунта {id}");
        put(actions, "DELETE", "/api/admin/bots/{id}", "удаление аккаунта {id}");
        put(actions, "GET", "/api/admin/promo-texts", "загрузка промо-текстов");
        put(actions, "GET", "/api/admin/promo-texts/management", "загрузка управления промо-текстами");
        put(actions, "POST", "/api/admin/promo-texts", "создание промо-текста");
        put(actions, "PUT", "/api/admin/promo-texts/{id}", "редактирование промо-текста {id}");
        put(actions, "PUT", "/api/admin/promo-text-assignments", "назначение промо-текста менеджеру");
        put(actions, "DELETE", "/api/admin/promo-text-assignments/{managerId}/{section}/{buttonKey}", "сброс промо-текста менеджера {managerId}");
        put(actions, "GET", "/api/admin/manager-texts", "загрузка шаблонов сообщений менеджеров");
        put(actions, "PUT", "/api/admin/manager-texts/{managerId}", "редактирование шаблонов сообщений менеджера {managerId}");
        put(actions, "GET", "/api/admin/phones", "загрузка телефонов операторов");
        put(actions, "POST", "/api/admin/phones", "создание телефона оператора \"{number}\"");
        put(actions, "PUT", "/api/admin/phones/{id}", "редактирование телефона оператора {id}");
        put(actions, "DELETE", "/api/admin/phones/{id}", "удаление телефона оператора {id}");
        put(actions, "GET", "/api/admin/settings/nagul", "загрузка настройки времени между выгулами");
        put(actions, "PUT", "/api/admin/settings/nagul", "редактирование настройки времени между выгулами");
        put(actions, "POST", "/api/admin/analytics/aggregates/rebuild-month", "пересчет аналитики за месяц {month}");
        put(actions, "GET", "/api/admin/analytics/aggregates/source-range", "проверка доступного периода данных для аналитики");
        put(actions, "GET", "/api/admin/analytics/aggregates/compare-admin-month", "сверка аналитики за месяц {month}");
        put(actions, "GET", "/api/admin/analytics/aggregates/compare-cabinet-analyse", "сверка аналитики кабинета пользователя \"{username}\"");
        put(actions, "GET", "/api/admin/analytics/aggregates/compare-score", "сверка рейтинга пользователей за дату {date}");
        put(actions, "GET", "/api/admin/analytics/aggregates/compare-team", "сверка команды пользователя \"{username}\"");
        put(actions, "GET", "/api/admin/analytics/aggregates/compare-user-stats", "сверка статистики пользователя {userId}");
        put(actions, "GET", "/api/admin/archive/orders/settings", "загрузка настроек архиватора заказов");
        put(actions, "PUT", "/api/admin/archive/orders/settings", "редактирование настроек архиватора заказов");
        put(actions, "GET", "/api/admin/archive/orders/candidates", "предпросмотр заказов для архива");
        put(actions, "GET", "/api/admin/archive/orders/lock", "проверка блокировки архиватора заказов");
        put(actions, "POST", "/api/admin/archive/orders/dry-run", "проверка кандидатов архиватора заказов");
        put(actions, "POST", "/api/admin/archive/orders/run", "запуск архиватора заказов");
        put(actions, "GET", "/api/admin/archive/batches", "загрузка журнала архиватора");
        put(actions, "GET", "/api/admin/archive/batches/{batchId}", "загрузка запуска архиватора {batchId}");

        put(actions, "GET", "/api/companies/create-payload", "загрузка формы создания компании");
        put(actions, "GET", "/api/companies/categories/{categoryId}/subcategories", "загрузка подкатегорий компании для категории {categoryId}");
        put(actions, "POST", "/api/companies", "создание компании \"{title}\"");

        put(actions, "POST", "/api/manager/companies/{companyId}/status", "смена статуса компании на \"{status}\"");
        put(actions, "GET", "/api/manager/companies/{companyId}/edit", "загрузка редактирования компании {companyId}");
        put(actions, "GET", "/api/manager/companies/{companyId}/order-create", "загрузка создания заказа для компании {companyId}");
        put(actions, "POST", "/api/manager/companies/{companyId}/orders", "создание заказа для компании {companyId}");
        put(actions, "PUT", "/api/manager/companies/{companyId}", "редактирование компании {companyId}");
        put(actions, "PUT", "/api/manager/companies/{companyId}/note", "редактирование заметки компании {companyId}");
        put(actions, "DELETE", "/api/manager/companies/{companyId}/workers/{workerId}", "удаление специалиста {workerId} из компании {companyId}");
        put(actions, "DELETE", "/api/manager/companies/{companyId}/filials/{filialId}", "удаление филиала {filialId} из компании {companyId}");
        put(actions, "PUT", "/api/manager/companies/{companyId}/filials/{filialId}", "редактирование филиала {filialId} компании {companyId}");
        put(actions, "GET", "/api/manager/categories/{categoryId}/subcategories", "загрузка подкатегорий менеджера для категории {categoryId}");
        put(actions, "GET", "/api/manager/overdue-orders", "проверка заказов, которые давно не менялись");
        put(actions, "POST", "/api/manager/orders/{orderId}/status", "смена статуса заказа на \"{status}\"");
        put(actions, "GET", "/api/manager/orders/{orderId}/edit", "загрузка редактирования заказа {orderId}");
        put(actions, "PUT", "/api/manager/orders/{orderId}", "редактирование заказа {orderId}");
        put(actions, "DELETE", "/api/manager/orders/{orderId}", "удаление заказа {orderId}");
        put(actions, "GET", "/api/manager/orders/{orderId}/details", "загрузка деталей заказа {orderId}");
        put(actions, "POST", "/api/manager/orders/{orderId}/reviews", "добавление отзыва к заказу {orderId}");
        put(actions, "POST", "/api/manager/orders/{orderId}/reviews/{reviewId}/change-text", "перегенерация текста отзыва {reviewId}");
        put(actions, "PUT", "/api/manager/orders/{orderId}/reviews/{reviewId}", "редактирование отзыва {reviewId} в заказе {orderId}");
        put(actions, "POST", "/api/manager/orders/{orderId}/reviews/{reviewId}/photo", "загрузка фото отзыва {reviewId}");
        put(actions, "DELETE", "/api/manager/orders/{orderId}/reviews/{reviewId}", "удаление отзыва {reviewId} из заказа {orderId}");
        put(actions, "PUT", "/api/manager/orders/{orderId}/reviews/{reviewId}/text", "редактирование текста отзыва {reviewId}");
        put(actions, "PUT", "/api/manager/orders/{orderId}/reviews/{reviewId}/answer", "редактирование ответа отзыва {reviewId}");
        put(actions, "PUT", "/api/manager/orders/{orderId}/reviews/{reviewId}/note", "редактирование заметки отзыва {reviewId}");
        put(actions, "PUT", "/api/manager/orders/{orderId}/note", "редактирование заметки заказа {orderId}");
        put(actions, "PUT", "/api/manager/orders/{orderId}/company-note", "редактирование заметки компании в заказе {orderId}");
        put(actions, "POST", "/api/manager/orders/{orderId}/reviews/{reviewId}/change-bot", "смена аккаунта отзыва {reviewId}");
        put(actions, "POST", "/api/manager/orders/{orderId}/reviews/{reviewId}/new-account", "назначение нового аккаунта отзыву {reviewId}");
        put(actions, "POST", "/api/manager/orders/{orderId}/reviews/{reviewId}/bots/{botId}/deactivate", "деактивация аккаунта {botId} отзыва {reviewId}");
        put(actions, "POST", "/api/manager/orders/{orderId}/reviews/{reviewId}/publish", "публикация отзыва {reviewId}");
        put(actions, "POST", "/api/manager/orders/{orderId}/bad-review-tasks/{taskId}/cancel", "отмена плохого отзыва {taskId}");
        put(actions, "PUT", "/api/manager/orders/{orderId}/bad-review-tasks/{taskId}", "редактирование плохого отзыва {taskId}");
        put(actions, "POST", "/api/manager/orders/{orderId}/bad-review-tasks/{taskId}/change-bot", "смена аккаунта плохого отзыва {taskId}");
        put(actions, "POST", "/api/manager/orders/{orderId}/bad-review-tasks/{taskId}/complete", "выполнение плохого отзыва {taskId}");
        put(actions, "POST", "/api/manager/orders/{orderId}/reviews/{reviewId}/recovery-tasks", "создание восстановления отзыва {reviewId}");
        put(actions, "POST", "/api/manager/orders/{orderId}/reviews/{reviewId}/help-draft", "создание AI-помощи для отзыва {reviewId}");
        put(actions, "POST", "/api/manager/orders/{orderId}/reviews/help-drafts", "создание AI-помощи для всех отзывов заказа {orderId}");
        put(actions, "PUT", "/api/manager/orders/{orderId}/recovery-tasks/{taskId}", "редактирование восстановления {taskId}");
        put(actions, "POST", "/api/manager/orders/{orderId}/recovery-tasks/{taskId}/complete", "выполнение восстановления {taskId}");
        put(actions, "POST", "/api/manager/orders/{orderId}/recovery-batches/{batchId}/client-notified", "отметка клиента уведомленным по восстановлению {batchId}");
        put(actions, "GET", "/api/manager/archive/orders", "загрузка архива заказов");
        put(actions, "GET", "/api/manager/archive/orders/{orderId}", "загрузка архивного заказа {orderId}");
        put(actions, "POST", "/api/manager/archive/orders/{orderId}/restore", "восстановление архивного заказа {orderId}");

        put(actions, "POST", "/api/worker/orders/{orderId}/status", "смена статуса заказа на \"{status}\"");
        put(actions, "POST", "/api/worker/orders/{orderId}/client-waiting", "изменение ожидания клиента у заказа {orderId}");
        put(actions, "PUT", "/api/worker/orders/{orderId}/note", "редактирование заметки заказа {orderId}");
        put(actions, "PUT", "/api/worker/orders/{orderId}/company-note", "редактирование заметки компании в заказе {orderId}");
        put(actions, "POST", "/api/worker/reviews/{reviewId}/change-bot", "смена аккаунта отзыва {reviewId}");
        put(actions, "POST", "/api/worker/reviews/{reviewId}/bots/{botId}/deactivate", "деактивация аккаунта {botId} отзыва {reviewId}");
        put(actions, "POST", "/api/worker/reviews/{reviewId}/copy-click", "нажатие кнопки копирования данных аккаунта в отзыве {reviewId}");
        put(actions, "POST", "/api/worker/reviews/{reviewId}/publish", "публикация отзыва {reviewId}");
        put(actions, "POST", "/api/worker/bad-review-tasks/{taskId}/complete", "выполнение плохого отзыва {taskId}");
        put(actions, "PUT", "/api/worker/bad-review-tasks/{taskId}", "редактирование плохого отзыва {taskId}");
        put(actions, "POST", "/api/worker/bad-review-tasks/{taskId}/change-bot", "смена аккаунта плохого отзыва {taskId}");
        put(actions, "POST", "/api/worker/bad-review-tasks/{taskId}/bots/{botId}/deactivate", "деактивация аккаунта {botId} плохого отзыва {taskId}");
        put(actions, "POST", "/api/worker/reviews/{reviewId}/nagul", "выгул отзыва {reviewId}");
        put(actions, "PUT", "/api/worker/reviews/{reviewId}/text", "редактирование текста отзыва {reviewId}");
        put(actions, "PUT", "/api/worker/reviews/{reviewId}/answer", "редактирование ответа отзыва {reviewId}");
        put(actions, "PUT", "/api/worker/reviews/{reviewId}/note", "редактирование заметки отзыва {reviewId}");
        put(actions, "GET", "/api/worker/overdue-orders", "проверка заказов специалиста, которые давно не менялись");
        put(actions, "DELETE", "/api/worker/bots/{botId}", "удаление аккаунта {botId}");

        put(actions, "GET", "/api/leads/edit-options", "загрузка справочников для редактирования лидов");
        put(actions, "POST", "/api/leads", "создание лида");
        put(actions, "POST", "/api/leads/file-import", "импорт лидов из файла");
        put(actions, "PUT", "/api/leads/{id}", "редактирование лида {id}");
        put(actions, "DELETE", "/api/leads/{id}", "удаление лида {id}");
        put(actions, "POST", "/api/leads/{id}/status/send", "смена статуса лида {id} на \"Отправлен\"");
        put(actions, "POST", "/api/leads/{id}/status/resend", "смена статуса лида {id} на повторную отправку");
        put(actions, "POST", "/api/leads/{id}/status/archive", "смена статуса лида {id} на \"Архив\"");
        put(actions, "POST", "/api/leads/{id}/status/to-work", "смена статуса лида {id} на \"В работу\"");
        put(actions, "POST", "/api/leads/{id}/status/new", "смена статуса лида {id} на \"Новый\"");
        put(actions, "GET", "/api/leads/modified", "выгрузка измененных лидов");
        put(actions, "POST", "/api/leads/sendToServer", "отправка лида {leadId} на сервер");
        put(actions, "POST", "/api/leads/sync", "синхронизация лида");
        put(actions, "POST", "/api/leads/update", "синхронизация обновления лида");
        put(actions, "POST", "/api/leads/import", "импорт лида");

        put(actions, "POST", "/api/operator/device-token", "создание токена телефона {telephoneId}");
        put(actions, "POST", "/api/operator/leads/{id}/status/send", "смена статуса лида {id} на \"Отправлен\"");
        put(actions, "POST", "/api/operator/leads/{id}/status/to-work", "смена статуса лида {id} на \"В работу\"");
        put(actions, "DELETE", "/api/admin/phones/{id}/device-tokens/{token}", "удаление токена устройства телефона {id}");

        put(actions, "GET", "/api/review-check/{orderDetailId}", "загрузка проверки отзывов {orderDetailId}");
        put(actions, "PUT", "/api/review-check/{orderDetailId}", "сохранение проверки отзывов {orderDetailId}");
        put(actions, "PUT", "/api/review-check/{orderDetailId}/reviews/{reviewId}/text", "редактирование текста отзыва {reviewId} при проверке");
        put(actions, "PUT", "/api/review-check/{orderDetailId}/reviews/{reviewId}/answer", "редактирование ответа отзыва {reviewId} при проверке");
        put(actions, "POST", "/api/review-check/{orderDetailId}/approve", "подтверждение отзывов {orderDetailId}");
        put(actions, "POST", "/api/review-check/{orderDetailId}/correction", "отправка отзывов {orderDetailId} на коррекцию");
        put(actions, "POST", "/api/review-check/{orderDetailId}/send-to-check", "отправка отзывов {orderDetailId} на проверку");
        put(actions, "POST", "/api/review-check/{orderDetailId}/pay-ok", "подтверждение оплаты отзывов {orderDetailId}");
        put(actions, "PUT", "/api/review-check/{orderDetailId}/reviews/{reviewId}/note", "редактирование заметки отзыва {reviewId}");
        put(actions, "PUT", "/api/review-check/{orderDetailId}/order-note", "редактирование заметки заказа {orderDetailId}");
        put(actions, "PUT", "/api/review-check/{orderDetailId}/company-note", "редактирование заметки компании {orderDetailId}");

        put(actions, "POST", "/api/bots/{botId}/browser/open", "открытие браузера аккаунта {botId}");
        put(actions, "POST", "/api/bots/{botId}/browser/close", "закрытие браузера аккаунта {botId}");
        put(actions, "GET", "/api/review", "генерация текста отзыва");

        put(actions, "GET", "/api/ai/reputation/status", "загрузка статуса AI-помощника репутации");
        put(actions, "POST", "/api/ai/reputation/status/openai-check", "проверка маршрута OpenAI для AI-помощника репутации");
        put(actions, "GET", "/api/ai/reputation/prompts", "загрузка промптов AI-помощника репутации");
        put(actions, "GET", "/api/ai/reputation/prompts/{key}/history", "загрузка истории промпта AI-помощника \"{key}\"");
        put(actions, "POST", "/api/ai/reputation/prompts/{key}/validate", "проверка промпта AI-помощника \"{key}\"");
        put(actions, "POST", "/api/ai/reputation/prompts/{key}/preview", "предпросмотр промпта AI-помощника \"{key}\"");
        put(actions, "POST", "/api/ai/reputation/prompts/{key}/presets/{presetKey}", "применение пресета \"{presetKey}\" к промпту AI-помощника \"{key}\"");
        put(actions, "PUT", "/api/ai/reputation/prompts/{key}", "сохранение промпта AI-помощника \"{key}\"");
        put(actions, "DELETE", "/api/ai/reputation/prompts/{key}", "сброс промпта AI-помощника \"{key}\"");
        put(actions, "POST", "/api/ai/reputation/companies/{companyId}/research", "сбор источников AI-репутации для компании {companyId}");
        put(actions, "GET", "/api/ai/reputation/companies/{companyId}/research/latest", "загрузка последнего слепка источников AI-репутации компании {companyId}");
        put(actions, "POST", "/api/ai/reputation/companies/{companyId}/deep-research", "создание глубокого AI-отчета по компании {companyId}");
        put(actions, "POST", "/api/ai/reputation/companies/{companyId}/deep-research/jobs", "запуск фонового глубокого AI-отчета по компании {companyId}");
        put(actions, "POST", "/api/ai/reputation/companies/{companyId}/deep-research/jobs/refresh-sources", "обновление источников глубокого AI-отчета по компании {companyId}");
        put(actions, "POST", "/api/ai/reputation/companies/{companyId}/deep-research/jobs/rebuild-text", "пересборка текста глубокого AI-отчета по компании {companyId}");
        put(actions, "POST", "/api/ai/reputation/companies/{companyId}/deep-research/jobs/rebuild-section", "перегенерация секции глубокого AI-отчета по компании {companyId}");
        put(actions, "GET", "/api/ai/reputation/companies/{companyId}/deep-research/jobs/latest", "загрузка последнего запуска глубокого AI-отчета компании {companyId}");
        put(actions, "GET", "/api/ai/reputation/companies/{companyId}/deep-research/jobs/history", "загрузка истории глубоких AI-отчетов компании {companyId}");
        put(actions, "GET", "/api/ai/reputation/companies/{companyId}/deep-research/jobs/latest/export", "экспорт последнего глубокого AI-отчета компании {companyId} в Markdown");
        put(actions, "GET", "/api/ai/reputation/companies/{companyId}/deep-research/jobs/{jobId}/export", "экспорт глубокого AI-отчета {jobId} компании {companyId} в Markdown");
        put(actions, "GET", "/api/ai/reputation/companies/{companyId}/deep-research/jobs/latest/export/pdf", "экспорт последнего глубокого AI-отчета компании {companyId} в PDF");
        put(actions, "GET", "/api/ai/reputation/companies/{companyId}/deep-research/jobs/{jobId}/export/pdf", "экспорт глубокого AI-отчета {jobId} компании {companyId} в PDF");
        put(actions, "POST", "/api/ai/reputation/companies/{companyId}/content-pack", "создание AI-пакета репутации для компании {companyId}");
        put(actions, "POST", "/api/ai/reputation/companies/{companyId}/content-pack/jobs", "запуск фонового AI-пакета репутации для компании {companyId}");
        put(actions, "GET", "/api/ai/reputation/companies/{companyId}/content-pack/jobs/latest", "загрузка последнего AI-пакета репутации компании {companyId}");
        put(actions, "GET", "/api/ai/reputation/companies/{companyId}/content-pack/jobs/latest/export", "экспорт последнего AI-пакета репутации компании {companyId} в Markdown");
        put(actions, "GET", "/api/ai/reputation/companies/{companyId}/content-pack/jobs/latest/export/pdf", "экспорт последнего AI-пакета репутации компании {companyId} в PDF");
        put(actions, "POST", "/api/ai/reputation/companies/{companyId}/content-pack/review-templates", "улучшение шаблонов отзывов AI-пакета компании {companyId}");
        put(actions, "POST", "/api/ai/reputation/companies/{companyId}/content-pack/review-templates/apply", "замена шаблонов отзывов в AI-пакете компании {companyId}");
        put(actions, "POST", "/api/ai/reputation/companies/{companyId}/content-pack/review-draft", "создание точечного черновика отзыва по AI-пакету компании {companyId}");
        put(actions, "POST", "/api/ai/reputation/companies/{companyId}/review-draft", "создание черновика отзыва через AI-помощник для компании {companyId}");
        put(actions, "POST", "/api/ai/reputation/review/rewrite", "переписывание отзыва через AI-помощник репутации");
        put(actions, "POST", "/api/ai/reputation/review/check", "проверка отзыва на безопасность через AI-помощник репутации");
        put(actions, "POST", "/api/ai/reputation/companies/{companyId}/reply/positive", "создание ответа на положительный отзыв через AI-помощник для компании {companyId}");
        put(actions, "POST", "/api/ai/reputation/companies/{companyId}/reply/negative", "создание ответа на негативный отзыв через AI-помощник для компании {companyId}");

        put(actions, "POST", "/api/auth/register", "регистрация клиента \"{username}\"");
        put(actions, "POST", "/api/auth/legacy-migration", "миграция пользователя \"{username}\"");
        put(actions, "GET", "/api/me", "загрузка текущего пользователя");
        put(actions, "GET", "/api/dispatch-settings/cron", "загрузка cron-настройки рассылки");
        put(actions, "GET", "/categories/getSubcategories", "подгрузка подкатегорий для категории {categoryId}");
        put(actions, "GET", "/companies/getSubcategories", "подгрузка подкатегорий компании для категории {categoryId}");
        put(actions, "GET", "/sendEmail", "отправка тестового email");
        put(actions, "GET", "/images/{id}", "загрузка изображения {id}");
        put(actions, "POST", "/webhook/whatsapp-reply", "получение ответа WhatsApp");
        put(actions, "POST", "/webhook/whatsapp-group-reply", "получение группового ответа WhatsApp");
        put(actions, "GET", "/webhook/health", "проверка webhook health");
        put(actions, "POST", "/admin/dispatch/start", "ручной запуск рассылки");
        put(actions, "GET", "/admin/dispatch/start", "тестовый запуск рассылки");
        put(actions, "GET", "/admin/dispatch/startLastSeen", "ручной запуск last-seen рассылки");
        put(actions, "POST", "/telephone/device-token", "создание токена телефона {telephoneId}");
        put(actions, "GET", "/logs", "просмотр логов системы");
        put(actions, "GET", "/logs/tail", "загрузка новых строк лога");
        put(actions, "POST", "/logs/clear", "очистка текущего файла лога");
        put(actions, "POST", "/logs/clear/all", "очистка основных файлов логов");
        put(actions, "GET", "/logs/ui", "открытие страницы живых логов");

        return Map.copyOf(actions);
    }

    private static void put(Map<ActionKey, String> actions, String method, String pattern, String action) {
        actions.put(new ActionKey(method, pattern), action);
    }

    private record ActionKey(String method, String pattern) {
    }
}

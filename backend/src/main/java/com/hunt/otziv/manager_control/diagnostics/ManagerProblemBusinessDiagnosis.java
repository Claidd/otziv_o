package com.hunt.otziv.manager_control.diagnostics;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.*;

/** Read-only, evidence-bound guidance. Never changes a card, sends a message or proves resolution. */
final class ManagerProblemBusinessDiagnosis {
    static final String MODEL = "server-rules";
    private static final Pattern HISTORICAL_REVIEW_ID = Pattern.compile(
            "(?iu)(?:отзыв\\s*#|\\breview\\s+#?)\\s*([1-9][0-9]{0,18})(?![0-9])");
    private static final Pattern TECHNICAL = Pattern.compile(
            "(?i)(?:[a-z][a-z0-9]*_[a-z0-9_]+|\\b(?:invoice|automation|card|order|coverage)\\.[a-z]|\\b(?:invoice|order|card|automation|attempt|message|chat|case|sourceTask):[0-9]+|[{}]|\\b(?:caseId|operationId|sourceTaskId|UNKNOWN|PREPARED|QUEUED|COLLECTING|READY|UNAVAILABLE|SKIPPED)\\b|[0-9a-f]{8}-[0-9a-f-]{27,})");

    private ManagerProblemBusinessDiagnosis() { }

    /** Closing a problem requires a source result, even when older model advice accepts a handoff or another deadline. */
    static Diagnosis withClosurePolicy(List<Fact> facts, Diagnosis diagnosis) {
        if (diagnosis == null) return null;
        String type = value(facts, "card", "entity_type");
        List<String> verification;
        List<Action> actions = diagnosis.actions();
        if (ordinaryInvoiceReadiness(facts)) {
            verification = List.of("Все необходимые позиции подтверждённо готовы либо их ненужность или исключение обоснованы в исходных заказах и счёте, а причина замечания устранена. Либо источник штатно подтверждает, что сама причина отсутствует или больше не актуальна.",
                    "Согласованный срок, внешняя зависимость или передача ответственному разрешают лишь отложить проверку. Они не закрывают замечание, даже если названы результатом отдельного действия.");
            actions = actions.stream().map(action -> new Action(action.code(), action.text(), action.owner(),
                    "WAIT_FOR_DEPENDENCY".equals(action.code())
                            ? "Зафиксированы зависимость и срок следующей проверки; замечание отложено и остаётся открытым до подтверждения результата в источнике."
                            : "Результат шага подтверждён записью исходного заказа или счёта. Срок или сообщение сотрудника не заменяют готовность позиции; при сохраняющейся причине замечание остаётся открытым.",
                    action.evidenceRefs())).toList();
        } else if ("CLIENT_CHAT_UNANSWERED".equals(type)) {
            verification = List.of("В исходной переписке после последнего сообщения клиента записан проверенный ответ по существу либо штатно подтверждено, что ответ не требуется, с зафиксированной причиной.",
                    "Передача обращения, назначение ответственного, взятие в работу и обещание ответить сами по себе не подтверждают устранение причины и не разрешают закрыть замечание.");
        } else if ("AUTOMATION_FAILURE".equals(type) && currentPaymentPreparationFailure(facts) != null) {
            verification = List.of("Исходный заказ и счёт подтверждают успешную подготовку существующего счёта и проверенный результат его отправки либо штатно зафиксированную ненужность этих действий с причиной.",
                    "Передача администратору, назначение срока следующей проверки и повторная неудачная попытка сами по себе не устраняют причину и не разрешают закрыть замечание.");
        } else return diagnosis;
        return new Diagnosis(diagnosis.summary(), diagnosis.confirmedFacts(), diagnosis.hypotheses(), diagnosis.missingEvidence(), actions,
                verification);
    }

    /** Readiness is not payment, archival reconciliation or an uncertain delivery. Unknown counts are not zero. */
    static boolean ordinaryInvoiceReadiness(List<Fact> facts) {
        if (!Set.of("COMMON_INVOICE", "COMMON_INVOICE_AUTOMATION").contains(value(facts, "card", "entity_type"))
                || !"COLLECTING".equals(value(facts, "invoice", "status"))
                || !uncertainDelivery(facts).isEmpty() || hasHistoricalInvoiceArchive(facts)
                || number(facts, "invoice", "archivedActiveCount") != 0) return false;
        long total = number(facts, "invoice", "orderCount"), ready = number(facts, "invoice", "readyCount");
        return total > 0 && ready >= 0 && ready < total;
    }

    static boolean readable(Diagnosis value) {
        return value != null && readable(value.summary(), 400) && !value.actions().isEmpty() && value.actions().size() <= 3
                && !value.verification().isEmpty() && value.verification().size() <= 2
                && value.actions().stream().allMatch(a -> readable(a.text(), 350) && readable(a.owner(), 100) && readable(a.verification(), 250))
                && value.verification().stream().allMatch(v -> readable(v, 300));
    }

    private static boolean readable(String text, int length) {
        return text != null && !text.isBlank() && text.length() <= length && !TECHNICAL.matcher(text).find();
    }

    static List<Fact> uncertainDelivery(List<Fact> facts) {
        boolean commonInvoice = value(facts, "card", "entity_type").startsWith("COMMON_INVOICE");
        return facts.stream().filter(f -> {
            if (!Set.of("automation", "manager_delivery", "invoice").contains(f.subject())) return false;
            // This source marker proves only that an existing send has no settled result.
            // Old DONE automation records cannot establish delivery or a root cause for it.
            if (commonInvoice && "invoice".equals(f.subject()) && "last_error".equals(f.field())
                    && "message_send_in_progress".equals(f.value())) return true;
            String prefix = f.reference().substring(0, f.reference().lastIndexOf(':') + 1);
            if (facts.stream().anyMatch(scope -> scope.reference().equals(prefix + "current_cycle") && "false".equals(scope.value()))) return false;
            String value = f.value().toUpperCase(Locale.ROOT);
            if (Set.of("delivery_status", "state").contains(f.field()))
                return Set.of("UNKNOWN", "UNCERTAIN", "PENDING", "PREPARED", "SENDING").contains(value);
            return Set.of("last_error_code", "error_code").contains(f.field())
                    && (value.contains("DELIVERY_UNCERTAIN") || value.contains("OUTCOME_UNCERTAIN")
                    || value.contains("MANUAL_DELIVERY_HANDOFF") || value.equals("LEGACY_OPERATION_UNVERIFIED"));
        }).toList();
    }

    static Diagnosis fallback(List<Fact> facts) {
        List<Fact> uncertain = uncertainDelivery(facts);
        if (!uncertain.isEmpty()) return uncertain(facts, uncertain.getFirst());
        if (hasHistoricalInvoiceArchive(facts)) return historicalInvoiceArchive(facts);
        Fact publicationConflict = currentInvoicePublicationConflict(facts);
        if (publicationConflict != null) return invoicePublicationConflict(facts, publicationConflict);
        String type = value(facts, "card", "entity_type");
        if (type.startsWith("COMMON_INVOICE") || type.isBlank() && has(facts, "invoice", "status")) return invoice(facts);
        if ("AUTOMATION_FAILURE".equals(type)) {
            Fact preparationFailure = currentPaymentPreparationFailure(facts);
            if (preparationFailure != null) {
                String verification = "Подготовка существующего счёта завершена, результат отправки подтверждён и ошибка больше не повторяется.";
                return new Diagnosis("Подготовка счёта завершилась ошибкой. По этой попытке нельзя считать счёт отправленным.",
                        List.of(), List.of(), List.of("Нужно подтвердить результат подготовки и существующей отправки."),
                        List.of(new Action("ESCALATE", "Передайте администратору проверку подготовки оплаты в связанном заказе. Не создавайте второй счёт и не отправляйте сообщение вручную до сверки существующей попытки.",
                                "Администратор", verification, List.of(preparationFailure.reference()))), List.of(verification));
            }
        }
        if (type.startsWith("CLIENT_CHAT") || type.isBlank() && has(facts, "client_chat", "status")) {
            return guidance("По переписке нужно проверить, получил ли клиент ответ на свой вопрос.", facts,
                    "Сопоставьте последнее сообщение клиента с ответом в чате. Проверьте, дан ли ответ по существу и подтверждены ли обещанные действия.",
                    "В переписке есть ответ на вопрос клиента, а заявленные действия подтверждены в системе.");
        }
        if ("На проверке".equals(value(facts, "order", "status"))) {
            return guidance("Заказ остаётся на проверке у клиента. Доставка напоминания сама по себе не подтверждает, что проверка закончена.", facts,
                    "Откройте заказ и существующую переписку: проверьте, получен ли результат проверки клиента и какие замечания ещё требуют выполнения.",
                    "Результат проверки клиента отражён в заказе, замечания выполнены и причина контроля больше не действует.");
        }
        if ("Архив".equals(value(facts, "order", "status"))) {
            return guidance("Заказ находится в архиве. Нужно сверить, относится ли замечание к ещё не выполненной работе.", facts,
                    "Проверьте основание архивирования и оставшиеся обязательства. При расхождении передайте проверку администратору; не создавайте повторную публикацию или счёт.",
                    "Подтверждено, что причина замечания устранена или больше не относится к действующим обязательствам.");
        }
        return guidance("Причина замечания пока не подтверждена полностью: требуется сверка исходной задачи.", facts,
                "Откройте связанную задачу и проверьте её текущее состояние и результат последних действий. Если данных недостаточно, уточните их у ответственного и зафиксируйте срок проверки.",
                "Исходная задача подтверждает устранение причины; одного комментария или обещания недостаточно.");
    }

    private static Fact currentPaymentPreparationFailure(List<Fact> facts) {
        return facts.stream().filter(f -> "automation".equals(f.subject())
                && "last_error_code".equals(f.field()) && "payment_instruction_failed".equals(f.value())
                && facts.stream().noneMatch(scope -> scope.reference().equals(f.reference().replace("last_error_code", "current_cycle"))
                    && "false".equals(scope.value()))).findFirst().orElse(null);
    }

    static boolean hasHistoricalInvoiceArchive(List<Fact> facts) {
        if (!Set.of("COMMON_INVOICE", "COMMON_INVOICE_AUTOMATION").contains(value(facts, "card", "entity_type"))
                || !"MANUAL_ARCHIVE".equals(value(facts, "invoice", "close_reason"))) return false;
        String closedAt = value(facts, "invoice", "closed_at");
        try {
            java.time.LocalDateTime.parse(closedAt.replace(' ', 'T'));
        } catch (java.time.format.DateTimeParseException ignored) {
            return false;
        }
        String status = value(facts, "invoice", "status");
        // NEEDS_ATTENTION can legitimately follow a late payment or reversal; do not hide that incident as a revival.
        return Set.of("COLLECTING", "READY", "INVOICED", "REMINDER", "PARTIALLY_PAID").contains(status)
                || "ARCHIVED".equals(status) && number(facts, "invoice", "paid_kopecks") > 0;
    }

    private static Diagnosis historicalInvoiceArchive(List<Fact> facts) {
        boolean archived = "ARCHIVED".equals(value(facts, "invoice", "status"));
        String summary = archived ? "Счёт находится в историческом архиве; учтённая оплата сохранена. "
                : "У счёта сохранено прежнее ручное архивирование, но текущий статус снова открыт. ";
        long amount = number(facts, "invoice", "amount_kopecks"), paid = number(facts, "invoice", "paid_kopecks");
        if (amount > 0 && paid >= 0 && paid <= amount) {
            summary += "В составе учтено " + rubles(paid) + " ₽ из " + rubles(amount) + " ₽. ";
            if (paid < amount) summary += "Расчётный остаток " + rubles(amount - paid) + " ₽ не подтверждает действующий долг. ";
        }
        summary += "Нужно сверить историю и основания обязательств.";
        List<String> refs = facts.stream().filter(f -> "invoice".equals(f.subject())
                && Set.of("status", "closed_at", "close_reason", "amount_kopecks", "paid_kopecks").contains(f.field()))
                .map(Fact::reference).toList();
        return new Diagnosis(summary, List.of(), List.of(),
                List.of("Нужны подтверждённые основания действующих обязательств по прежнему архивному составу.",
                        "Возврат отдельного заказа и последующая оплата сами по себе не подтверждают восстановление всего счёта."),
                List.of(new Action("CHECK_SOURCE", "Сопоставьте архивирование счёта, возвраты отдельных заказов и подтверждённую оплату с журналом и платёжным источником. Не требуйте расчётный остаток как установленный долг без проверки оснований.",
                                "Менеджер", "История счёта и заказов сопоставлена с сохранёнными подтверждениями оплаты и основаниями обязательств.", refs),
                        new Action("ESCALATE", "Передайте администратору расхождение архивного счёта и состава работ. Сохраните учтённую оплату; не списывайте остаток, не возвращайте заказы и не отправляйте новый счёт автоматически.",
                                "Администратор", "В источнике отражено проверенное состояние счёта; подтверждённая оплата и её история сохранены.", refs)),
                List.of("Состояние счёта, состав и действующие обязательства подтверждены исходными записями, причина замечания устранена. Сам архив или ответ сотрудника не подтверждает отмену долга либо оплату."));
    }

    static boolean hasCurrentInvoicePublicationConflict(List<Fact> facts) {
        // Current invoice.last_error can describe an old attempt; it does not prove a current text conflict.
        return currentInvoicePublicationConflict(facts) != null;
    }

    private static Fact currentInvoicePublicationConflict(List<Fact> facts) {
        if (!Set.of("COMMON_INVOICE", "COMMON_INVOICE_AUTOMATION").contains(value(facts, "card", "entity_type"))) return null;
        return facts.stream().filter(f -> "invoice".equals(f.subject()) && "last_error".equals(f.field()))
                .filter(f -> {
                    String error = f.value().toLowerCase(Locale.ROOT);
                    return error.contains("текст") && error.contains("уже опубликован ранее");
                }).findFirst().orElse(null);
    }

    private static Diagnosis invoicePublicationConflict(List<Fact> facts, Fact conflict) {
        long total = number(facts, "invoice", "orderCount"), ready = number(facts, "invoice", "readyCount");
        String summary = total >= 0 && ready >= 0 && ready <= total
                ? "По сохранённому снимку готовы " + ready + " из " + total + " позиций. " : "Готовность всех позиций общего счёта нужно сверить. ";
        var reviewId = HISTORICAL_REVIEW_ID.matcher(conflict.value());
        summary += "При предыдущей попытке одобрения сохранена ошибка повторного текста"
                + (reviewId.find() ? " (отзыв #" + reviewId.group(1) + ")" : "")
                + ". Она не подтверждает конфликт в текущем тексте.";
        List<String> readinessRefs = facts.stream().filter(f -> "invoice".equals(f.subject()) && Set.of("orderCount", "readyCount").contains(f.field()))
                .map(Fact::reference).toList();
        String unready = unreadyInvoicePositions(facts);
        return new Diagnosis(summary, List.of(), List.of(),
                List.of("Нужно сверить актуальность сохранённой ошибки по текущему тексту отзыва.", "Причины задержки неготовых позиций и согласование клиента нужно проверить в исходных заказах."),
                List.of(new Action("CHECK_SOURCE", "Откройте отзыв из сохранённой ошибки и связанный заказ. Сверьте текущий текст и согласование клиента. Сохранение текста не означает одобрение публикации.",
                                "Менеджер", "В исходном заказе подтверждены текущий текст, решение клиента и актуальность прежней ошибки.", List.of(conflict.reference())),
                        new Action("CHECK_SOURCE", (unready.isBlank() ? "" : "По снимку не готовы: " + unready + ". ")
                                + "Проверьте неготовые позиции по отдельности и согласование клиента. Прежняя ошибка одного отзыва не объясняет задержку остальных.",
                                "Менеджер", "По каждой неготовой позиции причина задержки и результат её устранения подтверждены в соответствующем заказе.", readinessRefs.isEmpty() ? List.of(conflict.reference()) : readinessRefs)),
                List.of("В счёте и связанных заказах подтверждена готовность действующих позиций и устранены причины задержек. Одного ответа ответственного или повторной публикации недостаточно."));
    }

    private static String unreadyInvoicePositions(List<Fact> facts) {
        List<String> positions = facts.stream().filter(f -> "invoice_order".equals(f.subject()) && "ready".equals(f.field())
                        && Set.of("false", "0").contains(f.value()))
                .map(f -> f.reference().substring(0, f.reference().lastIndexOf(':') + 1))
                .filter(prefix -> Set.of("true", "1").contains(positionValue(facts, prefix, "active_membership")))
                .filter(prefix -> positionValue(facts, prefix, "order_id").matches("[1-9][0-9]{0,18}"))
                .map(prefix -> "#" + positionValue(facts, prefix, "order_id") + " «"
                        + (positionValue(facts, prefix, "current_order_status").isBlank() ? "без статуса"
                        : positionValue(facts, prefix, "current_order_status")) + "»")
                .toList();
        return String.join(", ", positions.stream().limit(3).toList())
                + (positions.size() > 3 ? " и ещё " + (positions.size() - 3) : "");
    }

    private static String positionValue(List<Fact> facts, String prefix, String field) {
        return facts.stream().filter(f -> f.reference().equals(prefix + field)).map(Fact::value).findFirst().orElse("");
    }

    static Diagnosis uncertain(List<Fact> facts, Fact fact) {
        if ("invoice".equals(fact.subject()) && "last_error".equals(fact.field())
                && "message_send_in_progress".equals(fact.value())) {
            return new Diagnosis("Результат существующей отправки сообщения по общему счёту не подтверждён. Нужна сверка перед любыми повторами.",
                    List.of(), List.of(), List.of("Нужно подтверждение результата существующей отправки по счёту."),
                    List.of(new Action("CHECK_DELIVERY", "Нажмите «Проверить отправку». Система сверит существующую отправку с квитанцией и историей нужного чата и восстановит подтверждение при надёжном совпадении. Новое сообщение или счёт для проверки не создаются.",
                                    "Система", "В журнале существующей отправки сохранён проверяемый результат сверки. Отсутствие сообщения в доступной истории не доказывает, что отправки не было.", List.of(fact.reference()))),
                    List.of("Результат существующей отправки подтверждён проверяемой записью, а причина замечания по счёту устранена. Запуск проверки сам по себе замечание не закрывает."));
        }
        boolean periodicReminder = isCurrentPeriodicReminder(facts, fact);
        String action = periodicReminder
                ? "Система проверяет подтверждение в исходном канале и автоматически восстанавливает очередь плановых напоминаний на будущий срок с полным интервалом ожидания. Старая неподтверждённая отправка не повторяется. Если восстановлению мешает другая незавершённая отправка, нужна сверка администратором."
                : "Система сверяет существующую отправку с квитанцией исходного канала. Если подтверждения нет, нужна сверка администратором. От менеджера не требуется подтверждать доставку или повторять отправку. Новое сообщение не создаётся.";
        String verification = periodicReminder
                ? "Подтверждена отправка либо восстановлена очередь плановых напоминаний с указанным сроком. Оплату и проверку отзывов система закрывает только по их фактическому результату."
                : "Результат существующей отправки подтверждён проверяемой записью, а причина замечания устранена. Запуск проверки сам по себе замечание не закрывает.";
        return new Diagnosis("Результат предыдущей отправки клиенту не подтверждён. Повторное сообщение может оказаться дубликатом.",
                List.of(), List.of(), List.of("Нужно подтверждение результата существующей отправки."),
                List.of(new Action("CHECK_DELIVERY", action,
                                "Система", periodicReminder
                                    ? "Сохранена квитанция исходной отправки либо проверяемый срок следующего планового напоминания. Прежний неопределённый результат остаётся в истории без повтора."
                                    : "Сохранено подтверждение результата именно существующей отправки. Отсутствие сообщения в доступной истории не доказывает, что отправки не было.", List.of(fact.reference()))),
                List.of(verification));
    }

    private static boolean isCurrentPeriodicReminder(List<Fact> facts, Fact uncertain) {
        if (!"automation".equals(uncertain.subject())) return false;
        String prefix = uncertain.reference().substring(0, uncertain.reference().lastIndexOf(':') + 1);
        return facts.stream().anyMatch(f -> f.reference().equals(prefix + "scenario")
                    && Set.of("REVIEW_CHECK_REMINDER", "PAYMENT_REMINDER").contains(f.value()))
                && facts.stream().anyMatch(f -> f.reference().equals(prefix + "state_status") && "ACTIVE".equals(f.value()))
                && facts.stream().anyMatch(f -> f.reference().equals(prefix + "current_cycle") && "true".equals(f.value()));
    }

    private static Diagnosis invoice(List<Fact> facts) {
        String status = value(facts, "invoice", "status");
        long total = number(facts, "invoice", "orderCount"), ready = number(facts, "invoice", "readyCount");
        long archived = number(facts, "invoice", "archivedActiveCount");
        boolean disabled = Set.of("false", "0").contains(value(facts, "invoice", "account_enabled"));
        String summary;
        String action;
        String verification;
        if ("PARTIALLY_PAID".equals(status)) {
            summary = "По общему счёту подтверждена только часть оплаты.";
            long amount = number(facts, "invoice", "amount_kopecks"), paid = number(facts, "invoice", "paid_kopecks");
            if (amount > 0 && paid >= 0 && paid < amount) summary = "По общему счёту оплачено " + rubles(paid) + " ₽ из " + rubles(amount) + " ₽. Остаток " + rubles(amount - paid) + " ₽ требует сверки.";
            action = "Сверьте подтверждённые платежи и неоплаченную часть работ в существующем счёте. Не создавайте новый счёт взамен без проверки состава и начислений.";
            verification = "Состав работ и начисления сверены, неоплаченная часть урегулирована и это отражено в существующем счёте.";
        } else if (Set.of("PAID", "ARCHIVED", "DISABLED").contains(status)) {
            summary = switch (status) {
                case "PAID" -> "По данным системы счёт оплачен. Нужно сверить, осталась ли причина замечания.";
                case "ARCHIVED" -> "Счёт находится в архиве. Само архивирование не подтверждает урегулирование оставшихся обязательств.";
                default -> "Счёт отключён. Это само по себе не подтверждает урегулирование оставшихся обязательств.";
            };
            action = "Проверьте итоговый статус счёта, связанные работы и оставшиеся ошибки. При расхождении карточки с источником передайте сверку администратору.";
            verification = "Источник подтверждает итог счёта и отсутствие той причины, по которой создано замечание.";
        } else if ("COLLECTING".equals(status)) {
            summary = total >= 0 && ready >= 0 && ready <= total
                    ? "Общий счёт ещё собирается: готовы " + ready + " из " + total + " действующих позиций."
                    : "Общий счёт ещё собирается; готовность всех позиций не подтверждена.";
            action = "Проверьте неготовые позиции в составе счёта: выполнена ли работа и подтверждена ли её готовность. Уточните оставшиеся действия и срок у ответственного специалиста.";
            if (total > 0 && ready == total) action = "Все действующие позиции готовы. Сверьте состояние существующего счёта и причину задержки его подготовки с администратором; не создавайте дубликат.";
            else if (total == 0) action = "Уточните действующий состав работ и необходимость этого счёта. Не возвращайте архивные заказы в работу только ради наполнения счёта.";
            verification = "Готовность действующих позиций подтверждена, состав счёта корректен и причина задержки устранена.";
        } else {
            summary = "Общий счёт требует проверки текущей оплаты и состояния связанных работ.";
            action = "Откройте существующий счёт, сверьте платежи и состояние работ. При расхождении данных передайте проверку администратору, сохранив существующий счёт.";
            verification = "Причина замечания устранена и подтверждена текущими данными счёта и связанных работ.";
        }
        if (archived > 0) {
            summary += " В составе остаются архивные заказы.";
            action = "Сверьте архивные заказы в составе счёта и основания начислений с администратором. Не возвращайте их в работу и не публикуйте повторно только ради готовности счёта.";
        }
        if (disabled) summary += " Автоматическое формирование этого общего счёта отключено.";
        return guidance(summary, facts, action, verification);
    }

    private static Diagnosis guidance(String summary, List<Fact> facts, String action, String verification) {
        List<String> refs = facts.stream().filter(f -> !Set.of("reason", "comment", "title", "last_error", "last_error_message").contains(f.field()))
                .map(Fact::reference).limit(3).toList();
        return new Diagnosis(summary, List.of(), List.of(), List.of("Предварительный разбор по данным системы; точную причину подтвердите при сверке."),
                List.of(new Action("CHECK_SOURCE", action, "Менеджер", verification, refs)), List.of(verification));
    }

    private static boolean has(List<Fact> facts, String subject, String field) { return !value(facts, subject, field).isBlank(); }
    private static String value(List<Fact> facts, String subject, String field) {
        return facts.stream().filter(f -> subject.equals(f.subject()) && field.equals(f.field())).map(Fact::value).findFirst().orElse("");
    }
    private static long number(List<Fact> facts, String subject, String field) {
        try { return Long.parseLong(value(facts, subject, field)); } catch (NumberFormatException ignored) { return -1; }
    }
    private static String rubles(long kopecks) { return java.math.BigDecimal.valueOf(kopecks, 2).stripTrailingZeros().toPlainString().replace('.', ','); }
}

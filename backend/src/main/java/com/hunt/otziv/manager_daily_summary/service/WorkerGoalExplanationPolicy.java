package com.hunt.otziv.manager_daily_summary.service;

import java.util.Locale;
import java.util.regex.Pattern;

/** Bounds reported explanations; it never establishes a payment or completed work. */
final class WorkerGoalExplanationPolicy {
    private WorkerGoalExplanationPolicy() { }

    private static final Pattern MONEY_OR_DELIVERY = Pattern.compile(
            "(?U)\\b(?:клиент\\p{L}*|заказчик\\p{L}*|оплат\\p{L}*|плат[её]ж\\p{L}*|деньг\\p{L}*|руб\\p{L}*|чек\\p{L}*|сч[её]т\\p{L}*|достав\\p{L}*|перев[её]л\\p{L}*)\\b");
    private static final Pattern COMPLETED_RESULT = Pattern.compile(
            "(?U)\\b(?:выполнил[аи]?|выполнили|закрыл[аи]?|закрыли|устранил[аи]?|устранили|"
                    + "восстановил[аи]?|восстановили|опубликовал[аи]?|опубликовали|исправил[аи]?|исправили|"
                    + "выполнен[аоы]?|закрыт[аоы]?|устран[её]н[аоы]?|восстановлен[аоы]?|опубликован[аоы]?|"
                    + "исправлен[аоы]?|завершен[аоы]?|готов[аоы]?)\\b");
    private static final Pattern CONTACT = Pattern.compile(
            "(?U)\\b(?:я|мы)\\b.*\\b(?:\\p{L}*звонил[аи]?|\\p{L}*звонили|\\p{L}*звон[а-я]*сь|"
                    + "писал[аи]?|писали|написал[аи]?|написали|поговорил[аи]?|поговорили|"
                    + "обсудил[аи]?|обсудили|напомнил[аи]?|напомнили|провел[аи]? разбор|провели разбор)\\b");
    private static final Pattern RESPONSE_PENDING = Pattern.compile(
            "(?U)(?:\\bне\\s+ответил[аи]?\\b.*\\b(?:мне|нам)\\b|"
                    + "\\bответ\\p{L}*\\b.{0,40}\\b(?:нет|не было|не получен[аоы]?)\\b|"
                    + "\\bпричин\\p{L}*\\b.{0,30}\\b(?:неизвестн[аоы]?|не выяснен[аоы]?|не установлен[аоы]?)\\b)");
    private static final Pattern SENT = Pattern.compile(
            "(?U)\\b(?:отправ\\p{L}*|переслал[аи]?|выслал[аи]?|разослал[аи]?|отослал[аи]?|направил[аи]?)\\b");

    private static boolean privateMessage(String value) {
        return value.matches("(?U).*\\b(?:сообщени[ея]|смс|напоминание)\\b.*")
                && value.matches("(?U).*\\b(?:специалист\\p{L}*|ей|ему|им)\\b.*")
                && !value.matches("(?U).*\\b(?:текст\\p{L}*|отзыв\\p{L}*|заказ\\p{L}*|файл\\p{L}*|документ\\p{L}*)\\b.*");
    }

    static boolean containsUnverifiedOutcome(String quote) {
        String value = normalize(quote);
        return value.contains("%") || MONEY_OR_DELIVERY.matcher(value).find()
                || COMPLETED_RESULT.matcher(value).find()
                || SENT.matcher(value).find() && !privateMessage(value)
                || value.matches("(?U).*\\b\\d+\\s+из\\s+\\d+\\b.*");
    }

    static boolean reportedExplanation(String quote, boolean declaredReport) {
        String value = normalize(quote);
        if (value.isBlank() || containsUnverifiedOutcome(value)) return false;
        if(value.matches("(?U).*\\b(?:переведены|переданы|переназначены|разблокирован[аоы]?|удален[аоы]?)\\b.*")) return false;
        if(value.matches("(?U).*\\b(?:поступил\\p{L}*|пришл\\p{L}*|назначен\\p{L}*|выдан\\p{L}*)\\b.*")
                && !value.matches("(?U).*(?:со слов|с (?:ее|его|их) слов|по словам)\\b.*")) return false;
        if(value.matches("(?s).*\\d.*") && value.matches("(?U).*\\b(?:задач\\p{L}*|текст\\p{L}*|заказ\\p{L}*|отзыв\\p{L}*)\\b.*")) return false;
        if(value.matches("(?U)(?:по этому вопросу )?(?:уже )?(?:были )?(?:меры приняты|приняты меры)[.! ]*")) return false;
        // The model classifies meaning. The server bounds the kind and scope instead of
        // requiring a particular contact verb. A report remains attributed, not verified.
        return declaredReport || CONTACT.matcher(value).find() || RESPONSE_PENDING.matcher(value).find()
                || SENT.matcher(value).find() && privateMessage(value)
                || value.matches("(?U).*(?:со слов|с (?:ее|его|их) слов|по словам|по моему разбору|считаю причиной)\\b.*")
                || value.matches("(?U)(?:помешал[оа]|причина\\p{L}*)\\b.*")
                    && value.matches("(?U).*\\b(?:накоп\\p{L}*|приоритет\\p{L}*|нагрузк\\p{L}*|организац\\p{L}*|обработк\\p{L}*)\\b.*");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ").trim();
    }
}

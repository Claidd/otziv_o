package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class PublicationHealthMonitorService {

    private static final int PUBLICATION_STALE_DAYS = 60;
    private static final int PUBLICATION_SPAN_LIMIT_DAYS = 75;
    private static final int PUBLICATION_FUTURE_LIMIT_DAYS = 90;
    private static final int PUBLICATION_FIRST_FUTURE_LIMIT_DAYS = 21;
    private static final int ISSUE_SAMPLE_LIMIT = 12;

    private final NamedParameterJdbcTemplate jdbc;
    private final AppSettingService appSettingService;
    private final UserRepository userRepository;
    private final ManagerRepository managerRepository;
    private final TelegramService telegramService;

    @Scheduled(
            cron = "${publication.health-monitor.cron:0 20 9 * * *}",
            zone = "${publication.health-monitor.zone:Asia/Irkutsk}"
    )
    public void sendDailyPublicationHealthReport() {
        if (!appSettingService.getBoolean(AppSettingService.PUBLICATION_HEALTH_MONITOR_ENABLED, true)) {
            return;
        }

        String today = LocalDate.now().toString();
        String lastRunKey = appSettingService.getString(AppSettingService.PUBLICATION_HEALTH_MONITOR_LAST_RUN_KEY, "");
        if (today.equals(lastRunKey)) {
            return;
        }

        PublicationReport ownerReport = publicationReport(null);
        if (ownerReport.suspicious() <= 0) {
            appSettingService.setString(AppSettingService.PUBLICATION_HEALTH_MONITOR_LAST_RUN_KEY, today);
            return;
        }

        sendOwnerReports(ownerReport);
        sendManagerReports();
        appSettingService.setString(AppSettingService.PUBLICATION_HEALTH_MONITOR_LAST_RUN_KEY, today);
    }

    private void sendOwnerReports(PublicationReport report) {
        String message = buildMessage(report, "общий отчет");
        Set<Long> recipients = ownerRecipients();
        if (recipients.isEmpty()) {
            log.warn("Ежедневный монитор публикаций не отправлен владельцам: нет Telegram chatId");
            return;
        }
        recipients.forEach(chatId -> telegramService.sendMessage(chatId, message));
    }

    private void sendManagerReports() {
        Map<Long, List<Long>> managerIdsByChat = managerIdsByChat();
        if (managerIdsByChat.isEmpty()) {
            log.warn("Ежедневный монитор публикаций не отправлен менеджерам: нет Telegram chatId");
            return;
        }

        managerIdsByChat.forEach((chatId, managerIds) -> {
            PublicationReport report = publicationReport(managerIds);
            if (report.suspicious() > 0) {
                telegramService.sendMessage(chatId, buildMessage(report, "ваши компании"));
            }
        });
    }

    private String buildMessage(PublicationReport report, String scope) {
        StringBuilder message = new StringBuilder();
        message.append("Монитор публикаций (")
                .append(scope)
                .append("): найдено проблемных заказов: ")
                .append(report.suspicious())
                .append("\n\n");

        for (IssueGroup issue : report.issues()) {
            if (issue.count() <= 0) {
                continue;
            }
            message.append("- ")
                    .append(issue.title())
                    .append(": ")
                    .append(issue.count())
                    .append("\n");

            issue.samples().forEach(sample -> message
                    .append("  #")
                    .append(sample.orderId())
                    .append(" ")
                    .append(safeTitle(sample.companyTitle()))
                    .append("\n"));

            long hidden = issue.count() - issue.samples().size();
            if (hidden > 0) {
                message.append("  ... еще ").append(hidden).append("\n");
            }
        }
        return message.toString().trim();
    }

    private PublicationReport publicationReport(List<Long> managerIds) {
        MapSqlParameterSource params = baseParams(managerIds);
        long suspicious = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM (
                  SELECT o.order_id,
                         TIMESTAMPDIFF(DAY, COALESCE(o.order_status_changed_at, o.order_changed, o.order_created), NOW()) AS age_days,
                         COUNT(r.review_id) AS reviews,
                         SUM(CASE WHEN r.review_publish = 1 THEN 1 ELSE 0 END) AS published_reviews,
                         SUM(CASE WHEN r.review_publish = 0 OR r.review_publish IS NULL THEN 1 ELSE 0 END) AS unpublished_reviews,
                         SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND r.review_publish_date < CURDATE() THEN 1 ELSE 0 END) AS overdue_unpublished,
                         SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND r.review_publish_date IS NULL THEN 1 ELSE 0 END) AS undated_unpublished,
                         SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND %s THEN 1 ELSE 0 END) AS blank_or_placeholder_text,
                         SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND %s THEN 1 ELSE 0 END) AS invalid_publication_accounts,
                         SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND %s THEN 1 ELSE 0 END) AS template_publication_account_names,
                         MIN(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND r.review_publish_date >= CURDATE()
                                  THEN r.review_publish_date ELSE NULL END) AS first_future_publish_date,
                         MIN(r.review_publish_date) AS min_publish_date,
                         MAX(r.review_publish_date) AS max_publish_date,
                         DATEDIFF(MAX(r.review_publish_date), MIN(r.review_publish_date)) AS publish_span_days
                  FROM orders o
                  JOIN order_statuses os ON os.order_status_id = o.order_status
                  LEFT JOIN companies c ON c.company_id = o.order_company
                  LEFT JOIN order_details od ON od.order_detail_order = o.order_id
                  LEFT JOIN reviews r ON r.review_order_details = od.order_detail_id
                  LEFT JOIN bots b ON b.bot_id = r.review_bot
                  WHERE os.order_status_title = 'Публикация'
                    AND (:managerScoped = 0 OR c.company_manager IN (:managerIds))
                  GROUP BY o.order_id, o.order_status_changed_at, o.order_changed, o.order_created
                ) x
                WHERE age_days >= :publicationStaleDays
                   OR overdue_unpublished > 0
                   OR undated_unpublished > 0
                   OR blank_or_placeholder_text > 0
                   OR invalid_publication_accounts > 0
                   OR template_publication_account_names > 0
                   OR first_future_publish_date > :publicationFirstFutureCutoffDate
                   OR publish_span_days > :publicationSpanLimitDays
                   OR max_publish_date > :publicationFutureCutoffDate
                   OR (reviews > 0 AND unpublished_reviews = 0)
                """.formatted(blankTextCondition(), invalidAccountCondition(), templateAccountCondition()), params, Long.class);

        List<IssueGroup> issues = List.of(
                issue("Пустой/шаблонный текст после статуса \"Новые\"", "SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND " + blankTextCondition() + " THEN 1 ELSE 0 END) > 0", params),
                issue("Нет назначенного/активного аккаунта с логином", "SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND " + invalidAccountCondition() + " THEN 1 ELSE 0 END) > 0", params),
                issue("Шаблонное имя аккаунта после выгула", "SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND " + templateAccountCondition() + " THEN 1 ELSE 0 END) > 0", params),
                issue("Дата публикации в прошлом, publish=false", "SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND r.review_publish_date < CURDATE() THEN 1 ELSE 0 END) > 0", params),
                issue("Все отзывы publish=true, заказ еще в Публикации", "COUNT(r.review_id) > 0 AND SUM(CASE WHEN r.review_publish = 0 OR r.review_publish IS NULL THEN 1 ELSE 0 END) = 0", params),
                issue("Первая будущая публикация слишком далеко", "MIN(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND r.review_publish_date >= CURDATE() THEN r.review_publish_date ELSE NULL END) > :publicationFirstFutureCutoffDate", params),
                issue("Даты растянуты больше лимита", "DATEDIFF(MAX(r.review_publish_date), MIN(r.review_publish_date)) > :publicationSpanLimitDays", params),
                issue("Отзывы без даты публикации", "SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND r.review_publish_date IS NULL THEN 1 ELSE 0 END) > 0", params)
        );

        return new PublicationReport(suspicious, issues);
    }

    private IssueGroup issue(String title, String havingCondition, MapSqlParameterSource params) {
        String fromGroupedOrders = """
                FROM orders o
                JOIN order_statuses os ON os.order_status_id = o.order_status
                LEFT JOIN companies c ON c.company_id = o.order_company
                LEFT JOIN order_details od ON od.order_detail_order = o.order_id
                LEFT JOIN reviews r ON r.review_order_details = od.order_detail_id
                LEFT JOIN bots b ON b.bot_id = r.review_bot
                WHERE os.order_status_title = 'Публикация'
                  AND (:managerScoped = 0 OR c.company_manager IN (:managerIds))
                GROUP BY o.order_id, c.company_id, c.company_title, o.order_status_changed_at, o.order_changed, o.order_created
                HAVING %s
                """.formatted(havingCondition);

        long count = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT o.order_id " + fromGroupedOrders + ") issue_orders",
                params, Long.class);
        List<OrderIssueSample> samples = jdbc.query("""
                SELECT o.order_id, c.company_title
                """ + fromGroupedOrders + """
                ORDER BY TIMESTAMPDIFF(DAY, COALESCE(o.order_status_changed_at, o.order_changed, o.order_created), NOW()) DESC,
                         o.order_id DESC
                LIMIT :sampleLimit
                """, params, (rs, rowNum) -> new OrderIssueSample(
                rs.getLong("order_id"),
                rs.getString("company_title")
        ));
        return new IssueGroup(title, count, samples);
    }

    private MapSqlParameterSource baseParams(List<Long> managerIds) {
        boolean managerScoped = managerIds != null && !managerIds.isEmpty();
        return new MapSqlParameterSource()
                .addValue("managerScoped", managerScoped ? 1 : 0)
                .addValue("managerIds", managerScoped ? managerIds : List.of(-1L))
                .addValue("publicationStaleDays", PUBLICATION_STALE_DAYS)
                .addValue("publicationSpanLimitDays", PUBLICATION_SPAN_LIMIT_DAYS)
                .addValue("publicationFutureCutoffDate", LocalDate.now().plusDays(PUBLICATION_FUTURE_LIMIT_DAYS))
                .addValue("publicationFirstFutureCutoffDate", LocalDate.now().plusDays(PUBLICATION_FIRST_FUTURE_LIMIT_DAYS))
                .addValue("sampleLimit", ISSUE_SAMPLE_LIMIT);
    }

    private String blankTextCondition() {
        return """
                (
                  r.review_text IS NULL
                  OR TRIM(r.review_text) = ''
                  OR LOWER(TRIM(r.review_text)) LIKE 'текст отзыва%%'
                  OR LOWER(TRIM(r.review_text)) LIKE 'нужно подставить%%'
                  OR LOWER(TRIM(r.review_text)) LIKE 'нужно подсавить%%'
                  OR LOWER(TRIM(r.review_text)) LIKE 'подставить текст%%'
                  OR LOWER(TRIM(r.review_text)) LIKE 'подсавить текст%%'
                )
                """;
    }

    private String invalidAccountCondition() {
        return """
                (
                  r.review_bot IS NULL
                  OR r.review_bot = 1
                  OR b.bot_id IS NULL
                  OR b.bot_active = 0
                  OR b.bot_login IS NULL
                  OR TRIM(b.bot_login) = ''
                )
                """;
    }

    private String templateAccountCondition() {
        return """
                r.review_vigul = 1
                AND LOWER(TRIM(b.bot_fio)) IN ('впишите имя фамилию', 'впиши имя фамилию', 'впишите фамилию имя', 'нет доступных аккаунтов')
                """;
    }

    private Set<Long> ownerRecipients() {
        Set<Long> chatIds = new LinkedHashSet<>();
        addRecipients(chatIds, userRepository.findAllOwners("ROLE_OWNER"));
        return chatIds;
    }

    private Map<Long, List<Long>> managerIdsByChat() {
        Map<Long, List<Long>> managerIdsByChat = new LinkedHashMap<>();
        for (Manager manager : managerRepository.findAllWithUserAndImage()) {
            User user = manager.getUser();
            Long chatId = user != null ? user.getTelegramChatId() : null;
            if (user == null || !user.isActive() || chatId == null || chatId == 0L || manager.getId() == null) {
                continue;
            }
            managerIdsByChat.computeIfAbsent(chatId, ignored -> new ArrayList<>()).add(manager.getId());
        }
        return managerIdsByChat;
    }

    private void addRecipients(Set<Long> chatIds, List<User> users) {
        if (users == null) {
            return;
        }
        users.stream()
                .map(User::getTelegramChatId)
                .filter(chatId -> chatId != null && chatId != 0L)
                .forEach(chatIds::add);
    }

    private String safeTitle(String companyTitle) {
        return companyTitle == null || companyTitle.isBlank() ? "Без названия" : companyTitle.trim();
    }

    private record PublicationReport(long suspicious, List<IssueGroup> issues) {
    }

    private record IssueGroup(String title, long count, List<OrderIssueSample> samples) {
    }

    private record OrderIssueSample(Long orderId, String companyTitle) {
    }
}

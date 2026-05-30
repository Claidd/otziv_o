package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.client_messages.dto.ClientMessageMaintenanceApplyResponse;
import com.hunt.otziv.client_messages.dto.ClientMessageMaintenancePreviewResponse;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.OrderStatusTransitionService;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ClientMessageMaintenancePreviewService {

    private static final List<String> CLOSED_ORDER_STATUSES = List.of("Оплачено", "Архив", "Бан");
    private static final List<String> OPEN_NEXT_ORDER_REQUEST_STATUSES = List.of("CREATED", "PENDING", "OPEN");
    private static final List<String> PAYMENT_WAITING_STATUSES = List.of("Выставлен счет", "Напоминание");
    private static final int PUBLICATION_STALE_DAYS = 60;
    private static final int PUBLICATION_SPAN_LIMIT_DAYS = 75;
    private static final int PUBLICATION_FUTURE_LIMIT_DAYS = 90;
    private static final int PUBLICATION_FIRST_FUTURE_LIMIT_DAYS = 21;

    private final NamedParameterJdbcTemplate jdbc;
    private final AppSettingService appSettingService;
    private final OrderRepository orderRepository;
    private final BadReviewTaskService badReviewTaskService;
    private final OrderStatusTransitionService orderStatusTransitionService;

    @Transactional(readOnly = true)
    public ClientMessageMaintenancePreviewResponse preview() {
        int paymentOverdueDays = appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_DAYS,
                60
        );
        ClientMessageMaintenancePreviewResponse.CompanyStatusPreview companyStatuses = companyStatuses();
        ClientMessageMaintenancePreviewResponse.PaymentStatusPreview paymentStatuses = paymentStatuses(paymentOverdueDays);
        ClientMessageMaintenancePreviewResponse.UnpaidRecoveryPreview unpaidRecovery = unpaidRecovery(paymentOverdueDays);
        ClientMessageMaintenancePreviewResponse.PublicationPreview publication = publication();
        ClientMessageMaintenancePreviewResponse.ArchiveOfferPreview archiveOffers = archiveOffers();

        return new ClientMessageMaintenancePreviewResponse(
                LocalDateTime.now(),
                paymentOverdueDays,
                PUBLICATION_STALE_DAYS,
                companyStatuses,
                paymentStatuses,
                unpaidRecovery,
                publication,
                archiveOffers,
                suggestedActions(companyStatuses, paymentStatuses, unpaidRecovery, publication, archiveOffers)
        );
    }

    @Transactional
    public ClientMessageMaintenanceApplyResponse applyCompanyStatuses() {
        long toWork = jdbc.update("""
                UPDATE companies c
                JOIN company_status current_status ON current_status.company_status_id = c.company_status
                JOIN company_status target_status ON target_status.status_title = 'В работе'
                SET c.company_status = target_status.company_status_id,
                    c.company_status_changed_at = NOW(6),
                    c.update_status = CURDATE()
                WHERE current_status.status_title NOT IN ('В работе', 'Бан')
                  AND EXISTS (
                    SELECT 1
                    FROM orders o
                    JOIN order_statuses os ON os.order_status_id = o.order_status
                    WHERE o.order_company = c.company_id
                      AND os.order_status_title NOT IN (:closedStatuses)
                  )
                """, baseParams());

        long toStop = jdbc.update("""
                UPDATE companies c
                JOIN company_status current_status ON current_status.company_status_id = c.company_status
                JOIN company_status target_status ON target_status.status_title = 'На стопе'
                SET c.company_status = target_status.company_status_id,
                    c.company_status_changed_at = NOW(6),
                    c.update_status = CURDATE()
                WHERE current_status.status_title = 'В работе'
                  AND NOT EXISTS (
                    SELECT 1
                    FROM orders o
                    JOIN order_statuses os ON os.order_status_id = o.order_status
                    WHERE o.order_company = c.company_id
                      AND os.order_status_title NOT IN (:closedStatuses)
                  )
                  AND NOT EXISTS (
                    SELECT 1
                    FROM next_order_requests norq
                    WHERE norq.company_id = c.company_id
                      AND norq.request_status IN (:openRequestStatuses)
                  )
                """, baseParams());

        long changed = toWork + toStop;
        return applyResponse(
                "company-statuses",
                changed,
                "Компании актуализированы: в работу " + toWork + ", на стоп " + toStop
        );
    }

    public ClientMessageMaintenanceApplyResponse applyPaymentOverdue() {
        int paymentOverdueDays = appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_DAYS,
                60
        );
        List<Long> orderIds = jdbc.queryForList("""
                SELECT o.order_id
                FROM orders o
                JOIN order_statuses os ON os.order_status_id = o.order_status
                WHERE os.order_status_title IN (:paymentStatuses)
                  AND TIMESTAMPDIFF(DAY, COALESCE(o.order_status_changed_at, o.order_changed, o.order_created), NOW()) >= :paymentOverdueDays
                ORDER BY o.order_status_changed_at ASC, o.order_id ASC
                LIMIT 200
                """, new MapSqlParameterSource()
                .addValue("paymentStatuses", PAYMENT_WAITING_STATUSES)
                .addValue("paymentOverdueDays", paymentOverdueDays), Long.class);

        long changed = 0;
        long failed = 0;
        for (Long orderId : orderIds) {
            try {
                if (Boolean.TRUE.equals(orderStatusTransitionService.changeStatusForOrder(orderId, "Не оплачено"))) {
                    changed++;
                }
            } catch (Exception e) {
                failed++;
            }
        }

        return applyResponse(
                "payment-overdue",
                changed,
                "Просроченные счета переведены в Не оплачено через бизнес-логику: " + changed
                        + (failed > 0 ? ", не удалось: " + failed : "")
        );
    }

    @Transactional
    public ClientMessageMaintenanceApplyResponse applyMissingBadTasks() {
        int paymentOverdueDays = appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_DAYS,
                60
        );
        List<Long> orderIds = jdbc.queryForList("""
                SELECT o.order_id
                FROM orders o
                JOIN order_statuses os ON os.order_status_id = o.order_status
                WHERE os.order_status_title = 'Не оплачено'
                  AND TIMESTAMPDIFF(DAY, COALESCE(o.order_status_changed_at, o.order_changed, o.order_created), NOW()) >= :paymentOverdueDays
                  AND NOT EXISTS (
                    SELECT 1
                    FROM bad_review_tasks t
                    WHERE t.bad_review_task_order = o.order_id
                  )
                  AND EXISTS (
                    SELECT 1
                    FROM order_details od
                    JOIN reviews r ON r.review_order_details = od.order_detail_id
                    WHERE od.order_detail_order = o.order_id
                      AND r.review_publish = 1
                  )
                ORDER BY o.order_status_changed_at ASC, o.order_id ASC
                LIMIT 300
                """, new MapSqlParameterSource("paymentOverdueDays", paymentOverdueDays), Long.class);

        long created = 0;
        for (Long orderId : orderIds) {
            Order order = orderRepository.findByIdForMutation(orderId).orElse(null);
            created += badReviewTaskService.createTasksForUnpaidOrder(order);
        }

        return applyResponse(
                "missing-bad-tasks",
                created,
                "Созданы недостающие плохие задачи для старых неоплат: " + created
        );
    }

    @Transactional
    public ClientMessageMaintenanceApplyResponse blockInvalidArchiveOffers() {
        long changed = jdbc.update("""
                UPDATE scheduled_client_message_state s
                SET s.state_status = 'DONE',
                    s.last_error_code = 'archive_reorder_blocked',
                    s.last_error_message = 'У компании уже есть активный заказ или открытая заявка на следующий заказ',
                    s.updated_at = NOW(6)
                WHERE s.scenario = 'ARCHIVE_REORDER_OFFER'
                  AND s.state_status = 'ACTIVE'
                  AND (
                    EXISTS (
                      SELECT 1
                      FROM orders o
                      JOIN order_statuses os ON os.order_status_id = o.order_status
                      WHERE o.order_company = s.company_id
                        AND os.order_status_title NOT IN (:closedStatuses)
                    )
                    OR EXISTS (
                      SELECT 1
                      FROM next_order_requests norq
                      WHERE norq.company_id = s.company_id
                        AND norq.request_status IN (:openRequestStatuses)
                    )
                  )
                """, baseParams());

        return applyResponse(
                "archive-offers",
                changed,
                "Заблокированы лишние архивные предложения: " + changed
        );
    }

    @Transactional
    public ClientMessageMaintenanceApplyResponse repairPublicationDates() {
        List<Long> orderIds = jdbc.queryForList("""
                SELECT order_id
                FROM (
                  SELECT o.order_id,
                         DATEDIFF(MAX(r.review_publish_date), MIN(r.review_publish_date)) AS publish_span_days,
                         SUM(CASE WHEN r.review_publish_date > :publicationFutureCutoffDate THEN 1 ELSE 0 END) AS too_far_future,
                         SUM(CASE WHEN r.review_publish_date IS NULL THEN 1 ELSE 0 END) AS without_publish_date
                  FROM orders o
                  JOIN order_statuses os ON os.order_status_id = o.order_status
                  JOIN order_details od ON od.order_detail_order = o.order_id
                  JOIN reviews r ON r.review_order_details = od.order_detail_id
                  WHERE os.order_status_title = 'Публикация'
                    AND r.review_publish = 0
                  GROUP BY o.order_id
                ) x
                WHERE too_far_future > 0 OR publish_span_days > :publicationSpanLimitDays OR without_publish_date > 0
                ORDER BY order_id ASC
                LIMIT 100
                """, new MapSqlParameterSource()
                .addValue("publicationFutureCutoffDate", Date.valueOf(LocalDate.now().plusDays(PUBLICATION_FUTURE_LIMIT_DAYS)))
                .addValue("publicationSpanLimitDays", PUBLICATION_SPAN_LIMIT_DAYS), Long.class);

        long updatedReviews = 0;
        for (Long orderId : orderIds) {
            updatedReviews += repairPublicationDatesForOrder(orderId);
        }

        return applyResponse(
                "publication-dates",
                updatedReviews,
                "Исправлены отсутствующие, явно дальние или растянутые даты публикации у неопубликованных отзывов: " + updatedReviews
        );
    }

    public ClientMessageMaintenanceApplyResponse completePublishedPublicationOrders() {
        List<Long> orderIds = jdbc.queryForList("""
                SELECT order_id
                FROM (
                  SELECT o.order_id,
                         COUNT(r.review_id) AS reviews,
                         SUM(CASE WHEN r.review_publish = 0 OR r.review_publish IS NULL THEN 1 ELSE 0 END) AS unpublished_reviews
                  FROM orders o
                  JOIN order_statuses os ON os.order_status_id = o.order_status
                  LEFT JOIN order_details od ON od.order_detail_order = o.order_id
                  LEFT JOIN reviews r ON r.review_order_details = od.order_detail_id
                  WHERE os.order_status_title = 'Публикация'
                  GROUP BY o.order_id
                ) x
                WHERE reviews > 0 AND unpublished_reviews = 0
                ORDER BY order_id ASC
                LIMIT 200
                """, new MapSqlParameterSource(), Long.class);

        long changed = 0;
        long failed = 0;
        for (Long orderId : orderIds) {
            try {
                if (Boolean.TRUE.equals(orderStatusTransitionService.changeStatusForOrder(orderId, "Опубликовано"))) {
                    changed++;
                }
            } catch (Exception e) {
                failed++;
            }
        }

        return applyResponse(
                "publication-completed",
                changed,
                "Завершенные публикации переведены в Опубликовано через бизнес-логику: " + changed
                        + (failed > 0 ? ", не удалось: " + failed : "")
        );
    }

    private long repairPublicationDatesForOrder(Long orderId) {
        List<Long> reviewIds = jdbc.queryForList("""
                SELECT r.review_id
                FROM reviews r
                JOIN order_details od ON od.order_detail_id = r.review_order_details
                WHERE od.order_detail_order = :orderId
                  AND r.review_publish = 0
                ORDER BY COALESCE(r.review_publish_date, CURDATE()), r.review_id
                """, new MapSqlParameterSource("orderId", orderId), Long.class);
        if (reviewIds.isEmpty()) {
            return 0;
        }

        List<LocalDate> dates = compactPublicationDates(LocalDate.now(), reviewIds.size());
        if (dates.size() < reviewIds.size()) {
            return 0;
        }

        long updated = 0;
        for (int i = 0; i < reviewIds.size(); i++) {
            updated += jdbc.update("""
                    UPDATE reviews
                    SET review_publish_date = :publishDate
                    WHERE review_id = :reviewId
                      AND review_publish = 0
                    """, new MapSqlParameterSource()
                    .addValue("publishDate", dates.get(i))
                    .addValue("reviewId", reviewIds.get(i)));
        }
        return updated;
    }

    private List<LocalDate> compactPublicationDates(LocalDate startDate, int count) {
        if (count <= 0) {
            return List.of();
        }
        int desiredDays = Math.max(28, (int) Math.ceil(count * 7.0 / 6.0) + 3);
        int maxDays = 60;
        LocalDate endDate = startDate.plusDays(Math.min(desiredDays, maxDays));
        List<LocalDate> candidates = publicationDateCandidates(startDate, endDate);
        while (candidates.size() < count && endDate.isBefore(startDate.plusDays(maxDays))) {
            endDate = endDate.plusWeeks(1);
            if (endDate.isAfter(startDate.plusDays(maxDays))) {
                endDate = startDate.plusDays(maxDays);
            }
            candidates = publicationDateCandidates(startDate, endDate);
        }
        if (candidates.size() < count) {
            return List.of();
        }

        List<LocalDate> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = (int) ((long) i * candidates.size() / count);
            result.add(candidates.get(index));
        }
        return result;
    }

    private List<LocalDate> publicationDateCandidates(LocalDate startDate, LocalDate endDate) {
        List<LocalDate> candidates = new ArrayList<>();
        LocalDate date = startDate;
        while (!date.isAfter(endDate)) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY) {
                candidates.add(date);
            }
            date = date.plusDays(1);
        }
        return candidates;
    }

    private ClientMessageMaintenanceApplyResponse applyResponse(String action, long changed, String message) {
        return new ClientMessageMaintenanceApplyResponse(
                action,
                changed,
                message,
                LocalDateTime.now(),
                preview()
        );
    }

    private ClientMessageMaintenancePreviewResponse.CompanyStatusPreview companyStatuses() {
        MapSqlParameterSource params = baseParams();
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT
                  SUM(CASE WHEN current_status NOT IN ('В работе', 'Бан') AND has_active_order = 1 THEN 1 ELSE 0 END) AS should_move_to_work,
                  SUM(CASE WHEN current_status = 'На стопе' AND has_active_order = 1 THEN 1 ELSE 0 END) AS stopped_with_active_orders,
                  SUM(CASE WHEN current_status = 'Новый заказ' AND has_active_order = 1 THEN 1 ELSE 0 END) AS new_order_with_active_orders,
                  SUM(CASE WHEN current_status = 'Бан' AND has_active_order = 1 THEN 1 ELSE 0 END) AS banned_with_active_orders,
                  SUM(CASE WHEN current_status = 'В работе' AND has_active_order = 0 AND has_open_request = 0 THEN 1 ELSE 0 END) AS work_without_active_orders,
                  SUM(CASE WHEN current_status = 'Новый заказ' AND has_active_order = 0 AND has_open_request = 0 THEN 1 ELSE 0 END) AS new_order_without_active_orders
                FROM (
                  SELECT c.company_id,
                         cs.status_title AS current_status,
                         EXISTS (
                           SELECT 1
                           FROM orders o
                           JOIN order_statuses os ON os.order_status_id = o.order_status
                           WHERE o.order_company = c.company_id
                             AND os.order_status_title NOT IN (:closedStatuses)
                         ) AS has_active_order,
                         EXISTS (
                           SELECT 1
                           FROM next_order_requests norq
                           WHERE norq.company_id = c.company_id
                             AND norq.request_status IN (:openRequestStatuses)
                         ) AS has_open_request
                  FROM companies c
                  LEFT JOIN company_status cs ON cs.company_status_id = c.company_status
                ) x
                """, params);

        List<ClientMessageMaintenancePreviewResponse.CompanyStatusSample> samplesToWork = jdbc.query("""
                SELECT c.company_id,
                       c.company_title,
                       cs.status_title AS current_status,
                       COUNT(DISTINCT o.order_id) AS active_orders,
                       GROUP_CONCAT(DISTINCT os.order_status_title ORDER BY os.order_status_title SEPARATOR ', ') AS active_order_statuses
                FROM companies c
                LEFT JOIN company_status cs ON cs.company_status_id = c.company_status
                JOIN orders o ON o.order_company = c.company_id
                JOIN order_statuses os ON os.order_status_id = o.order_status
                WHERE cs.status_title NOT IN ('В работе', 'Бан')
                  AND os.order_status_title NOT IN (:closedStatuses)
                GROUP BY c.company_id, c.company_title, cs.status_title
                ORDER BY c.company_id
                LIMIT 10
                """, params, (rs, rowNum) -> new ClientMessageMaintenancePreviewResponse.CompanyStatusSample(
                rs.getLong("company_id"),
                rs.getString("company_title"),
                rs.getString("current_status"),
                rs.getLong("active_orders"),
                rs.getString("active_order_statuses")
        ));

        List<ClientMessageMaintenancePreviewResponse.CompanyStatusSample> samplesToStop = jdbc.query("""
                SELECT c.company_id,
                       c.company_title,
                       cs.status_title AS current_status,
                       0 AS active_orders,
                       '' AS active_order_statuses
                FROM companies c
                LEFT JOIN company_status cs ON cs.company_status_id = c.company_status
                WHERE cs.status_title = 'В работе'
                  AND NOT EXISTS (
                    SELECT 1
                    FROM orders o
                    JOIN order_statuses os ON os.order_status_id = o.order_status
                    WHERE o.order_company = c.company_id
                      AND os.order_status_title NOT IN (:closedStatuses)
                  )
                  AND NOT EXISTS (
                    SELECT 1
                    FROM next_order_requests norq
                    WHERE norq.company_id = c.company_id
                      AND norq.request_status IN (:openRequestStatuses)
                  )
                ORDER BY c.company_id
                LIMIT 10
                """, params, (rs, rowNum) -> new ClientMessageMaintenancePreviewResponse.CompanyStatusSample(
                rs.getLong("company_id"),
                rs.getString("company_title"),
                rs.getString("current_status"),
                rs.getLong("active_orders"),
                rs.getString("active_order_statuses")
        ));

        List<ClientMessageMaintenancePreviewResponse.CompanyStatusSample> samplesBannedWithActiveOrders = jdbc.query("""
                SELECT c.company_id,
                       c.company_title,
                       cs.status_title AS current_status,
                       COUNT(DISTINCT o.order_id) AS active_orders,
                       GROUP_CONCAT(DISTINCT os.order_status_title ORDER BY os.order_status_title SEPARATOR ', ') AS active_order_statuses
                FROM companies c
                LEFT JOIN company_status cs ON cs.company_status_id = c.company_status
                JOIN orders o ON o.order_company = c.company_id
                JOIN order_statuses os ON os.order_status_id = o.order_status
                WHERE cs.status_title = 'Бан'
                  AND os.order_status_title NOT IN (:closedStatuses)
                GROUP BY c.company_id, c.company_title, cs.status_title
                ORDER BY c.company_id
                LIMIT 10
                """, params, (rs, rowNum) -> new ClientMessageMaintenancePreviewResponse.CompanyStatusSample(
                rs.getLong("company_id"),
                rs.getString("company_title"),
                rs.getString("current_status"),
                rs.getLong("active_orders"),
                rs.getString("active_order_statuses")
        ));

        return new ClientMessageMaintenancePreviewResponse.CompanyStatusPreview(
                number(row, "should_move_to_work"),
                number(row, "stopped_with_active_orders"),
                number(row, "new_order_with_active_orders"),
                number(row, "banned_with_active_orders"),
                number(row, "work_without_active_orders"),
                number(row, "new_order_without_active_orders"),
                samplesToWork,
                samplesToStop,
                samplesBannedWithActiveOrders
        );
    }

    private ClientMessageMaintenancePreviewResponse.PaymentStatusPreview paymentStatuses(int paymentOverdueDays) {
        MapSqlParameterSource params = baseParams()
                .addValue("paymentStatuses", PAYMENT_WAITING_STATUSES)
                .addValue("paymentOverdueDays", paymentOverdueDays);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       SUM(age_days >= :paymentOverdueDays) AS older_than_threshold,
                       SUM(age_days >= 30) AS older_than_thirty_days,
                       SUM(has_active_state = 0) AS without_active_state
                FROM (
                  SELECT o.order_id,
                         TIMESTAMPDIFF(DAY, COALESCE(o.order_status_changed_at, o.order_changed, o.order_created), NOW()) AS age_days,
                         EXISTS (
                           SELECT 1
                           FROM scheduled_client_message_state s
                           WHERE s.order_id = o.order_id
                             AND s.state_status = 'ACTIVE'
                             AND s.scenario IN ('PAYMENT_REMINDER', 'PAYMENT_OVERDUE_ESCALATION', 'PAYMENT_INVOICE_RETRY')
                         ) AS has_active_state
                  FROM orders o
                  JOIN order_statuses os ON os.order_status_id = o.order_status
                  WHERE os.order_status_title IN (:paymentStatuses)
                ) x
                """, params);

        List<ClientMessageMaintenancePreviewResponse.OrderRiskSample> samples = jdbc.query("""
                SELECT o.order_id,
                       c.company_id,
                       c.company_title,
                       os.order_status_title AS status_title,
                       TIMESTAMPDIFF(DAY, COALESCE(o.order_status_changed_at, o.order_changed, o.order_created), NOW()) AS age_days,
                       o.order_amount,
                       o.order_sum,
                       0 AS reviews,
                       0 AS published_reviews,
                       0 AS bad_tasks,
                       0 AS pending_bad_tasks,
                       NULL AS max_publish_date,
                       'Счет или напоминание старше порога просрочки' AS reason
                FROM orders o
                JOIN order_statuses os ON os.order_status_id = o.order_status
                LEFT JOIN companies c ON c.company_id = o.order_company
                WHERE os.order_status_title IN (:paymentStatuses)
                  AND TIMESTAMPDIFF(DAY, COALESCE(o.order_status_changed_at, o.order_changed, o.order_created), NOW()) >= :paymentOverdueDays
                ORDER BY age_days DESC, o.order_id DESC
                LIMIT 10
                """, params, this::orderRiskSample);

        return new ClientMessageMaintenancePreviewResponse.PaymentStatusPreview(
                number(row, "total"),
                number(row, "older_than_threshold"),
                number(row, "older_than_thirty_days"),
                number(row, "without_active_state"),
                samples
        );
    }

    private ClientMessageMaintenancePreviewResponse.UnpaidRecoveryPreview unpaidRecovery(int paymentOverdueDays) {
        MapSqlParameterSource params = baseParams().addValue("paymentOverdueDays", paymentOverdueDays);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       SUM(age_days >= :paymentOverdueDays) AS older_than_threshold,
                       SUM(age_days >= 300) AS older_than_three_hundred_days,
                       SUM(bad_tasks = 0) AS without_bad_tasks,
                       SUM(bad_tasks = 0 AND published_reviews > 0) AS can_create_bad_tasks,
                       SUM(bad_tasks = 0 AND published_reviews = 0) AS without_published_reviews,
                       SUM(bad_tasks > 0 AND pending_bad_tasks > 0) AS with_pending_bad_tasks,
                       SUM(bad_tasks > 0 AND pending_bad_tasks = 0 AND done_bad_tasks > 0) AS all_bad_tasks_done
                FROM (
                  SELECT o.order_id,
                         TIMESTAMPDIFF(DAY, COALESCE(o.order_status_changed_at, o.order_changed, o.order_created), NOW()) AS age_days,
                         COUNT(DISTINCT t.bad_review_task_id) AS bad_tasks,
                         SUM(CASE WHEN t.bad_review_task_status = 'NEW' THEN 1 ELSE 0 END) AS pending_bad_tasks,
                         SUM(CASE WHEN t.bad_review_task_status = 'DONE' THEN 1 ELSE 0 END) AS done_bad_tasks,
                         SUM(CASE WHEN r.review_publish = 1 THEN 1 ELSE 0 END) AS published_reviews
                  FROM orders o
                  JOIN order_statuses os ON os.order_status_id = o.order_status
                  LEFT JOIN bad_review_tasks t ON t.bad_review_task_order = o.order_id
                  LEFT JOIN order_details od ON od.order_detail_order = o.order_id
                  LEFT JOIN reviews r ON r.review_order_details = od.order_detail_id
                  WHERE os.order_status_title = 'Не оплачено'
                  GROUP BY o.order_id, o.order_status_changed_at, o.order_changed, o.order_created
                ) x
                """, params);

        List<ClientMessageMaintenancePreviewResponse.OrderRiskSample> samples = jdbc.query("""
                SELECT o.order_id,
                       c.company_id,
                       c.company_title,
                       os.order_status_title AS status_title,
                       TIMESTAMPDIFF(DAY, COALESCE(o.order_status_changed_at, o.order_changed, o.order_created), NOW()) AS age_days,
                       o.order_amount,
                       o.order_sum,
                       COUNT(DISTINCT r.review_id) AS reviews,
                       SUM(CASE WHEN r.review_publish = 1 THEN 1 ELSE 0 END) AS published_reviews,
                       COUNT(DISTINCT t.bad_review_task_id) AS bad_tasks,
                       SUM(CASE WHEN t.bad_review_task_status = 'NEW' THEN 1 ELSE 0 END) AS pending_bad_tasks,
                       NULL AS max_publish_date,
                       CASE
                         WHEN COUNT(DISTINCT t.bad_review_task_id) = 0 AND SUM(CASE WHEN r.review_publish = 1 THEN 1 ELSE 0 END) > 0
                           THEN 'Можно создать недостающие плохие задачи через сервис'
                         WHEN COUNT(DISTINCT t.bad_review_task_id) = 0
                           THEN 'Нет плохих задач и нет опубликованных отзывов'
                         WHEN SUM(CASE WHEN t.bad_review_task_status = 'NEW' THEN 1 ELSE 0 END) > 0
                           THEN 'Плохие задачи уже в работе'
                         ELSE 'Плохие задачи завершены, нужна проверка финального счета'
                       END AS reason
                FROM orders o
                JOIN order_statuses os ON os.order_status_id = o.order_status
                LEFT JOIN companies c ON c.company_id = o.order_company
                LEFT JOIN bad_review_tasks t ON t.bad_review_task_order = o.order_id
                LEFT JOIN order_details od ON od.order_detail_order = o.order_id
                LEFT JOIN reviews r ON r.review_order_details = od.order_detail_id
                WHERE os.order_status_title = 'Не оплачено'
                  AND TIMESTAMPDIFF(DAY, COALESCE(o.order_status_changed_at, o.order_changed, o.order_created), NOW()) >= :paymentOverdueDays
                GROUP BY o.order_id, c.company_id, c.company_title, os.order_status_title, o.order_status_changed_at, o.order_changed, o.order_created, o.order_amount, o.order_sum
                ORDER BY age_days DESC, o.order_id DESC
                LIMIT 15
                """, params, this::orderRiskSample);

        return new ClientMessageMaintenancePreviewResponse.UnpaidRecoveryPreview(
                number(row, "total"),
                number(row, "older_than_threshold"),
                number(row, "older_than_three_hundred_days"),
                number(row, "without_bad_tasks"),
                number(row, "can_create_bad_tasks"),
                number(row, "without_published_reviews"),
                number(row, "with_pending_bad_tasks"),
                number(row, "all_bad_tasks_done"),
                samples
        );
    }

    private ClientMessageMaintenancePreviewResponse.PublicationPreview publication() {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("publicationStaleDays", PUBLICATION_STALE_DAYS)
                .addValue("publicationSpanLimitDays", PUBLICATION_SPAN_LIMIT_DAYS)
                .addValue("publicationFutureCutoffDate", Date.valueOf(LocalDate.now().plusDays(PUBLICATION_FUTURE_LIMIT_DAYS)))
                .addValue("publicationFirstFutureCutoffDate", Date.valueOf(LocalDate.now().plusDays(PUBLICATION_FIRST_FUTURE_LIMIT_DAYS)));
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       SUM(
                         age_days >= :publicationStaleDays
                         OR overdue_unpublished > 0
                         OR undated_unpublished > 0
                         OR blank_or_placeholder_text > 0
                         OR invalid_publication_accounts > 0
                         OR template_publication_account_names > 0
                         OR first_future_publish_date > :publicationFirstFutureCutoffDate
                         OR publish_span_days > :publicationSpanLimitDays
                         OR max_publish_date > :publicationFutureCutoffDate
                         OR (reviews > 0 AND unpublished_reviews = 0)
                       ) AS suspicious,
                       SUM(age_days >= :publicationStaleDays) AS older_than_stale_days,
                       SUM(overdue_unpublished > 0) AS overdue_unpublished,
                       SUM(undated_unpublished > 0) AS undated_unpublished,
                       SUM(blank_or_placeholder_text > 0) AS blank_or_placeholder_text,
                       SUM(invalid_publication_accounts > 0) AS invalid_publication_accounts,
                       SUM(template_publication_account_names > 0) AS template_publication_account_names,
                       SUM(first_future_publish_date > :publicationFirstFutureCutoffDate) AS first_future_publication_too_far,
                       SUM(publish_span_days > :publicationSpanLimitDays) AS long_publish_span,
                       SUM(max_publish_date > :publicationFutureCutoffDate) AS far_future_publish_date,
                       SUM(reviews > 0 AND unpublished_reviews = 0) AS old_all_reviews_published,
                       SUM(age_days >= :publicationStaleDays AND max_publish_date > :publicationFutureCutoffDate) AS old_with_future_publish_date
                FROM (
                  SELECT o.order_id,
                         TIMESTAMPDIFF(DAY, COALESCE(o.order_status_changed_at, o.order_changed, o.order_created), NOW()) AS age_days,
                         COUNT(r.review_id) AS reviews,
                         SUM(CASE WHEN r.review_publish = 0 OR r.review_publish IS NULL THEN 1 ELSE 0 END) AS unpublished_reviews,
                         SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND r.review_publish_date < CURDATE() THEN 1 ELSE 0 END) AS overdue_unpublished,
                         SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND r.review_publish_date IS NULL THEN 1 ELSE 0 END) AS undated_unpublished,
                         SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND (
                               r.review_text IS NULL
                               OR TRIM(r.review_text) = ''
                               OR LOWER(TRIM(r.review_text)) LIKE 'текст отзыва%'
                               OR LOWER(TRIM(r.review_text)) LIKE 'нужно подставить%'
                               OR LOWER(TRIM(r.review_text)) LIKE 'нужно подсавить%'
                               OR LOWER(TRIM(r.review_text)) LIKE 'подставить текст%'
                               OR LOWER(TRIM(r.review_text)) LIKE 'подсавить текст%'
                             ) THEN 1 ELSE 0 END) AS blank_or_placeholder_text,
                         SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND (
                               r.review_bot IS NULL
                               OR r.review_bot = 1
                               OR b.bot_id IS NULL
                               OR b.bot_active = 0
                               OR b.bot_login IS NULL
                               OR TRIM(b.bot_login) = ''
                             ) THEN 1 ELSE 0 END) AS invalid_publication_accounts,
                         SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL)
                                      AND r.review_vigul = 1
                                      AND LOWER(TRIM(b.bot_fio)) IN ('впишите имя фамилию', 'впиши имя фамилию', 'впишите фамилию имя', 'нет доступных аккаунтов')
                                  THEN 1 ELSE 0 END) AS template_publication_account_names,
                         MIN(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL)
                                      AND r.review_publish_date >= CURDATE()
                                  THEN r.review_publish_date ELSE NULL END) AS first_future_publish_date,
                         MIN(r.review_publish_date) AS min_publish_date,
                         MAX(r.review_publish_date) AS max_publish_date,
                         DATEDIFF(MAX(r.review_publish_date), MIN(r.review_publish_date)) AS publish_span_days
                  FROM orders o
                  JOIN order_statuses os ON os.order_status_id = o.order_status
                  LEFT JOIN order_details od ON od.order_detail_order = o.order_id
                  LEFT JOIN reviews r ON r.review_order_details = od.order_detail_id
                  LEFT JOIN bots b ON b.bot_id = r.review_bot
                  WHERE os.order_status_title = 'Публикация'
                  GROUP BY o.order_id, o.order_status_changed_at, o.order_changed, o.order_created
                ) x
                """, params);

        List<ClientMessageMaintenancePreviewResponse.OrderRiskSample> samples = jdbc.query("""
                SELECT o.order_id,
                       c.company_id,
                       c.company_title,
                       os.order_status_title AS status_title,
                       TIMESTAMPDIFF(DAY, COALESCE(o.order_status_changed_at, o.order_changed, o.order_created), NOW()) AS age_days,
                       o.order_amount,
                       o.order_sum,
                       COUNT(r.review_id) AS reviews,
                       SUM(CASE WHEN r.review_publish = 1 THEN 1 ELSE 0 END) AS published_reviews,
                       0 AS bad_tasks,
                       0 AS pending_bad_tasks,
                       MAX(r.review_publish_date) AS max_publish_date,
                       CASE
                         WHEN SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND (
                               r.review_text IS NULL
                               OR TRIM(r.review_text) = ''
                               OR LOWER(TRIM(r.review_text)) LIKE 'текст отзыва%'
                               OR LOWER(TRIM(r.review_text)) LIKE 'нужно подставить%'
                               OR LOWER(TRIM(r.review_text)) LIKE 'нужно подсавить%'
                               OR LOWER(TRIM(r.review_text)) LIKE 'подставить текст%'
                               OR LOWER(TRIM(r.review_text)) LIKE 'подсавить текст%'
                             ) THEN 1 ELSE 0 END) > 0
                           THEN 'Есть пустой или шаблонный текст отзыва'
                         WHEN SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND (
                               r.review_bot IS NULL
                               OR r.review_bot = 1
                               OR b.bot_id IS NULL
                               OR b.bot_active = 0
                               OR b.bot_login IS NULL
                               OR TRIM(b.bot_login) = ''
                             ) THEN 1 ELSE 0 END) > 0
                           THEN 'Есть отзыв без реального аккаунта публикации'
                         WHEN SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL)
                                           AND r.review_vigul = 1
                                           AND LOWER(TRIM(b.bot_fio)) IN ('впишите имя фамилию', 'впиши имя фамилию', 'впишите фамилию имя', 'нет доступных аккаунтов')
                                      THEN 1 ELSE 0 END) > 0
                           THEN 'Есть аккаунт с шаблонным именем после выгула'
                         WHEN MIN(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL)
                                           AND r.review_publish_date >= CURDATE()
                                      THEN r.review_publish_date ELSE NULL END) > :publicationFirstFutureCutoffDate
                           THEN 'Первая будущая публикация назначена слишком далеко'
                         WHEN MAX(r.review_publish_date) > :publicationFutureCutoffDate
                           THEN 'Есть подозрительно дальняя дата публикации'
                         WHEN SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND r.review_publish_date IS NULL THEN 1 ELSE 0 END) > 0
                           THEN 'Есть неопубликованные отзывы без даты публикации'
                         WHEN SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND r.review_publish_date < CURDATE() THEN 1 ELSE 0 END) > 0
                           THEN 'Есть неопубликованные отзывы с прошедшей датой'
                         WHEN DATEDIFF(MAX(r.review_publish_date), MIN(r.review_publish_date)) > :publicationSpanLimitDays
                           THEN 'Публикация растянута слишком далеко'
                         WHEN COUNT(r.review_id) > 0 AND SUM(CASE WHEN r.review_publish = 0 OR r.review_publish IS NULL THEN 1 ELSE 0 END) = 0
                           THEN 'Все отзывы опубликованы, статус мог застрять'
                         ELSE 'Долгая публикация, нужна ручная проверка графика'
                       END AS reason
                FROM orders o
                JOIN order_statuses os ON os.order_status_id = o.order_status
                LEFT JOIN companies c ON c.company_id = o.order_company
                LEFT JOIN order_details od ON od.order_detail_order = o.order_id
                LEFT JOIN reviews r ON r.review_order_details = od.order_detail_id
                LEFT JOIN bots b ON b.bot_id = r.review_bot
                WHERE os.order_status_title = 'Публикация'
                GROUP BY o.order_id, c.company_id, c.company_title, os.order_status_title, o.order_status_changed_at, o.order_changed, o.order_created, o.order_amount, o.order_sum
                HAVING age_days >= :publicationStaleDays
                    OR SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND r.review_publish_date < CURDATE() THEN 1 ELSE 0 END) > 0
                    OR SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND r.review_publish_date IS NULL THEN 1 ELSE 0 END) > 0
                    OR SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND (
                         r.review_text IS NULL
                         OR TRIM(r.review_text) = ''
                         OR LOWER(TRIM(r.review_text)) LIKE 'текст отзыва%'
                         OR LOWER(TRIM(r.review_text)) LIKE 'нужно подставить%'
                         OR LOWER(TRIM(r.review_text)) LIKE 'нужно подсавить%'
                         OR LOWER(TRIM(r.review_text)) LIKE 'подставить текст%'
                         OR LOWER(TRIM(r.review_text)) LIKE 'подсавить текст%'
                       ) THEN 1 ELSE 0 END) > 0
                    OR SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL) AND (
                         r.review_bot IS NULL
                         OR r.review_bot = 1
                         OR b.bot_id IS NULL
                        OR b.bot_active = 0
                        OR b.bot_login IS NULL
                        OR TRIM(b.bot_login) = ''
                       ) THEN 1 ELSE 0 END) > 0
                    OR SUM(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL)
                                   AND r.review_vigul = 1
                                   AND LOWER(TRIM(b.bot_fio)) IN ('впишите имя фамилию', 'впиши имя фамилию', 'впишите фамилию имя', 'нет доступных аккаунтов')
                              THEN 1 ELSE 0 END) > 0
                    OR MIN(CASE WHEN (r.review_publish = 0 OR r.review_publish IS NULL)
                                   AND r.review_publish_date >= CURDATE()
                                THEN r.review_publish_date ELSE NULL END) > :publicationFirstFutureCutoffDate
                    OR DATEDIFF(MAX(r.review_publish_date), MIN(r.review_publish_date)) > :publicationSpanLimitDays
                    OR MAX(r.review_publish_date) > :publicationFutureCutoffDate
                    OR (COUNT(r.review_id) > 0 AND SUM(CASE WHEN r.review_publish = 0 OR r.review_publish IS NULL THEN 1 ELSE 0 END) = 0)
                ORDER BY age_days DESC, o.order_id DESC
                LIMIT 20
                """, params, this::orderRiskSample);

        return new ClientMessageMaintenancePreviewResponse.PublicationPreview(
                number(row, "total"),
                number(row, "suspicious"),
                number(row, "older_than_stale_days"),
                number(row, "overdue_unpublished"),
                number(row, "undated_unpublished"),
                number(row, "blank_or_placeholder_text"),
                number(row, "invalid_publication_accounts"),
                number(row, "template_publication_account_names"),
                number(row, "first_future_publication_too_far"),
                number(row, "long_publish_span"),
                number(row, "far_future_publish_date"),
                number(row, "old_all_reviews_published"),
                number(row, "old_with_future_publish_date"),
                samples
        );
    }

    private ClientMessageMaintenancePreviewResponse.ArchiveOfferPreview archiveOffers() {
        MapSqlParameterSource params = baseParams();
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(*) AS active_states,
                       SUM(s.next_attempt_at <= NOW()) AS due_now,
                       SUM(EXISTS (
                         SELECT 1
                         FROM orders o
                         JOIN order_statuses os ON os.order_status_id = o.order_status
                         WHERE o.order_company = s.company_id
                           AND os.order_status_title NOT IN (:closedStatuses)
                       )) AS blocked_by_active_orders,
                       SUM(EXISTS (
                         SELECT 1
                         FROM next_order_requests norq
                         WHERE norq.company_id = s.company_id
                           AND norq.request_status IN (:openRequestStatuses)
                       )) AS blocked_by_open_next_request
                FROM scheduled_client_message_state s
                WHERE s.scenario = 'ARCHIVE_REORDER_OFFER'
                  AND s.state_status = 'ACTIVE'
                """, params);

        return new ClientMessageMaintenancePreviewResponse.ArchiveOfferPreview(
                number(row, "active_states"),
                number(row, "due_now"),
                number(row, "blocked_by_active_orders"),
                number(row, "blocked_by_open_next_request")
        );
    }

    private List<ClientMessageMaintenancePreviewResponse.ActionItem> suggestedActions(
            ClientMessageMaintenancePreviewResponse.CompanyStatusPreview companyStatuses,
            ClientMessageMaintenancePreviewResponse.PaymentStatusPreview paymentStatuses,
            ClientMessageMaintenancePreviewResponse.UnpaidRecoveryPreview unpaidRecovery,
            ClientMessageMaintenancePreviewResponse.PublicationPreview publication,
            ClientMessageMaintenancePreviewResponse.ArchiveOfferPreview archiveOffers
    ) {
        List<ClientMessageMaintenancePreviewResponse.ActionItem> actions = new ArrayList<>();
        addAction(actions, "warning", "Вернуть компании в работу",
                "Компании стоят не в рабочем статусе, но у них есть активные заказы.", companyStatuses.shouldMoveToWork());
        addAction(actions, "safe", "Поставить на стоп",
                "Компании в работе без активных заказов и открытых заявок можно вернуть на стоп.", companyStatuses.workWithoutActiveOrders());
        addAction(actions, "warning", "Проверить статус Новый заказ",
                "Компании без активных заказов, но в статусе Новый заказ лучше не менять без просмотра менеджером.", companyStatuses.newOrderWithoutActiveOrders());
        addAction(actions, "danger", "Не трогать Бан автоматически",
                "Есть компании в бане с активными заказами. Их лучше разобрать вручную.", companyStatuses.bannedWithActiveOrders());
        addAction(actions, "warning", "Перевести просроченные счета через сервис",
                "Счета старше порога нужно переводить в Не оплачено только через бизнес-логику, чтобы создались плохие задачи.", paymentStatuses.invoiceOrReminderOlderThanThreshold());
        addAction(actions, "warning", "Создать недостающие плохие задачи",
                "Старые неоплаченные заказы с опубликованными отзывами можно восстановить через сервис создания плохих задач.", unpaidRecovery.canCreateBadTasks());
        addAction(actions, "danger", "Проверить неоплаченные без опубликованных отзывов",
                "Для них плохие задачи создать не из чего, нужен ручной разбор.", unpaidRecovery.withoutPublishedReviews());
        addAction(actions, "warning", "Проверить старую публикацию",
                "Заказы в публикации с прошедшими датами, отсутствующими датами, дальним графиком или долгим статусом показываем как потенциально зависшие.", publication.suspicious());
        addAction(actions, "danger", "Заполнить тексты в публикации",
                "В Публикации есть отзывы с пустым или шаблонным текстом. Их нельзя публиковать без исправления.",
                publication.blankOrPlaceholderText());
        addAction(actions, "warning", "Назначить аккаунты публикации",
                "В Публикации есть отзывы без назначенного, активного аккаунта с логином. Заказ может оставаться в публикации, но сам отзыв публиковать нельзя.",
                publication.invalidPublicationAccounts());
        addAction(actions, "warning", "Заменить шаблонные имена аккаунтов",
                "В Публикации есть аккаунты с именем-заглушкой после выгула. На ранних стадиях это нормально, но перед публикацией имя должно быть заменено.",
                publication.templatePublicationAccountNames());
        addAction(actions, "warning", "Проверить первый будущий выход",
                "Есть заказы, где ближайшая будущая публикация стоит слишком далеко от сегодняшней даты.",
                publication.firstFuturePublicationTooFar());
        addAction(actions, "safe", "Завершить опубликованные публикации",
                "Заказы в статусе Публикация, где все отзывы уже опубликованы, можно перевести в Опубликовано через бизнес-логику финального счета.",
                publication.oldAllReviewsPublished());
        addAction(actions, "warning", "Заблокировать лишние архивные предложения",
                "Архивные кандидаты с активным заказом или открытой заявкой не должны получать предложение нового заказа.",
                archiveOffers.blockedByActiveOrders() + archiveOffers.blockedByOpenNextRequest());
        return actions;
    }

    private void addAction(
            List<ClientMessageMaintenancePreviewResponse.ActionItem> actions,
            String tone,
            String title,
            String description,
            long count
    ) {
        if (count <= 0) {
            return;
        }
        actions.add(new ClientMessageMaintenancePreviewResponse.ActionItem(tone, title, description, count));
    }

    private MapSqlParameterSource baseParams() {
        return new MapSqlParameterSource()
                .addValue("closedStatuses", CLOSED_ORDER_STATUSES)
                .addValue("openRequestStatuses", OPEN_NEXT_ORDER_REQUEST_STATUSES);
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private ClientMessageMaintenancePreviewResponse.OrderRiskSample orderRiskSample(ResultSet rs, int rowNum) throws SQLException {
        Date maxPublishDate = rs.getDate("max_publish_date");
        BigDecimal orderSum = rs.getBigDecimal("order_sum");
        return new ClientMessageMaintenancePreviewResponse.OrderRiskSample(
                rs.getLong("order_id"),
                rs.getLong("company_id"),
                rs.getString("company_title"),
                rs.getString("status_title"),
                rs.getLong("age_days"),
                (Integer) rs.getObject("order_amount"),
                orderSum == null ? null : orderSum.stripTrailingZeros().toPlainString(),
                rs.getLong("reviews"),
                rs.getLong("published_reviews"),
                rs.getLong("bad_tasks"),
                rs.getLong("pending_bad_tasks"),
                maxPublishDate == null ? null : maxPublishDate.toLocalDate(),
                rs.getString("reason")
        );
    }
}

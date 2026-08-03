package com.hunt.otziv.common_billing.repository;

import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read model for the common-invoice portion of the manager board.
 *
 * <p>The board used to materialize every active invoice and every linked order
 * in the JVM even when only one page or grouped counters were requested. These
 * queries deliberately keep the business predicates in one SQL read model so
 * filtering, counting, grouping and pagination happen before entity graphs are
 * loaded.</p>
 */
@Repository
@RequiredArgsConstructor
public class CommonInvoiceBoardQueryRepository {

    private static final Set<CommonInvoiceStatus> BOARD_INVOICE_STATUSES = Set.of(
            CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID,
            CommonInvoiceStatus.NEEDS_ATTENTION,
            CommonInvoiceStatus.UNPAID
    );
    private static final List<String> PRE_PUBLICATION_STATUSES = List.of(
            "Новый",
            "Нагул",
            "В проверку",
            "На проверке",
            "Коррекция"
    );

    private static final String VISIBILITY_SQL = """
            (
                :unrestricted = 1
                OR account.manager_id IN (:visibleManagerIds)
                OR (
                    EXISTS (
                        SELECT 1
                        FROM common_invoice_orders visibility_item
                        WHERE visibility_item.invoice_id = invoice.invoice_id
                    )
                    AND NOT EXISTS (
                        SELECT 1
                        FROM common_invoice_orders hidden_item
                        JOIN orders hidden_order ON hidden_order.order_id = hidden_item.order_id
                        WHERE hidden_item.invoice_id = invoice.invoice_id
                          AND (
                              hidden_order.order_manager IS NULL
                              OR hidden_order.order_manager NOT IN (:visibleManagerIds)
                          )
                    )
                )
            )
            """;

    private static final String BOARD_STATUS_SQL = """
            CASE
                WHEN invoice.status = 'COLLECTING'
                     AND EXISTS (
                         SELECT 1
                         FROM common_invoice_orders blocker_item
                         JOIN orders blocker_order ON blocker_order.order_id = blocker_item.order_id
                         JOIN order_statuses blocker_status
                           ON blocker_status.order_status_id = blocker_order.order_status
                         WHERE blocker_item.invoice_id = invoice.invoice_id
                           AND blocker_item.publication_blocker_since IS NOT NULL
                           AND blocker_item.publication_blocker_since <= :blockerCutoff
                           AND TRIM(COALESCE(blocker_status.order_status_title, ''))
                               IN (:prePublicationStatuses)
                     )
                    THEN 'Требует внимания'
                WHEN invoice.status IN ('COLLECTING', 'READY')
                    THEN CASE
                        WHEN EXISTS (
                                 SELECT 1
                                 FROM common_invoice_orders any_item
                                 WHERE any_item.invoice_id = invoice.invoice_id
                             )
                             AND NOT EXISTS (
                                 SELECT 1
                                 FROM common_invoice_orders unready_item
                                 WHERE unready_item.invoice_id = invoice.invoice_id
                                   AND unready_item.ready = FALSE
                             )
                             AND NOT EXISTS (
                                 SELECT 1
                                 FROM common_invoice_orders recovery_item
                                 JOIN review_recovery_tasks recovery_task
                                   ON recovery_task.review_recovery_task_order = recovery_item.order_id
                                  AND recovery_task.review_recovery_task_status = 'PLANNED'
                                 JOIN review_recovery_batches recovery_batch
                                   ON recovery_batch.review_recovery_batch_id = recovery_task.review_recovery_task_batch
                                  AND recovery_batch.review_recovery_batch_status = 'OPEN'
                                 WHERE recovery_item.invoice_id = invoice.invoice_id
                             )
                            THEN 'Опубликовано'
                        ELSE 'Ожидает общего счета'
                    END
                WHEN invoice.status = 'INVOICED' THEN 'Выставлен счет'
                WHEN invoice.status IN ('REMINDER', 'PARTIALLY_PAID') THEN 'Напоминание'
                WHEN invoice.status = 'NEEDS_ATTENTION' THEN 'Требует внимания'
                WHEN invoice.status = 'UNPAID' THEN 'Не оплачено'
                WHEN invoice.status = 'BAN' THEN 'Бан'
                WHEN invoice.status IN ('ARCHIVED', 'DISABLED') THEN 'Архив'
                WHEN invoice.status = 'PAID' THEN 'Оплачено'
                ELSE invoice.status
            END
            """;

    private static final String BOARD_ROWS_CTE = """
            WITH board_rows AS (
                SELECT
                    invoice.invoice_id,
                    invoice.updated_at,
                    %s AS board_status,
                    CASE WHEN %s THEN 1 ELSE 0 END AS visible,
                    CASE
                        WHEN :filterCompany = 0 OR EXISTS (
                            SELECT 1
                            FROM common_invoice_orders company_item
                            JOIN orders company_order ON company_order.order_id = company_item.order_id
                            WHERE company_item.invoice_id = invoice.invoice_id
                              AND company_order.order_company = :companyId
                        ) THEN 1 ELSE 0
                    END AS company_match,
                    CASE
                        WHEN :keyword = ''
                             OR LOCATE(BINARY :keyword, BINARY LOWER(TRIM(COALESCE(account.account_name, '')))) > 0
                             OR LOCATE(BINARY :keyword, BINARY LOWER(TRIM(COALESCE(invoice.title, '')))) > 0
                             OR LOCATE(:keyword, CAST(invoice.invoice_id AS CHAR)) > 0
                             OR EXISTS (
                                 SELECT 1
                                 FROM common_invoice_orders keyword_item
                                 JOIN orders keyword_order ON keyword_order.order_id = keyword_item.order_id
                                 LEFT JOIN companies keyword_company
                                   ON keyword_company.company_id = keyword_order.order_company
                                 LEFT JOIN filial keyword_filial
                                   ON keyword_filial.filial_id = keyword_order.order_filial
                                 WHERE keyword_item.invoice_id = invoice.invoice_id
                                   AND (
                                       LOCATE(:keyword, CAST(keyword_order.order_id AS CHAR)) > 0
                                       OR LOCATE(BINARY :keyword, BINARY LOWER(TRIM(COALESCE(keyword_company.company_title, '')))) > 0
                                       OR LOCATE(BINARY :keyword, BINARY LOWER(TRIM(COALESCE(keyword_filial.filial_title, '')))) > 0
                                   )
                             ) THEN 1 ELSE 0
                    END AS keyword_match
                FROM common_invoices invoice
                JOIN common_billing_accounts account ON account.account_id = invoice.account_id
                WHERE invoice.status IN (:invoiceStatuses)
            )
            """.formatted(BOARD_STATUS_SQL, VISIBILITY_SQL);

    private static final String FILTERED_CARD_SQL = """
            FROM board_rows
            WHERE visible = 1
              AND company_match = 1
              AND keyword_match = 1
              AND (:allStatuses = 1 OR BINARY board_status = BINARY :boardStatus)
            """;

    private static final String LINKED_ORDER_FROM_SQL = """
            FROM common_invoice_orders item
            JOIN common_invoices invoice ON invoice.invoice_id = item.invoice_id
            JOIN common_billing_accounts account ON account.account_id = invoice.account_id
            JOIN orders linked_order ON linked_order.order_id = item.order_id
            LEFT JOIN order_statuses linked_status
              ON linked_status.order_status_id = linked_order.order_status
            LEFT JOIN companies linked_company
              ON linked_company.company_id = linked_order.order_company
            LEFT JOIN filial linked_filial
              ON linked_filial.filial_id = linked_order.order_filial
            WHERE invoice.status IN (:invoiceStatuses)
              AND %s
              AND (:unrestricted = 1 OR linked_order.order_manager IN (:visibleManagerIds))
            """.formatted(VISIBILITY_SQL);

    private final NamedParameterJdbcTemplate jdbc;

    public PageSelection findPage(
            String boardStatus,
            String normalizedKeyword,
            Long companyId,
            Set<Long> visibleManagerIds,
            boolean ascendingSort,
            int pageNumber,
            int pageSize,
            LocalDateTime blockerCutoff
    ) {
        int safePageNumber = Math.max(0, pageNumber);
        int safePageSize = Math.max(1, pageSize);
        long offset = Math.multiplyExact((long) safePageNumber, safePageSize);
        MapSqlParameterSource params = parameters(
                boardStatus,
                normalizedKeyword,
                companyId,
                visibleManagerIds,
                blockerCutoff
        ).addValue("limit", safePageSize)
                .addValue("offset", offset);

        Long totalCards = jdbc.queryForObject(
                BOARD_ROWS_CTE + "SELECT COUNT(*) " + FILTERED_CARD_SQL,
                params,
                Long.class
        );
        List<Long> invoiceIds = jdbc.queryForList(
                BOARD_ROWS_CTE
                        + "SELECT invoice_id "
                        + FILTERED_CARD_SQL
                        + (ascendingSort
                            ? " ORDER BY updated_at DESC, invoice_id DESC"
                            : " ORDER BY updated_at ASC, invoice_id ASC")
                        + " LIMIT :limit OFFSET :offset",
                params,
                Long.class
        );
        Long linkedOrderCount = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT item.order_id) "
                        + LINKED_ORDER_FROM_SQL
                        + " AND (:allStatuses = 1"
                        + "      OR BINARY TRIM(COALESCE(linked_status.order_status_title, '')) = BINARY :boardStatus)"
                        + " AND (:filterCompany = 0 OR linked_order.order_company = :companyId)"
                        + " AND (:keyword = ''"
                        + "      OR LOCATE(:keyword, CAST(linked_order.order_id AS CHAR)) > 0"
                        + "      OR LOCATE(BINARY :keyword, BINARY LOWER(TRIM(COALESCE(linked_company.company_title, '')))) > 0"
                        + "      OR LOCATE(BINARY :keyword, BINARY LOWER(TRIM(COALESCE(linked_filial.filial_title, '')))) > 0)",
                params,
                Long.class
        );
        return new PageSelection(
                invoiceIds,
                totalCards == null ? 0L : totalCards,
                Math.toIntExact(linkedOrderCount == null ? 0L : linkedOrderCount)
        );
    }

    public BoardMetrics metrics(Set<Long> visibleManagerIds, LocalDateTime blockerCutoff) {
        MapSqlParameterSource params = parameters("", "", null, visibleManagerIds, blockerCutoff);
        Map<String, Integer> cardCounts = groupedCounts(
                BOARD_ROWS_CTE
                        + "SELECT board_status AS status_title, COUNT(*) AS status_count "
                        + "FROM board_rows WHERE visible = 1 GROUP BY board_status",
                params
        );
        Map<String, Integer> linkedOrderCounts = groupedCounts(
                "SELECT MIN(TRIM(linked_status.order_status_title)) AS status_title, COUNT(*) AS status_count "
                        + LINKED_ORDER_FROM_SQL
                        + " AND TRIM(COALESCE(linked_status.order_status_title, '')) <> ''"
                        + " GROUP BY BINARY TRIM(linked_status.order_status_title)",
                params
        );
        return new BoardMetrics(cardCounts, linkedOrderCounts);
    }

    private Map<String, Integer> groupedCounts(String sql, MapSqlParameterSource params) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query(sql, params, resultSet -> {
            long rawCount = resultSet.getLong("status_count");
            counts.put(resultSet.getString("status_title"), Math.toIntExact(rawCount));
        });
        return counts;
    }

    private MapSqlParameterSource parameters(
            String boardStatus,
            String normalizedKeyword,
            Long companyId,
            Set<Long> visibleManagerIds,
            LocalDateTime blockerCutoff
    ) {
        List<Long> normalizedManagerIds = visibleManagerIds == null
                ? List.of()
                : visibleManagerIds.stream().filter(id -> id != null).distinct().toList();
        Collection<Long> managerIds = normalizedManagerIds.isEmpty() ? List.of(-1L) : normalizedManagerIds;
        String cleanStatus = boardStatus == null ? "" : boardStatus.trim();
        return new MapSqlParameterSource()
                .addValue("invoiceStatuses", BOARD_INVOICE_STATUSES.stream().map(Enum::name).toList())
                .addValue("prePublicationStatuses", PRE_PUBLICATION_STATUSES)
                .addValue("blockerCutoff", blockerCutoff)
                .addValue("unrestricted", visibleManagerIds == null ? 1 : 0)
                .addValue("visibleManagerIds", managerIds)
                .addValue("filterCompany", companyId == null ? 0 : 1)
                .addValue("companyId", companyId == null ? -1L : companyId)
                .addValue("keyword", normalizedKeyword == null ? "" : normalizedKeyword)
                .addValue("boardStatus", cleanStatus)
                .addValue("allStatuses", cleanStatus.isBlank() || "Все".equals(cleanStatus) ? 1 : 0);
    }

    public record PageSelection(List<Long> invoiceIds, long totalCards, int linkedOrderCount) {
        public PageSelection {
            invoiceIds = invoiceIds == null ? List.of() : List.copyOf(invoiceIds);
        }
    }

    public record BoardMetrics(Map<String, Integer> cardCounts, Map<String, Integer> linkedOrderCounts) {
        public BoardMetrics {
            cardCounts = cardCounts == null ? Map.of() : Map.copyOf(cardCounts);
            linkedOrderCounts = linkedOrderCounts == null ? Map.of() : Map.copyOf(linkedOrderCounts);
        }
    }
}

package com.hunt.otziv.specialist_transfer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.specialist_transfer.dto.SpecialistTransferAuditResponse;
import com.hunt.otziv.specialist_transfer.dto.SpecialistTransferCompanySample;
import com.hunt.otziv.specialist_transfer.dto.SpecialistTransferPreview;
import com.hunt.otziv.specialist_transfer.dto.SpecialistTransferRequest;
import com.hunt.otziv.specialist_transfer.dto.SpecialistTransferResult;
import com.hunt.otziv.specialist_transfer.dto.SpecialistTransferWarning;
import com.hunt.otziv.specialist_transfer.dto.SpecialistTransferWorkerResponse;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class SpecialistTransferService {

    private static final String CONFIRMATION_TEXT = "ПЕРЕНЕСТИ";
    private static final int SAMPLE_LIMIT = 20;
    private static final int COMMENT_MAX_LENGTH = 1000;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public SpecialistTransferPreview preview(SpecialistTransferRequest request, Authentication authentication) {
        TransferContext context = buildContext(request, authentication);
        return buildPreview(context);
    }

    @Transactional
    public SpecialistTransferResult apply(SpecialistTransferRequest request, Authentication authentication) {
        if (request == null || !CONFIRMATION_TEXT.equals(normalize(request.confirmationText()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для переноса нужно подтверждение: " + CONFIRMATION_TEXT);
        }

        TransferContext context = buildContext(request, authentication);
        SpecialistTransferPreview preview = buildPreview(context);
        if (preview.companyCount() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У исходного специалиста нет компаний для переноса");
        }

        MapSqlParameterSource params = baseParams(context);

        int companyLinksAdded = jdbc.update("""
                INSERT INTO workers_companies (company_id, worker_id)
                SELECT source.company_id, :toWorkerId
                FROM (
                    SELECT DISTINCT wc.company_id
                    FROM workers_companies wc
                    WHERE wc.worker_id = :fromWorkerId
                      AND wc.company_id IN (:companyIds)
                ) source
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM workers_companies existing_link
                    WHERE existing_link.company_id = source.company_id
                      AND existing_link.worker_id = :toWorkerId
                )
                """, params);

        int unpublishedReviewCount = jdbc.update("""
                UPDATE reviews r
                JOIN order_details od ON od.order_detail_id = r.review_order_details
                JOIN orders o ON o.order_id = od.order_detail_order
                SET r.review_worker = :toWorkerId
                WHERE r.review_publish = 0
                  AND o.order_worker = :fromWorkerId
                  AND o.order_complete = 0
                  AND o.order_company IN (:companyIds)
                """, params);

        int badReviewTaskCount = jdbc.update("""
                UPDATE bad_review_tasks brt
                JOIN orders o ON o.order_id = brt.bad_review_task_order
                SET brt.bad_review_task_worker = :toWorkerId
                WHERE brt.bad_review_task_status = 'NEW'
                  AND o.order_worker = :fromWorkerId
                  AND o.order_complete = 0
                  AND o.order_company IN (:companyIds)
                """, params);

        int activeOrderCount = jdbc.update("""
                UPDATE orders o
                SET o.order_worker = :toWorkerId
                WHERE o.order_worker = :fromWorkerId
                  AND o.order_company IN (:companyIds)
                  AND o.order_complete = 0
                """, params);

        int companyLinksRemoved = jdbc.update("""
                DELETE FROM workers_companies
                WHERE worker_id = :fromWorkerId
                  AND company_id IN (:companyIds)
                """, params);

        LocalDateTime createdAt = LocalDateTime.now();
        Long auditId = insertAudit(context, createdAt, preview.companyCount(), activeOrderCount, unpublishedReviewCount, badReviewTaskCount);

        return new SpecialistTransferResult(
                auditId,
                createdAt,
                context.fromWorker(),
                context.toWorker(),
                preview.companyCount(),
                companyLinksAdded,
                companyLinksRemoved,
                activeOrderCount,
                unpublishedReviewCount,
                badReviewTaskCount
        );
    }

    @Transactional(readOnly = true)
    public List<SpecialistTransferAuditResponse> recent(Authentication authentication) {
        Actor actor = resolveActor(authentication);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("actorUserId", actor.userId())
                .addValue("adminAccess", actor.admin());

        return jdbc.query("""
                SELECT
                    audit.id,
                    audit.created_at,
                    audit.actor_user_id,
                    COALESCE(NULLIF(TRIM(actor.fio), ''), actor.username, CONCAT('Пользователь #', audit.actor_user_id)) AS actor_name,
                    audit.from_worker_id,
                    COALESCE(NULLIF(TRIM(from_user.fio), ''), from_user.username, CONCAT('Специалист #', audit.from_worker_id)) AS from_worker_name,
                    audit.to_worker_id,
                    COALESCE(NULLIF(TRIM(to_user.fio), ''), to_user.username, CONCAT('Специалист #', audit.to_worker_id)) AS to_worker_name,
                    audit.company_count,
                    audit.order_count,
                    audit.review_count,
                    audit.bad_review_task_count,
                    audit.comment
                FROM specialist_transfer_audit audit
                LEFT JOIN users actor ON actor.id = audit.actor_user_id
                LEFT JOIN workers from_worker ON from_worker.worker_id = audit.from_worker_id
                LEFT JOIN users from_user ON from_user.id = from_worker.user_id
                LEFT JOIN workers to_worker ON to_worker.worker_id = audit.to_worker_id
                LEFT JOIN users to_user ON to_user.id = to_worker.user_id
                WHERE :adminAccess = TRUE
                   OR audit.actor_user_id = :actorUserId
                ORDER BY audit.created_at DESC, audit.id DESC
                LIMIT 20
                """, params, (rs, rowNum) -> new SpecialistTransferAuditResponse(
                rs.getLong("id"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getLong("actor_user_id"),
                rs.getString("actor_name"),
                rs.getLong("from_worker_id"),
                rs.getString("from_worker_name"),
                rs.getLong("to_worker_id"),
                rs.getString("to_worker_name"),
                rs.getInt("company_count"),
                rs.getInt("order_count"),
                rs.getInt("review_count"),
                rs.getInt("bad_review_task_count"),
                rs.getString("comment")
        ));
    }

    private TransferContext buildContext(SpecialistTransferRequest request, Authentication authentication) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Данные переноса не переданы");
        }
        if (request.fromWorkerId() == null || request.fromWorkerId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Исходный специалист не указан");
        }
        if (request.toWorkerId() == null || request.toWorkerId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Новый специалист не указан");
        }
        if (request.fromWorkerId().equals(request.toWorkerId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Нужно выбрать разных специалистов");
        }

        SpecialistTransferWorkerResponse fromWorker = findWorker(request.fromWorkerId());
        SpecialistTransferWorkerResponse toWorker = findWorker(request.toWorkerId());
        Actor actor = resolveActor(authentication);
        ensureActorCanTransfer(actor, request.fromWorkerId(), request.toWorkerId());

        List<Long> companyIds = eligibleCompanyIds(request, actor);
        return new TransferContext(actor, fromWorker, toWorker, companyIds, normalizeComment(request.comment()));
    }

    private SpecialistTransferWorkerResponse findWorker(Long workerId) {
        return jdbc.query("""
                SELECT
                    w.worker_id,
                    u.id AS user_id,
                    u.username,
                    u.fio,
                    COALESCE(NULLIF(TRIM(u.fio), ''), u.username, CONCAT('Специалист #', w.worker_id)) AS label,
                    COALESCE(u.active, 0) AS active
                FROM workers w
                LEFT JOIN users u ON u.id = w.user_id
                WHERE w.worker_id = :workerId
                """, new MapSqlParameterSource("workerId", workerId), (rs, rowNum) -> new SpecialistTransferWorkerResponse(
                rs.getLong("worker_id"),
                nullableLong(rs.getObject("user_id")),
                rs.getString("username"),
                rs.getString("fio"),
                rs.getString("label"),
                rs.getBoolean("active")
        )).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Специалист #" + workerId + " не найден"));
    }

    private Actor resolveActor(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Пользователь не определен");
        }

        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String role = normalizeRole(authority.getAuthority());
            if (!role.isBlank()) {
                roles.add(role);
            }
        }

        boolean admin = roles.contains("ADMIN");
        boolean owner = roles.contains("OWNER");
        if (!admin && !owner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Перенос доступен только админу или владельцу");
        }

        Long userId = jdbc.query("""
                SELECT id
                FROM users
                WHERE username = :username
                """, new MapSqlParameterSource("username", authentication.getName()), (rs, rowNum) -> rs.getLong("id"))
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Пользователь не найден"));

        return new Actor(userId, admin, owner);
    }

    private void ensureActorCanTransfer(Actor actor, Long fromWorkerId, Long toWorkerId) {
        if (actor.admin()) {
            return;
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("actorUserId", actor.userId())
                .addValue("workerIds", List.of(fromWorkerId, toWorkerId));

        int visibleCount = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT w.worker_id)
                FROM managers_users owner_manager
                JOIN managers m ON m.manager_id = owner_manager.manager_id
                JOIN workers_users manager_worker ON manager_worker.user_id = m.user_id
                JOIN workers w ON w.worker_id = manager_worker.worker_id
                WHERE owner_manager.user_id = :actorUserId
                  AND w.worker_id IN (:workerIds)
                """, params, Integer.class);

        if (visibleCount < 2) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Владелец может переносить только специалистов своей команды");
        }
    }

    private List<Long> eligibleCompanyIds(SpecialistTransferRequest request, Actor actor) {
        List<Long> requestedCompanyIds = normalizeCompanyIds(request.companyIds());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("fromWorkerId", request.fromWorkerId())
                .addValue("actorUserId", actor.userId())
                .addValue("adminAccess", actor.admin());

        String companyFilter = "";
        if (!requestedCompanyIds.isEmpty()) {
            params.addValue("requestedCompanyIds", requestedCompanyIds);
            companyFilter = " AND c.company_id IN (:requestedCompanyIds)";
        }

        return jdbc.query("""
                SELECT DISTINCT c.company_id
                FROM workers_companies wc
                JOIN companies c ON c.company_id = wc.company_id
                WHERE wc.worker_id = :fromWorkerId
                """ + companyFilter + """
                  AND (
                      :adminAccess = TRUE
                      OR c.company_manager IN (
                          SELECT manager_id
                          FROM managers_users
                          WHERE user_id = :actorUserId
                      )
                  )
                ORDER BY c.company_id
                """, params, (rs, rowNum) -> rs.getLong("company_id"));
    }

    private SpecialistTransferPreview buildPreview(TransferContext context) {
        List<Long> companyIds = context.companyIds();
        List<SpecialistTransferWarning> warnings = new ArrayList<>();
        if (companyIds.isEmpty()) {
            warnings.add(new SpecialistTransferWarning("NO_COMPANIES", "У исходного специалиста нет доступных компаний для переноса"));
            return new SpecialistTransferPreview(
                    context.fromWorker(),
                    context.toWorker(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    List.of(),
                    warnings
            );
        }

        MapSqlParameterSource params = baseParams(context);
        int activeOrderCount = count("""
                SELECT COUNT(*)
                FROM orders o
                WHERE o.order_worker = :fromWorkerId
                  AND o.order_company IN (:companyIds)
                  AND o.order_complete = 0
                """, params);
        int unpublishedReviewCount = count("""
                SELECT COUNT(DISTINCT r.review_id)
                FROM reviews r
                JOIN order_details od ON od.order_detail_id = r.review_order_details
                JOIN orders o ON o.order_id = od.order_detail_order
                WHERE r.review_publish = 0
                  AND o.order_worker = :fromWorkerId
                  AND o.order_complete = 0
                  AND o.order_company IN (:companyIds)
                """, params);
        int badReviewTaskCount = count("""
                SELECT COUNT(*)
                FROM bad_review_tasks brt
                JOIN orders o ON o.order_id = brt.bad_review_task_order
                WHERE brt.bad_review_task_status = 'NEW'
                  AND o.order_worker = :fromWorkerId
                  AND o.order_complete = 0
                  AND o.order_company IN (:companyIds)
                """, params);
        int targetAlreadyAssigned = count("""
                SELECT COUNT(DISTINCT company_id)
                FROM workers_companies
                WHERE worker_id = :toWorkerId
                  AND company_id IN (:companyIds)
                """, params);
        int companiesWithCurrentOrders = count("""
                SELECT COUNT(DISTINCT o.order_company)
                FROM orders o
                WHERE o.order_worker = :fromWorkerId
                  AND o.order_company IN (:companyIds)
                  AND o.order_complete = 0
                """, params);
        int missingManagerLinks = count("""
                SELECT COUNT(DISTINCT c.company_manager)
                FROM companies c
                WHERE c.company_id IN (:companyIds)
                  AND c.company_manager IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1
                      FROM managers m
                      JOIN workers_users wu ON wu.user_id = m.user_id
                      WHERE m.manager_id = c.company_manager
                        AND wu.worker_id = :toWorkerId
                  )
                """, params);
        List<SpecialistTransferCompanySample> samples = sampleCompanies(companyIds);

        if (!context.fromWorker().active()) {
            warnings.add(new SpecialistTransferWarning("FROM_INACTIVE", "Исходный специалист отключен"));
        }
        if (!context.toWorker().active()) {
            warnings.add(new SpecialistTransferWarning("TO_INACTIVE", "Новый специалист отключен"));
        }
        if (targetAlreadyAssigned > 0) {
            warnings.add(new SpecialistTransferWarning(
                    "TARGET_ALREADY_ASSIGNED",
                    "Новый специалист уже привязан к части компаний; дубли не будут созданы"
            ));
        }
        int companiesWithoutActiveOrders = companyIds.size() - companiesWithCurrentOrders;
        if (companiesWithoutActiveOrders > 0) {
            warnings.add(new SpecialistTransferWarning(
                    "COMPANIES_WITHOUT_ACTIVE_ORDERS",
                    "У части компаний нет текущих заказов; будет перенесена только привязка компании"
            ));
        }
        if (missingManagerLinks > 0) {
            warnings.add(new SpecialistTransferWarning(
                    "TARGET_MISSING_MANAGER_LINKS",
                    "Новый специалист не состоит в команде части менеджеров этих компаний"
            ));
        }

        return new SpecialistTransferPreview(
                context.fromWorker(),
                context.toWorker(),
                companyIds.size(),
                activeOrderCount,
                unpublishedReviewCount,
                badReviewTaskCount,
                targetAlreadyAssigned,
                companiesWithoutActiveOrders,
                missingManagerLinks,
                samples,
                List.copyOf(warnings)
        );
    }

    private List<SpecialistTransferCompanySample> sampleCompanies(List<Long> companyIds) {
        return jdbc.query("""
                SELECT
                    c.company_id,
                    c.company_title,
                    c.company_phone,
                    c.company_city,
                    COALESCE(s.status_title, '') AS status_title
                FROM companies c
                LEFT JOIN company_status s ON s.company_status_id = c.company_status
                WHERE c.company_id IN (:companyIds)
                ORDER BY c.company_id
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("companyIds", companyIds)
                .addValue("limit", SAMPLE_LIMIT), (rs, rowNum) -> new SpecialistTransferCompanySample(
                rs.getLong("company_id"),
                rs.getString("company_title"),
                rs.getString("company_phone"),
                rs.getString("company_city"),
                rs.getString("status_title")
        ));
    }

    private Long insertAudit(
            TransferContext context,
            LocalDateTime createdAt,
            int companyCount,
            int orderCount,
            int reviewCount,
            int badReviewTaskCount
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.getJdbcTemplate().update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO specialist_transfer_audit (
                        created_at,
                        actor_user_id,
                        from_worker_id,
                        to_worker_id,
                        company_count,
                        order_count,
                        review_count,
                        bad_review_task_count,
                        company_ids_json,
                        comment
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setTimestamp(1, Timestamp.valueOf(createdAt));
            statement.setLong(2, context.actor().userId());
            statement.setLong(3, context.fromWorker().id());
            statement.setLong(4, context.toWorker().id());
            statement.setInt(5, companyCount);
            statement.setInt(6, orderCount);
            statement.setInt(7, reviewCount);
            statement.setInt(8, badReviewTaskCount);
            statement.setString(9, companyIdsJson(context.companyIds()));
            statement.setString(10, context.comment());
            return statement;
        }, keyHolder);

        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    private MapSqlParameterSource baseParams(TransferContext context) {
        return new MapSqlParameterSource()
                .addValue("fromWorkerId", context.fromWorker().id())
                .addValue("toWorkerId", context.toWorker().id())
                .addValue("companyIds", context.companyIds());
    }

    private int count(String sql, MapSqlParameterSource params) {
        Integer count = jdbc.queryForObject(sql, params, Integer.class);
        return count == null ? 0 : count;
    }

    private List<Long> normalizeCompanyIds(List<Long> companyIds) {
        if (companyIds == null || companyIds.isEmpty()) {
            return List.of();
        }

        return companyIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
    }

    private String normalizeComment(String comment) {
        String normalized = normalize(comment);
        if (normalized.length() <= COMMENT_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, COMMENT_MAX_LENGTH);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeRole(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        return normalized.startsWith("ROLE_") ? normalized.substring("ROLE_".length()) : normalized;
    }

    private Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private String companyIdsJson(List<Long> companyIds) {
        try {
            return objectMapper.writeValueAsString(companyIds);
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private record Actor(Long userId, boolean admin, boolean owner) {
    }

    private record TransferContext(
            Actor actor,
            SpecialistTransferWorkerResponse fromWorker,
            SpecialistTransferWorkerResponse toWorker,
            List<Long> companyIds,
            String comment
    ) {
    }
}

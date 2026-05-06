package com.hunt.otziv.r_review.board;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.hunt.otziv.r_review.utils.ReviewBoardSearch.keywordPredicate;
import static com.hunt.otziv.r_review.utils.ReviewBoardSearch.parseKeywordLong;
import static com.hunt.otziv.r_review.utils.ReviewBoardSearch.parseKeywordUuid;

@Service
@RequiredArgsConstructor
public class ReviewBoardQueryService {

    private final EntityManager entityManager;

    public Pageable reviewPageable(int pageNumber, int pageSize, String sortDirection) {
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDirection)
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        Sort sort = Sort.by(direction, "publishedDate").and(Sort.by(direction, "id"));
        return PageRequest.of(Math.max(pageNumber, 0), Math.max(pageSize, 1), sort);
    }

    public Page<Long> findReviewIdsForBoard(
            ReviewBoardMode mode,
            ReviewBoardScope scope,
            LocalDate localDate,
            String status,
            Worker worker,
            Manager manager,
            Set<Worker> workers,
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection
    ) {
        Pageable pageable = reviewPageable(pageNumber, pageSize, sortDirection);
        if ((scope == ReviewBoardScope.OWNER || scope == ReviewBoardScope.MANAGER) && (workers == null || workers.isEmpty())) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        String joins = """
                LEFT JOIN r.bot b
                LEFT JOIN r.filial f
                LEFT JOIN f.city city
                LEFT JOIN r.worker rw
                LEFT JOIN rw.user wu
                LEFT JOIN r.product rp
                LEFT JOIN r.category cat
                LEFT JOIN r.subCategory sub
                LEFT JOIN r.orderDetails d
                LEFT JOIN d.product dp
                LEFT JOIN d.order o
                LEFT JOIN o.company c
                LEFT JOIN o.status os
                LEFT JOIN o.manager om
                LEFT JOIN om.user mu
                """;

        List<String> conditions = new ArrayList<>();
        switch (mode) {
            case PUBLISH -> {
                conditions.add("r.publishedDate <= :localDate");
                conditions.add("r.publish = false");
            }
            case ORDER_STATUS -> conditions.add("os.title = :status");
            case VIGUL -> {
                conditions.add("r.publishedDate <= :localDate");
                conditions.add("r.publish = false");
                conditions.add("r.vigul = false");
                conditions.add("(b IS NULL OR b.counter <= 2)");
            }
        }

        switch (scope) {
            case WORKER -> conditions.add("r.worker = :worker");
            case OWNER -> conditions.add("r.worker IN :workers");
            case MANAGER -> {
                conditions.add("r.worker IN :workers");
                if (mode == ReviewBoardMode.VIGUL) {
                    conditions.add("(o IS NULL OR o.manager IS NULL OR o.manager = :manager)");
                } else {
                    conditions.add("o.manager = :manager");
                }
            }
            case ADMIN -> {
            }
        }

        Long keywordLong = parseKeywordLong(keyword);
        UUID keywordUuid = parseKeywordUuid(keyword);
        conditions.add(keywordPredicate(keywordLong != null, keywordUuid != null));

        String where = " WHERE " + String.join(" AND ", conditions);
        String direction = "desc".equalsIgnoreCase(sortDirection) ? "DESC" : "ASC";
        String orderBy = " ORDER BY r.publishedDate " + direction + ", r.id " + direction;

        TypedQuery<Long> idQuery = entityManager.createQuery(
                "SELECT r.id FROM Review r " + joins + where + " GROUP BY r.id, r.publishedDate" + orderBy,
                Long.class
        );
        TypedQuery<Long> countQuery = entityManager.createQuery(
                "SELECT COUNT(DISTINCT r.id) FROM Review r " + joins + where,
                Long.class
        );

        bindReviewBoardParameters(idQuery, mode, scope, localDate, status, worker, manager, workers, keyword, keywordLong, keywordUuid);
        bindReviewBoardParameters(countQuery, mode, scope, localDate, status, worker, manager, workers, keyword, keywordLong, keywordUuid);

        idQuery.setFirstResult((int) pageable.getOffset());
        idQuery.setMaxResults(pageable.getPageSize());

        return new PageImpl<>(idQuery.getResultList(), pageable, countQuery.getSingleResult());
    }

    public long countReviewIdsForBoard(
            ReviewBoardMode mode,
            ReviewBoardScope scope,
            LocalDate localDate,
            String status,
            Worker worker,
            Manager manager,
            Set<Worker> workers
    ) {
        String joins = reviewCountJoins(mode, scope);

        List<String> conditions = new ArrayList<>();
        switch (mode) {
            case PUBLISH -> {
                conditions.add("r.publishedDate <= :localDate");
                conditions.add("r.publish = false");
            }
            case ORDER_STATUS -> conditions.add("os.title = :status");
            case VIGUL -> {
                conditions.add("r.publishedDate <= :localDate");
                conditions.add("r.publish = false");
                conditions.add("r.vigul = false");
                conditions.add("(b IS NULL OR b.counter <= 2)");
            }
        }

        switch (scope) {
            case WORKER -> conditions.add("r.worker = :worker");
            case OWNER -> conditions.add("r.worker IN :workers");
            case MANAGER -> {
                conditions.add("r.worker IN :workers");
                if (mode == ReviewBoardMode.VIGUL) {
                    conditions.add("(o IS NULL OR o.manager IS NULL OR o.manager = :manager)");
                } else {
                    conditions.add("o.manager = :manager");
                }
            }
            case ADMIN -> {
            }
        }

        TypedQuery<Long> query = entityManager.createQuery(
                "SELECT COUNT(r.id) FROM Review r " + joins + " WHERE " + String.join(" AND ", conditions),
                Long.class
        );
        bindReviewBoardCountParameters(query, mode, scope, localDate, status, worker, manager, workers);
        return query.getSingleResult();
    }

    private String reviewCountJoins(ReviewBoardMode mode, ReviewBoardScope scope) {
        return switch (mode) {
            case PUBLISH -> scope == ReviewBoardScope.MANAGER
                    ? " JOIN r.orderDetails d JOIN d.order o "
                    : "";
            case VIGUL -> scope == ReviewBoardScope.MANAGER
                    ? " LEFT JOIN r.bot b LEFT JOIN r.orderDetails d LEFT JOIN d.order o "
                    : " LEFT JOIN r.bot b ";
            case ORDER_STATUS -> " JOIN r.orderDetails d JOIN d.order o JOIN o.status os ";
        };
    }

    private void bindReviewBoardCountParameters(
            TypedQuery<Long> query,
            ReviewBoardMode mode,
            ReviewBoardScope scope,
            LocalDate localDate,
            String status,
            Worker worker,
            Manager manager,
            Set<Worker> workers
    ) {
        if (mode == ReviewBoardMode.PUBLISH || mode == ReviewBoardMode.VIGUL) {
            query.setParameter("localDate", localDate);
        }
        if (mode == ReviewBoardMode.ORDER_STATUS) {
            query.setParameter("status", status);
        }
        if (scope == ReviewBoardScope.WORKER) {
            query.setParameter("worker", worker);
        }
        if (scope == ReviewBoardScope.OWNER || scope == ReviewBoardScope.MANAGER) {
            query.setParameter("workers", workers);
        }
        if (scope == ReviewBoardScope.MANAGER) {
            query.setParameter("manager", manager);
        }
    }

    private void bindReviewBoardParameters(
            TypedQuery<Long> query,
            ReviewBoardMode mode,
            ReviewBoardScope scope,
            LocalDate localDate,
            String status,
            Worker worker,
            Manager manager,
            Set<Worker> workers,
            String keyword,
            Long keywordLong,
            UUID keywordUuid
    ) {
        if (mode == ReviewBoardMode.PUBLISH || mode == ReviewBoardMode.VIGUL) {
            query.setParameter("localDate", localDate);
        }
        if (mode == ReviewBoardMode.ORDER_STATUS) {
            query.setParameter("status", status);
        }
        if (scope == ReviewBoardScope.WORKER) {
            query.setParameter("worker", worker);
        }
        if (scope == ReviewBoardScope.OWNER || scope == ReviewBoardScope.MANAGER) {
            query.setParameter("workers", workers);
        }
        if (scope == ReviewBoardScope.MANAGER) {
            query.setParameter("manager", manager);
        }

        query.setParameter("keyword", "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%");
        if (keywordLong != null) {
            query.setParameter("keywordLong", keywordLong);
        }
        if (keywordUuid != null) {
            query.setParameter("keywordUuid", keywordUuid);
        }
    }
}

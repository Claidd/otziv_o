package com.hunt.otziv.p_products.board.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.p_products.application.WorkerStaffAccessPolicy;
import com.hunt.otziv.p_products.board.model.WorkerOverdueOrders;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.u_users.model.Manager;
import java.security.Principal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Owns the overdue board use case; HTTP response types stay in the controller. */
@Service
@RequiredArgsConstructor
public class WorkerOverdueOrdersQuery {
    private static final int OVERDUE_NOTIFICATION_DAYS = 4;
    private static final Set<String> OVERDUE_IGNORED_STATUSES = Set.of("Оплачено", "Архив", "Публикация");
    private static final String ORDER_STATUS_NEW = "Новый", ORDER_STATUS_CORRECT = "Коррекция";
    private static final String SECTION_NAGUL = "nagul", SECTION_PUBLISH = "publish";
    private final WorkerStaffAccessPolicy staffAccess;
    private final OrderRepository orderRepository;
    private final WorkerBoardTaskQueries taskQueries;

    public WorkerOverdueOrders query(Principal principal, Authentication authentication) {
        LocalDate today = LocalDate.now();
        LocalDate cutoff = today.minusDays(OVERDUE_NOTIFICATION_DAYS + 1L);
        List<Object[]> orderRows = loadOverdueOrderSummary(principal, authentication, cutoff);
        List<WorkerOverdueOrders.Section> statuses = new ArrayList<>();
        addPositiveStatus(statuses, overdueOrderSection(orderRows, today, ORDER_STATUS_NEW, "Новые"));
        addPositiveStatus(statuses, overdueOrderSection(orderRows, today, ORDER_STATUS_CORRECT, "Коррекция"));
        addPositiveStatus(statuses, overdueReviewSection(principal, authentication, SECTION_NAGUL, "Выгул", cutoff, today));
        addPositiveStatus(statuses, overdueReviewSection(principal, authentication, SECTION_PUBLISH, "Публикация", cutoff, today));
        addPositiveStatus(statuses, overdueRecoverySection(principal, authentication, cutoff, today));
        addPositiveStatus(statuses, overdueBadSection(principal, authentication, cutoff, today));

        long total = statuses.stream()
                .mapToLong(WorkerOverdueOrders.Section::count)
                .sum();

        return new WorkerOverdueOrders(
                OVERDUE_NOTIFICATION_DAYS,
                total,
                statuses
        );
    }

    private List<Object[]> loadOverdueOrderSummary(Principal principal, Authentication authentication, LocalDate cutoff) {
        if (hasRole(authentication, "ADMIN")) {
            return orderRepository.summarizeOverdueOrders(cutoff, OVERDUE_IGNORED_STATUSES);
        }
        if (hasRole(authentication, "OWNER")) {
            Set<Manager> managers = staffAccess.resolveOwnerManagers(principal);
            return managers.isEmpty()
                    ? List.of()
                    : orderRepository.summarizeOverdueOrdersByManagers(managers, cutoff, OVERDUE_IGNORED_STATUSES);
        }
        if (hasRole(authentication, "MANAGER")) {
            return orderRepository.summarizeOverdueOrdersByManager(
                    staffAccess.resolveManager(principal),
                    cutoff,
                    OVERDUE_IGNORED_STATUSES
            );
        }
        return orderRepository.summarizeOverdueOrdersByWorker(
                staffAccess.resolveWorker(principal),
                cutoff,
                OVERDUE_IGNORED_STATUSES
        );
    }

    private WorkerOverdueOrders.Section overdueOrderSection(
            List<Object[]> rows,
            LocalDate today,
            String orderStatus,
            String sectionLabel
    ) {
        long count = 0;
        long maxDays = 0;
        if (rows != null) {
            for (Object[] row : rows) {
                if (!orderStatus.equals(rowString(row, 0, ""))) {
                    continue;
                }
                count += rowLong(row, 1);
                maxDays = Math.max(maxDays, daysSince(rowDate(row, 2), today));
            }
        }
        return new WorkerOverdueOrders.Section(sectionLabel, count, maxDays);
    }

    private WorkerOverdueOrders.Section overdueReviewSection(
            Principal principal,
            Authentication authentication,
            String section,
            String sectionLabel,
            LocalDate cutoff,
            LocalDate today
    ) {
        Page<ReviewDTOOne> page = taskQueries.loadReviewPage(
                principal,
                authentication,
                null,
                section,
                0,
                1,
                "asc",
                "",
                cutoff
        );
        LocalDate oldestDate = page.getContent().isEmpty() ? null : page.getContent().getFirst().getPublishedDate();
        return new WorkerOverdueOrders.Section(sectionLabel, page.getTotalElements(), daysSince(oldestDate, today));
    }

    private WorkerOverdueOrders.Section overdueRecoverySection(
            Principal principal,
            Authentication authentication,
            LocalDate cutoff,
            LocalDate today
    ) {
        Page<ReviewRecoveryTask> page = taskQueries.loadRecoveryTasks(principal, authentication, null, "", 0, 1, "asc", cutoff);
        LocalDate oldestDate = page.getContent().isEmpty() ? null : page.getContent().getFirst().getScheduledDate();
        return new WorkerOverdueOrders.Section("Восстановление", page.getTotalElements(), daysSince(oldestDate, today));
    }

    private WorkerOverdueOrders.Section overdueBadSection(
            Principal principal,
            Authentication authentication,
            LocalDate cutoff,
            LocalDate today
    ) {
        Page<BadReviewTask> page = taskQueries.loadBadReviewTasks(principal, authentication, null, "", 0, 1, "asc", cutoff);
        LocalDate oldestDate = page.getContent().isEmpty() ? null : page.getContent().getFirst().getScheduledDate();
        return new WorkerOverdueOrders.Section("Плохие", page.getTotalElements(), daysSince(oldestDate, today));
    }

    private void addPositiveStatus(List<WorkerOverdueOrders.Section> statuses, WorkerOverdueOrders.Section status) {
        if (status.count() > 0) {
            statuses.add(status);
        }
    }

    private long daysSince(LocalDate date, LocalDate today) {
        if (date == null) {
            return 0;
        }

        return ChronoUnit.DAYS.between(date, today);
    }

    private long rowLong(Object[] row, int index) {
        Object value = rowValue(row, index);
        return value instanceof Number number ? number.longValue() : 0;
    }

    private LocalDate rowDate(Object[] row, int index) {
        Object value = rowValue(row, index);
        return value instanceof LocalDate localDate ? localDate : null;
    }

    private String rowString(Object[] row, int index, String fallback) {
        Object value = rowValue(row, index);
        if (value == null) {
            return fallback;
        }

        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    private Object rowValue(Object[] row, int index) {
        return row != null && index >= 0 && index < row.length ? row[index] : null;
    }
    private boolean hasRole(Authentication authentication, String role) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + role).equals(authority.getAuthority()));
    }
}

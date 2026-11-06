package com.hunt.otziv.bad_reviews.service;

import org.springframework.security.core.Authentication;
import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface BadReviewTaskService {

    int createTasksForUnpaidOrder(Order order);

    void cancelPendingTasksForOrder(Order order);

    int deletePendingTasksForOrder(Order order);

    int reassignPendingTasksForOrder(Long orderId, Worker worker);

    BadReviewTask completeTask(Long taskId);

    BadReviewTask completeTask(Long taskId, Authentication authentication);

    BadReviewTask getTask(Long taskId);

    BadReviewTask updateTask(Long taskId, String taskText, LocalDate scheduledDate);

    BadReviewTask updateTask(Long taskId, String taskText, LocalDate scheduledDate, Authentication authentication);

    BadReviewTask reassignTask(Long taskId, Worker worker);

    BadReviewTask reassignTask(Long taskId, Worker worker, Authentication authentication);

    BadReviewTask cancelTask(Long taskId);

    void deleteOrderReadyReminder(Order order);

    int deleteAllByOrderId(Long orderId);

    BadReviewTask changeTaskBot(Long taskId);

    BadReviewTask changeTaskBot(Long taskId, Authentication authentication);

    BadReviewTask deactivateAndChangeTaskBot(Long taskId, Long botId);

    BadReviewTask deactivateAndChangeTaskBot(Long taskId, Long botId, Authentication authentication);

    List<BadReviewTask> getTasksByOrderId(Long orderId);

    BadReviewTaskSummary getSummaryForOrder(Long orderId);

    Map<Long, BadReviewTaskSummary> getSummaryByOrderIds(Collection<Long> orderIds);

    BigDecimal getPayableSum(Order order);

    /** Amounts and task counters from the same fresh read; scoped to this assembly. */
    OrderAmounts prepareOrderAmounts(Collection<Order> orders);

    record OrderAmounts(Map<Long, BigDecimal> payableSums, Map<Long, BadReviewTaskSummary> summaries) {
        public OrderAmounts {
            payableSums = Map.copyOf(payableSums);
            summaries = Map.copyOf(summaries);
        }
    }

    void enrichOrderList(List<OrderDTOList> orders);

    Page<BadReviewTask> getDueTasksToAdmin(LocalDate date, String keyword, Pageable pageable);

    Page<BadReviewTask> getDueTasksToOwner(Collection<Manager> managers, LocalDate date, String keyword, Pageable pageable);

    Page<BadReviewTask> getDueTasksToManager(Manager manager, LocalDate date, String keyword, Pageable pageable);

    Page<BadReviewTask> getDueTasksToWorker(Worker worker, LocalDate date, String keyword, Pageable pageable);

    int countDueTasksToAdmin(LocalDate date);

    int countDueTasksToOwner(Collection<Manager> managers, LocalDate date);

    int countDueTasksToManager(Manager manager, LocalDate date);

    int countDueTasksToWorker(Worker worker, LocalDate date);
}

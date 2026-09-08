package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Resolves the existing specialist task types for reports and notification workflows.
 * Application callers own authorization and transactions; a lookup never sends or mutates a task.
 */
@Component
@RequiredArgsConstructor
public class ManagerControlWorkerTaskLookup {
    static final String ENTITY_PUBLISH_REVIEW = "PUBLISH_REVIEW";
    static final String ENTITY_NAGUL_REVIEW = "NAGUL_REVIEW";
    static final String ENTITY_WORKER_ORDER_NEW = "WORKER_ORDER_NEW";
    static final String ENTITY_WORKER_ORDER_CORRECT = "WORKER_ORDER_CORRECT";

    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final WorkerRiskIncidentRepository riskIncidentRepository;
    private final UserRepository userRepository;

    boolean isWorkerTaskConcrete(ManagerDailyControlConcreteItem item) {
        String type = item == null ? "" : safe(item.getEntityType());
        return "BAD_REVIEW_TASK".equals(type) || "RECOVERY_TASK".equals(type);
    }

    boolean isPublishReviewConcrete(ManagerDailyControlConcreteItem item) {
        return ENTITY_PUBLISH_REVIEW.equals(safe(item == null ? null : item.getEntityType()));
    }

    boolean isNagulReviewConcrete(ManagerDailyControlConcreteItem item) {
        return ENTITY_NAGUL_REVIEW.equals(safe(item == null ? null : item.getEntityType()));
    }

    boolean isWorkerFlowOrderConcrete(ManagerDailyControlConcreteItem item) {
        String type = safe(item == null ? null : item.getEntityType());
        return ENTITY_WORKER_ORDER_NEW.equals(type) || ENTITY_WORKER_ORDER_CORRECT.equals(type);
    }

    boolean isWorkerRiskConcrete(ManagerDailyControlConcreteItem item) {
        return "RISK".equals(safe(item == null ? null : item.getEntityType()));
    }

    boolean isSpecialistActionConcrete(ManagerDailyControlConcreteItem item) {
        return isWorkerTaskConcrete(item)
                || isPublishReviewConcrete(item)
                || isNagulReviewConcrete(item)
                || isWorkerFlowOrderConcrete(item)
                || isWorkerRiskConcrete(item);
    }

    Order orderForTask(ManagerDailyControlConcreteItem concreteItem) {
        if (concreteItem == null || concreteItem.getEntityId() == null) {
            return null;
        }
        try {
            return switch (safe(concreteItem.getEntityType())) {
                case "BAD_REVIEW_TASK" -> {
                    BadReviewTask task = badReviewTaskService.getTask(concreteItem.getEntityId());
                    yield task == null ? null : task.getOrder();
                }
                case "RECOVERY_TASK" -> {
                    ReviewRecoveryTask task = reviewRecoveryTaskService.getTask(concreteItem.getEntityId());
                    yield task == null ? null : task.getOrder();
                }
                case ENTITY_PUBLISH_REVIEW, ENTITY_NAGUL_REVIEW -> {
                    Review review = reviewRepository.findById(concreteItem.getEntityId()).orElse(null);
                    yield reviewOrder(review);
                }
                case ENTITY_WORKER_ORDER_NEW, ENTITY_WORKER_ORDER_CORRECT -> orderRepository.findById(concreteItem.getEntityId()).orElse(null);
                case "RISK" -> {
                    WorkerRiskIncident incident = riskIncidentRepository.findById(concreteItem.getEntityId()).orElse(null);
                    yield incident == null || incident.getOrderId() == null
                            ? null
                            : orderRepository.findById(incident.getOrderId()).orElse(null);
                }
                default -> null;
            };
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    User workerUserForTask(ManagerDailyControlConcreteItem concreteItem) {
        if (concreteItem == null || concreteItem.getEntityId() == null) {
            return null;
        }
        try {
            return switch (safe(concreteItem.getEntityType())) {
                case "BAD_REVIEW_TASK" -> {
                    BadReviewTask task = badReviewTaskService.getTask(concreteItem.getEntityId());
                    yield task == null || task.getWorker() == null ? null : task.getWorker().getUser();
                }
                case "RECOVERY_TASK" -> {
                    ReviewRecoveryTask task = reviewRecoveryTaskService.getTask(concreteItem.getEntityId());
                    yield task == null || task.getWorker() == null ? null : task.getWorker().getUser();
                }
                case ENTITY_PUBLISH_REVIEW -> {
                    Review review = reviewRepository.findById(concreteItem.getEntityId()).orElse(null);
                    Worker worker = review == null ? null : review.getWorker();
                    if (worker == null) {
                        Order order = reviewOrder(review);
                        worker = order == null ? null : order.getWorker();
                    }
                    yield worker == null ? null : worker.getUser();
                }
                case ENTITY_NAGUL_REVIEW -> {
                    Review review = reviewRepository.findById(concreteItem.getEntityId()).orElse(null);
                    Worker worker = review == null ? null : review.getWorker();
                    if (worker == null) {
                        Order order = reviewOrder(review);
                        worker = order == null ? null : order.getWorker();
                    }
                    yield worker == null ? null : worker.getUser();
                }
                case "ORDER", ENTITY_WORKER_ORDER_NEW, ENTITY_WORKER_ORDER_CORRECT -> {
                    Order order = orderRepository.findById(concreteItem.getEntityId()).orElse(null);
                    yield order == null || order.getWorker() == null ? null : order.getWorker().getUser();
                }
                case "RISK" -> {
                    WorkerRiskIncident incident = riskIncidentRepository.findById(concreteItem.getEntityId()).orElse(null);
                    yield incident == null || incident.getWorkerUserId() == null
                            ? null
                            : userRepository.findById(incident.getWorkerUserId()).orElse(null);
                }
                default -> null;
            };
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    Long orderIdForTask(ManagerDailyControlConcreteItem concreteItem) {
        if (concreteItem == null || concreteItem.getEntityId() == null) {
            return null;
        }
        try {
            return switch (safe(concreteItem.getEntityType())) {
                case "BAD_REVIEW_TASK" -> {
                    BadReviewTask task = badReviewTaskService.getTask(concreteItem.getEntityId());
                    yield task == null || task.getOrder() == null ? null : task.getOrder().getId();
                }
                case "RECOVERY_TASK" -> {
                    ReviewRecoveryTask task = reviewRecoveryTaskService.getTask(concreteItem.getEntityId());
                    yield task == null || task.getOrder() == null ? null : task.getOrder().getId();
                }
                case ENTITY_PUBLISH_REVIEW -> {
                    Review review = reviewRepository.findById(concreteItem.getEntityId()).orElse(null);
                    Order order = reviewOrder(review);
                    yield order == null ? null : order.getId();
                }
                case ENTITY_NAGUL_REVIEW -> {
                    Review review = reviewRepository.findById(concreteItem.getEntityId()).orElse(null);
                    Order order = reviewOrder(review);
                    yield order == null ? null : order.getId();
                }
                case ENTITY_WORKER_ORDER_NEW, ENTITY_WORKER_ORDER_CORRECT -> concreteItem.getEntityId();
                case "RISK" -> {
                    WorkerRiskIncident incident = riskIncidentRepository.findById(concreteItem.getEntityId()).orElse(null);
                    yield incident == null ? null : incident.getOrderId();
                }
                default -> null;
            };
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    String userDisplayName(User user) {
        String fio = safe(user == null ? null : user.getFio());
        if (!fio.isBlank()) {
            return fio;
        }
        String username = safe(user == null ? null : user.getUsername());
        return username.isBlank() ? "Специалист #" + (user == null ? "-" : user.getId()) : username;
    }

    private Order reviewOrder(Review review) {
        return review == null || review.getOrderDetails() == null ? null : review.getOrderDetails().getOrder();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

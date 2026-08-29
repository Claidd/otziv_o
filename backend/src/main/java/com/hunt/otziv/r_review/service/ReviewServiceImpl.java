package com.hunt.otziv.r_review.service;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.service.BotService;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_categories.service.CategoryService;
import com.hunt.otziv.c_categories.service.SubCategoryService;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.service.FilialService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.contractor_payments.service.ContractorRouteAssignmentGuard;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.review.service.OrderPayableRecalculationService;
import com.hunt.otziv.p_products.service.OrderDetailsService;
import com.hunt.otziv.p_products.service.OrderStatusCheckerService;
import com.hunt.otziv.p_products.service.ProductService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.board.model.ReviewBoardMode;
import com.hunt.otziv.r_review.board.service.ReviewBoardQueryService;
import com.hunt.otziv.r_review.board.model.ReviewBoardScope;
import com.hunt.otziv.r_review.bot.service.ReviewBotChangeService;
import com.hunt.otziv.r_review.bot.service.ReviewAccountWalkScheduleService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.edit.service.ReviewEditService;
import com.hunt.otziv.r_review.mapper.ReviewDtoMapper;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.model.ReviewArchive;
import com.hunt.otziv.r_review.nagul.service.ReviewNagulService;
import com.hunt.otziv.r_review.repository.ReviewArchiveRepository;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.u_users.service.WorkerService;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.util.Pair;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.r_review.utils.ReviewBoardSearch.hasText;
import static com.hunt.otziv.r_review.utils.ReviewPublicationDatePolicy.requireAllowed;
import static com.hunt.otziv.r_review.utils.ReviewPublicationDatePolicy.requireAllowedAfterPrevious;
import static com.hunt.otziv.r_review.utils.ReviewTextPolicy.isBlankOrPlaceholder;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private static final String REVIEW_TEXT_PLACEHOLDER = "Текст отзыва";
    private static final int ARCHIVE_TEXT_UPDATE_BATCH_SIZE = 500;

    private final ReviewRepository reviewRepository;
    private final ReviewArchiveRepository reviewArchiveRepository;
    private final BotService botService;
    private final CategoryService categoryService;
    private final SubCategoryService subCategoryService;
    private final OrderDetailsService orderDetailsService;
    private final WorkerService workerService;
    private final ManagerService managerService;
    private final UserService userService;
    private final ProductService productService;
    private final FilialService filialService;
    private final ReviewDtoMapper reviewDtoMapper;
    private final ReviewBoardQueryService reviewBoardQueryService;
    private final ReviewNagulService reviewNagulService;
    private final ReviewBotChangeService reviewBotChangeService;
    private final ReviewAccountWalkScheduleService reviewAccountWalkScheduleService;
    private final ReviewEditService reviewEditService;
    private final OrderStatusCheckerService orderStatusCheckerService;
    private final BusinessAuditService businessAuditService;
    private final GamificationEventService gamificationEventService;
    private final OrderAggregateMutationLockService orderAggregateMutationLockService;
    private final WorkerAssignmentMutationGuardService assignmentMutationGuardService;
    private final ContractorRouteAssignmentGuard contractorRouteAssignmentGuard;
    private final OrderPayableRecalculationService payableRecalculationService;

    @Override
    public Map<Long, Integer> countOrdersByWorkerIdsAndStatusPublish(List<Long> workerIds, LocalDate localDate) {
        if (workerIds == null || workerIds.isEmpty()) {
            return Map.of();
        }

        return reviewRepository.countByWorkerIdsAndStatusPublish(workerIds, localDate)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> ((Long) row[1]).intValue()
                ));
    }

    @Override
    public Map<Long, Integer> countOrdersByWorkerIdsAndStatusVigul(List<Long> workerIds, LocalDate localDate) {
        if (workerIds == null || workerIds.isEmpty()) {
            return Map.of();
        }

        return reviewRepository.countByWorkerIdsAndStatusVigul(workerIds, localDate)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> ((Long) row[1]).intValue()
                ));
    }

    @Override
    public int countReviewsForWorkerUserId(Long userId) {
        return reviewRepository.countReviewsForWorkerUserId(userId);
    }

    @Override
    public int countBoardReviewsToPublish(LocalDate localDate, Principal principal, String role) {
        return countBoardReviews(ReviewBoardMode.PUBLISH, localDate, null, principal, role);
    }

    @Override
    public int countBoardReviewsToVigul(LocalDate localDate, Principal principal, String role) {
        return countBoardReviews(ReviewBoardMode.VIGUL, localDate, null, principal, role);
    }

    @Override
    public int countBoardReviewsByOrderStatus(String status, Principal principal, String role) {
        return countBoardReviews(ReviewBoardMode.ORDER_STATUS, null, status, principal, role);
    }

    @Override
    public Map<String, Integer> countBoardReviewMetrics(
            LocalDate publishDate,
            LocalDate vigulDate,
            String badStatus,
            Principal principal,
            String role
    ) {
        return countBoardReviewMetrics(ReviewBoardScope.fromRole(role), publishDate, vigulDate, badStatus, principal);
    }

    public Map<Long, Integer> countOrdersByWorkerIdsAndStatusPublish(Collection<Long> workerIds, LocalDate localDate) {
        if (workerIds == null || workerIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Integer> result = new HashMap<>();

        for (Object[] row : reviewRepository.countByWorkerIdsAndStatusPublish(workerIds, localDate)) {
            Long workerId = (Long) row[0];
            Long count = (Long) row[1];
            result.put(workerId, count.intValue());
        }

        return result;
    }

    public Map<Long, Integer> countOrdersByWorkerIdsAndStatusVigul(Collection<Long> workerIds, LocalDate localDate) {
        if (workerIds == null || workerIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Integer> result = new HashMap<>();

        for (Object[] row : reviewRepository.countByWorkerIdsAndStatusVigul(workerIds, localDate)) {
            Long workerId = (Long) row[0];
            Long count = (Long) row[1];
            result.put(workerId, count.intValue());
        }

        return result;
    }

    public Page<ReviewDTOOne> getAllReviewDTOAndDateToAdmin(LocalDate localDate, int pageNumber, int pageSize) {
        return getAllReviewDTOAndDateToAdmin(localDate, pageNumber, pageSize, "asc");
    }

    public Page<ReviewDTOOne> getAllReviewDTOAndDateToAdmin(LocalDate localDate, int pageNumber, int pageSize, String sortDirection) {
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(
                ReviewBoardMode.PUBLISH, ReviewBoardScope.ADMIN,
                localDate, null, null, null, null, "", pageNumber, pageSize, sortDirection
        ));
    }

    public Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublish(LocalDate localDate, Principal principal, int pageNumber, int pageSize) {
        return getAllReviewDTOByWorkerByPublish(localDate, principal, pageNumber, pageSize, "asc");
    }

    public Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublish(LocalDate localDate, Principal principal, int pageNumber, int pageSize, String sortDirection) {
        User user = requireUser(principal);
        Worker worker = workerService.getWorkerByUserId(user.getId());
        if (worker == null) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }

        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(
                ReviewBoardMode.PUBLISH, ReviewBoardScope.WORKER,
                localDate, null, worker, null, null, "", pageNumber, pageSize, sortDirection
        ));
    }

    public Page<ReviewDTOOne> getAllReviewDTOByManagerByPublish(LocalDate localDate, Principal principal, int pageNumber, int pageSize) {
        return getAllReviewDTOByManagerByPublish(localDate, principal, pageNumber, pageSize, "asc");
    }

    public Page<ReviewDTOOne> getAllReviewDTOByManagerByPublish(LocalDate localDate, Principal principal, int pageNumber, int pageSize, String sortDirection) {
        User user = requireUser(principal);
        Manager manager = managerService.getManagerByUserId(user.getId());
        if (manager == null || manager.getUser() == null || manager.getUser().getWorkers() == null) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }

        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(
                ReviewBoardMode.PUBLISH, ReviewBoardScope.MANAGER,
                localDate, null, null, manager, manager.getUser().getWorkers(), "", pageNumber, pageSize, sortDirection
        ));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublish(LocalDate localDate, Principal principal, int pageNumber, int pageSize) {
        return getAllReviewDTOByOwnerByPublish(localDate, principal, pageNumber, pageSize, "asc");
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublish(LocalDate localDate, Principal principal, int pageNumber, int pageSize, String sortDirection) {
        User user = requireUser(principal);
        List<Manager> managerList = user.getManagers() == null ? List.of() : user.getManagers().stream().toList();
        if (managerList.isEmpty()) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }

        Set<Worker> workerList = workerService.getAllWorkersToManagerList(managerList);
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(
                ReviewBoardMode.PUBLISH, ReviewBoardScope.OWNER,
                localDate, null, null, null, workerList, "", pageNumber, pageSize, sortDirection
        ));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOrderStatusToAdmin(String status, int pageNumber, int pageSize) {
        return getAllReviewDTOByOrderStatusToAdmin(status, pageNumber, pageSize, "asc");
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOrderStatusToAdmin(String status, int pageNumber, int pageSize, String sortDirection) {
        Pageable pageable = reviewBoardQueryService.reviewPageable(pageNumber, pageSize, sortDirection);
        Page<Long> reviewIds = reviewRepository.findPageIdsByOrderStatus(status, pageable);
        return getReviewDTOPage(reviewIds);
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByWorkerByOrderStatus(String status, Principal principal, int pageNumber, int pageSize) {
        return getAllReviewDTOByWorkerByOrderStatus(status, principal, pageNumber, pageSize, "asc");
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByWorkerByOrderStatus(String status, Principal principal, int pageNumber, int pageSize, String sortDirection) {
        User user = requireUser(principal);
        Worker worker = workerService.getWorkerByUserId(user.getId());
        if (worker == null) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }

        Pageable pageable = reviewBoardQueryService.reviewPageable(pageNumber, pageSize, sortDirection);
        Page<Long> reviewIds = reviewRepository.findPageIdsByWorkerAndOrderStatus(worker, status, pageable);
        return getReviewDTOPage(reviewIds);
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByManagerByOrderStatus(String status, Principal principal, int pageNumber, int pageSize) {
        return getAllReviewDTOByManagerByOrderStatus(status, principal, pageNumber, pageSize, "asc");
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByManagerByOrderStatus(String status, Principal principal, int pageNumber, int pageSize, String sortDirection) {
        User user = requireUser(principal);
        Manager manager = managerService.getManagerByUserId(user.getId());
        if (manager == null || manager.getUser() == null || manager.getUser().getWorkers() == null) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }

        Pageable pageable = reviewBoardQueryService.reviewPageable(pageNumber, pageSize, sortDirection);
        Page<Long> reviewIds = reviewRepository.findPageIdsByManagerAndOrderStatus(
                manager.getUser().getWorkers(), manager, status, pageable);
        return getReviewDTOPage(reviewIds);
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOwnerByOrderStatus(String status, Principal principal, int pageNumber, int pageSize) {
        return getAllReviewDTOByOwnerByOrderStatus(status, principal, pageNumber, pageSize, "asc");
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOwnerByOrderStatus(String status, Principal principal, int pageNumber, int pageSize, String sortDirection) {
        User user = requireUser(principal);
        List<Manager> managerList = user.getManagers() == null ? List.of() : user.getManagers().stream().toList();
        if (managerList.isEmpty()) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }

        Set<Worker> workerList = workerService.getAllWorkersToManagerList(managerList);
        Pageable pageable = reviewBoardQueryService.reviewPageable(pageNumber, pageSize, sortDirection);
        Page<Long> reviewIds = reviewRepository.findPageIdsByWorkersAndOrderStatus(workerList, status, pageable);
        return getReviewDTOPage(reviewIds);
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOAndDateToAdmin(LocalDate localDate, int pageNumber, int pageSize, String sortDirection, String keyword) {
        if (!hasText(keyword)) {
            return getAllReviewDTOAndDateToAdmin(localDate, pageNumber, pageSize, sortDirection);
        }
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(ReviewBoardMode.PUBLISH, ReviewBoardScope.ADMIN,
                localDate, null, null, null, null, keyword, pageNumber, pageSize, sortDirection));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublish(LocalDate localDate, Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword) {
        if (!hasText(keyword)) {
            return getAllReviewDTOByWorkerByPublish(localDate, principal, pageNumber, pageSize, sortDirection);
        }
        User user = requireUser(principal);
        Worker worker = workerService.getWorkerByUserId(user.getId());
        if (worker == null) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(ReviewBoardMode.PUBLISH, ReviewBoardScope.WORKER,
                localDate, null, worker, null, null, keyword, pageNumber, pageSize, sortDirection));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublish(
            Worker worker,
            LocalDate localDate,
            int pageNumber,
            int pageSize,
            String sortDirection,
            String keyword
    ) {
        if (worker == null) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }
        if (!hasText(keyword)) {
            return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(
                    ReviewBoardMode.PUBLISH, ReviewBoardScope.WORKER,
                    localDate, null, worker, null, null, "", pageNumber, pageSize, sortDirection
            ));
        }
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(ReviewBoardMode.PUBLISH, ReviewBoardScope.WORKER,
                localDate, null, worker, null, null, keyword, pageNumber, pageSize, sortDirection));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByManagerByPublish(LocalDate localDate, Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword) {
        if (!hasText(keyword)) {
            return getAllReviewDTOByManagerByPublish(localDate, principal, pageNumber, pageSize, sortDirection);
        }
        User user = requireUser(principal);
        Manager manager = managerService.getManagerByUserId(user.getId());
        if (manager == null || manager.getUser() == null || manager.getUser().getWorkers() == null) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(ReviewBoardMode.PUBLISH, ReviewBoardScope.MANAGER,
                localDate, null, null, manager, manager.getUser().getWorkers(), keyword, pageNumber, pageSize, sortDirection));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublish(LocalDate localDate, Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword) {
        if (!hasText(keyword)) {
            return getAllReviewDTOByOwnerByPublish(localDate, principal, pageNumber, pageSize, sortDirection);
        }
        User user = requireUser(principal);
        List<Manager> managerList = user.getManagers() == null ? List.of() : user.getManagers().stream().toList();
        if (managerList.isEmpty()) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }
        Set<Worker> workers = workerService.getAllWorkersToManagerList(managerList);
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(ReviewBoardMode.PUBLISH, ReviewBoardScope.OWNER,
                localDate, null, null, null, workers, keyword, pageNumber, pageSize, sortDirection));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOrderStatusToAdmin(String status, int pageNumber, int pageSize, String sortDirection, String keyword) {
        if (!hasText(keyword)) {
            return getAllReviewDTOByOrderStatusToAdmin(status, pageNumber, pageSize, sortDirection);
        }
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(ReviewBoardMode.ORDER_STATUS, ReviewBoardScope.ADMIN,
                null, status, null, null, null, keyword, pageNumber, pageSize, sortDirection));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByWorkerByOrderStatus(String status, Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword) {
        if (!hasText(keyword)) {
            return getAllReviewDTOByWorkerByOrderStatus(status, principal, pageNumber, pageSize, sortDirection);
        }
        User user = requireUser(principal);
        Worker worker = workerService.getWorkerByUserId(user.getId());
        if (worker == null) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(ReviewBoardMode.ORDER_STATUS, ReviewBoardScope.WORKER,
                null, status, worker, null, null, keyword, pageNumber, pageSize, sortDirection));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByWorkerByOrderStatus(
            Worker worker,
            String status,
            int pageNumber,
            int pageSize,
            String sortDirection,
            String keyword
    ) {
        if (worker == null) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }
        if (!hasText(keyword)) {
            Pageable pageable = reviewBoardQueryService.reviewPageable(pageNumber, pageSize, sortDirection);
            return getReviewDTOPage(reviewRepository.findPageIdsByWorkerAndOrderStatus(worker, status, pageable));
        }
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(ReviewBoardMode.ORDER_STATUS, ReviewBoardScope.WORKER,
                null, status, worker, null, null, keyword, pageNumber, pageSize, sortDirection));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByManagerByOrderStatus(String status, Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword) {
        if (!hasText(keyword)) {
            return getAllReviewDTOByManagerByOrderStatus(status, principal, pageNumber, pageSize, sortDirection);
        }
        User user = requireUser(principal);
        Manager manager = managerService.getManagerByUserId(user.getId());
        if (manager == null || manager.getUser() == null || manager.getUser().getWorkers() == null) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(ReviewBoardMode.ORDER_STATUS, ReviewBoardScope.MANAGER,
                null, status, null, manager, manager.getUser().getWorkers(), keyword, pageNumber, pageSize, sortDirection));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOwnerByOrderStatus(String status, Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword) {
        if (!hasText(keyword)) {
            return getAllReviewDTOByOwnerByOrderStatus(status, principal, pageNumber, pageSize, sortDirection);
        }
        User user = requireUser(principal);
        List<Manager> managerList = user.getManagers() == null ? List.of() : user.getManagers().stream().toList();
        if (managerList.isEmpty()) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }
        Set<Worker> workers = workerService.getAllWorkersToManagerList(managerList);
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(ReviewBoardMode.ORDER_STATUS, ReviewBoardScope.OWNER,
                null, status, null, null, workers, keyword, pageNumber, pageSize, sortDirection));
    }

    private Page<ReviewDTOOne> getReviewDTOPage(Page<Long> reviewIds) {
        if (reviewIds.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), reviewIds.getPageable(), reviewIds.getTotalElements());
        }

        List<Long> ids = reviewIds.getContent();
        Map<Long, Integer> orderById = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            orderById.put(ids.get(i), i);
        }

        List<ReviewDTOOne> reviewDTOOnes = reviewRepository.findAll(ids).stream()
                .sorted(Comparator.comparingInt(review -> orderById.getOrDefault(review.getId(), Integer.MAX_VALUE)))
                .map(review -> {
                    try {
                        return toReviewDTOOne(review);
                    } catch (Exception e) {
                        log.error("Ошибка при преобразовании отзыва ID {} в DTO: {}",
                                review.getId(), e.getMessage(), e);
                        return ReviewDTOOne.builder()
                                .id(review.getId())
                                .companyTitle("ОШИБКА ПРИ ОБРАБОТКЕ")
                                .botFio("ОШИБКА")
                                .text(review.getText() != null ? review.getText() : "")
                                .build();
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return new PageImpl<>(reviewDTOOnes, reviewIds.getPageable(), reviewIds.getTotalElements());
    }

    private int countBoardReviews(
            ReviewBoardMode mode,
            LocalDate localDate,
            String status,
            Principal principal,
            String role
    ) {
        if (mode == ReviewBoardMode.ORDER_STATUS && !hasText(status)) {
            return 0;
        }

        ReviewBoardScope scope = ReviewBoardScope.fromRole(role);
        Worker worker = null;
        Manager manager = null;
        Set<Worker> workers = null;

        switch (scope) {
            case WORKER -> {
                User user = requireUser(principal);
                worker = workerService.getWorkerByUserId(user.getId());
                if (worker == null) {
                    return 0;
                }
            }
            case MANAGER -> {
                User user = requireUser(principal);
                manager = managerService.getManagerByUserId(user.getId());
                if (manager == null || manager.getUser() == null || manager.getUser().getWorkers() == null) {
                    return 0;
                }
                workers = manager.getUser().getWorkers();
            }
            case OWNER -> {
                User user = requireUser(principal);
                List<Manager> managerList = user.getManagers() == null ? List.of() : user.getManagers().stream().toList();
                if (managerList.isEmpty()) {
                    return 0;
                }
                workers = workerService.getAllWorkersToManagerList(managerList);
            }
            case ADMIN -> {
            }
        }

        if ((scope == ReviewBoardScope.OWNER || scope == ReviewBoardScope.MANAGER) && (workers == null || workers.isEmpty())) {
            return 0;
        }

        return toIntCount(reviewBoardQueryService.countReviewIdsForBoard(mode, scope, localDate, status, worker, manager, workers));
    }

    private Map<String, Integer> countBoardReviewMetrics(
            ReviewBoardScope scope,
            LocalDate publishDate,
            LocalDate vigulDate,
            String badStatus,
            Principal principal
    ) {
        Worker worker = null;
        Manager manager = null;
        Set<Worker> workers = null;

        switch (scope) {
            case WORKER -> {
                User user = requireUser(principal);
                worker = workerService.getWorkerByUserId(user.getId());
                if (worker == null) {
                    return Map.of();
                }
            }
            case MANAGER -> {
                User user = requireUser(principal);
                manager = managerService.getManagerByUserId(user.getId());
                if (manager == null || manager.getUser() == null || manager.getUser().getWorkers() == null) {
                    return Map.of();
                }
                workers = manager.getUser().getWorkers();
            }
            case OWNER -> {
                User user = requireUser(principal);
                List<Manager> managerList = user.getManagers() == null ? List.of() : user.getManagers().stream().toList();
                if (managerList.isEmpty()) {
                    return Map.of();
                }
                workers = workerService.getAllWorkersToManagerList(managerList);
            }
            case ADMIN -> {
            }
        }

        if ((scope == ReviewBoardScope.OWNER || scope == ReviewBoardScope.MANAGER) && (workers == null || workers.isEmpty())) {
            return Map.of();
        }

        Map<String, Integer> result = new HashMap<>();
        result.put("publish", toIntCount(reviewBoardQueryService.countReviewIdsForBoard(
                ReviewBoardMode.PUBLISH, scope, publishDate, null, worker, manager, workers)));
        result.put("nagul", toIntCount(reviewBoardQueryService.countReviewIdsForBoard(
                ReviewBoardMode.VIGUL, scope, vigulDate, null, worker, manager, workers)));
        result.put("bad", toIntCount(reviewBoardQueryService.countReviewIdsForBoard(
                ReviewBoardMode.ORDER_STATUS, scope, null, badStatus, worker, manager, workers)));
        return result;
    }

    private int toIntCount(long count) {
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    @Transactional
    public void deleteAllByIdIn(List<Long> reviewIds) {
        if (reviewIds != null && !reviewIds.isEmpty()) {
            log.debug("Удаление отзывов по списку ID: {}", reviewIds);
            int deletedCount = reviewRepository.deleteByIdIn(reviewIds);
            log.info("Удалено {} отзывов из базы данных", deletedCount);
        }
    }

    private Page<ReviewDTOOne> getPageReviews(List<Review> reviewPage, int pageNumber, int pageSize) {
        if (reviewPage.isEmpty()) {
            Pageable pageable = PageRequest.of(pageNumber, pageSize, reviewPublishedDateSortDescending());
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        int totalElements = reviewPage.size();
        int maxPageNumber = (int) Math.ceil((double) totalElements / pageSize) - 1;

        if (pageNumber > maxPageNumber) {
            pageNumber = Math.max(maxPageNumber, 0);
        }
        if (pageNumber < 0) {
            pageNumber = 0;
        }

        Pageable pageable = PageRequest.of(pageNumber, pageSize, reviewPublishedDateSortDescending());
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), totalElements);

        if (start >= totalElements) {
            start = 0;
            end = Math.min(pageSize, totalElements);
            pageable = PageRequest.of(0, pageSize, reviewPublishedDateSortDescending());
        }

        List<ReviewDTOOne> reviewDTOOnes = reviewPage.subList(start, end)
                .stream()
                .map(review -> {
                    try {
                        return toReviewDTOOne(review);
                    } catch (Exception e) {
                        log.error("Ошибка при преобразовании отзыва ID {} в DTO: {}",
                                review.getId(), e.getMessage(), e);
                        return ReviewDTOOne.builder()
                                .id(review.getId())
                                .companyTitle("ОШИБКА ПРИ ОБРАБОТКЕ")
                                .botFio("ОШИБКА")
                                .text(review.getText() != null ? review.getText() : "")
                                .build();
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return new PageImpl<>(reviewDTOOnes, pageable, totalElements);
    }

    private Comparator<Review> reviewPublishedDateComparator() {
        return Comparator
                .comparing(Review::getPublishedDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Review::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private Sort reviewPublishedDateSortDescending() {
        return Sort.by("publishedDate").descending().and(Sort.by("id").descending());
    }

    public boolean hasActiveNagulReviews(Principal principal) {
        return reviewNagulService.hasActiveNagulReviews(principal);
    }

    @Override
    public List<Review> saveAll(List<Review> reviews) {
        return (List<Review>) reviewRepository.saveAll(reviews);
    }

    @Transactional
    public Review save(Review review) {
        if (review == null) {
            return null;
        }
        if (review.getId() != null) {
            orderAggregateMutationLockService.lockForReview(review.getId());
            assignmentMutationGuardService.assertReview(review.getId());
        }

        if (!reviewRepository.existsByText(review.getText())) {
            log.info("1. Отзыв в БД отзывы сохранен");
            return reviewRepository.save(review);
        }
        if ("Текст отзыва".equals(review.getText())) {
            log.info("1. Отзыв в БД отзывы сохранен как шаблон");
            return reviewRepository.save(review);
        }
        log.info("1. Отзыв в БД отзывы НЕ сохранен, так как такой текст уже есть и это не шаблон");
        return review;
    }

    @Override
    @Transactional
    public Review updateReviewPhoto(Long reviewId, String url) {
        orderAggregateMutationLockService.lockForReview(reviewId);
        assignmentMutationGuardService.assertReview(reviewId);
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new UsernameNotFoundException(String.format("Отзыв '%d' не найден", reviewId)));

        review.setUrl(url);
        log.info("Обновляем фото отзыва {}", reviewId);
        return reviewRepository.save(review);
    }

    @Transactional
    public boolean deleteReview(Long reviewId) {
        orderAggregateMutationLockService.lockForReview(reviewId);
        assignmentMutationGuardService.assertReview(reviewId);
        Review review = reviewRepository.findById(reviewId).orElse(null);
        if (review == null) {
            return false;
        }
        Long orderId = orderId(review);
        contractorRouteAssignmentGuard.requirePayableMutationAllowed(orderId);
        OrderDetails orderDetails = review.getOrderDetails();
        reviewRepository.delete(review);
        if (orderDetails != null) {
            List<Review> remaining = new ArrayList<>(safeReviews(orderDetails));
            remaining.removeIf(candidate -> Objects.equals(reviewId, candidate.getId()));
            orderDetails.setReviews(remaining);
            payableRecalculationService.recalculate(orderDetails);
        }
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean deleteReviewFromLockedOrder(Long orderId, Long reviewId) {
        if (orderId == null || reviewId == null) {
            return false;
        }
        contractorRouteAssignmentGuard.requirePayableMutationAllowed(orderId);
        Review review = reviewRepository.findById(reviewId).orElse(null);
        Order actualOrder = extractOrder(review);
        if (review == null || actualOrder == null || !Objects.equals(orderId, actualOrder.getId())) {
            return false;
        }
        reviewRepository.delete(review);
        return true;
    }

    @Override
    public List<Review> getReviewsAllByOrderDetailsId(UUID orderDetailsId) {
        return reviewRepository.findAllByOrderDetailsId(orderDetailsId);
    }

    public List<Review> getAllWorkerReviews(Long workerId) {
        List<Long> reviewId = getReviewByWorkerId(workerId);
        return findAllByListId(reviewId);
    }


    @Override
    public List<Long> getReviewByWorkerId(Long workerId) {
        return reviewRepository.findAllIdByWorkerId(workerId);
    }

    @Override
    public List<Review> findAllByListId(List<Long> reviewId) {
        if (reviewId == null || reviewId.isEmpty()) {
            return Collections.emptyList();
        }
        return reviewRepository.findAll(reviewId);
    }

    public List<ReviewDTOOne> getReviewsAllByOrderId(Long orderId) {
        return reviewRepository.getAllByOrderId(orderId).stream().map(this::toReviewDTOOne).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void updateReview(String userRole, ReviewDTO reviewDTO, Long reviewId) {
        log.info("2. Вошли в обновление данных Отзыв");
        orderAggregateMutationLockService.lockForReview(reviewId);
        assignmentMutationGuardService.assertReview(reviewId);
        Review saveReview = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new UsernameNotFoundException(String.format("Отзыв '%d' не найден", reviewId)));

        boolean isChanged = false;
        boolean publishChanged = false;
        String oldText = saveReview.getText();
        LocalDate oldPublishedDate = saveReview.getPublishedDate();
        boolean oldPublish = saveReview.isPublish();

        Product dtoProduct = reviewDTO.getProduct();
        Product currentProduct = saveReview.getProduct();
        Long dtoProductId = dtoProduct != null ? dtoProduct.getId() : null;
        Long currentProductId = currentProduct != null ? currentProduct.getId() : null;
        Filial currentFilial = saveReview.getFilial();
        Long dtoFilialId = reviewDTO.getFilial() != null ? reviewDTO.getFilial().getId() : null;
        Long currentFilialId = currentFilial != null ? currentFilial.getId() : null;
        boolean reassignBotAfterSave = false;
        boolean productChanged = false;
        if (dtoProduct != null && currentProduct != null) {
            if (!Objects.equals(dtoProductId, currentProductId)) {
                productChanged = true;
            }
        } else if (dtoProduct != null || currentProduct != null) {
            productChanged = true;
        }
        boolean filialChanged = dtoFilialId != null && !Objects.equals(dtoFilialId, currentFilialId);
        boolean publishFlagChanged = ("ROLE_ADMIN".equals(userRole) || "ROLE_OWNER".equals(userRole))
                && !Objects.equals(reviewDTO.isPublish(), saveReview.isPublish());
        boolean publishedDateChanged = !Objects.equals(reviewDTO.getPublishedDate(), saveReview.getPublishedDate());
        if (productChanged || filialChanged || publishFlagChanged || publishedDateChanged) {
            contractorRouteAssignmentGuard.requirePayableMutationAllowed(orderId(saveReview));
        }

        log.info("text: {}", !Objects.equals(reviewDTO.getText(), saveReview.getText()));
        log.info("answer: {}", !Objects.equals(reviewDTO.getAnswer(), saveReview.getAnswer()));
        log.info("comment: {}", !Objects.equals(reviewDTO.getComment(), extractComment(saveReview.getOrderDetails())));
        log.info("url: {}", !Objects.equals(reviewDTO.getUrl(), saveReview.getUrl()));
        log.info("date publish: {}", !Objects.equals(reviewDTO.getPublishedDate(), saveReview.getPublishedDate()));
        log.info("date isPublish: {}", !Objects.equals(reviewDTO.isPublish(), saveReview.isPublish()));
        log.info("Выгул: {}", !Objects.equals(reviewDTO.isVigul(), saveReview.isVigul()));
        log.info("product id: {}", !Objects.equals(dtoProductId, currentProductId));
        log.info("filial id: {}", !Objects.equals(dtoFilialId, currentFilialId));

        if (!Objects.equals(reviewDTO.getText(), saveReview.getText())) {
            log.info("Обновляем текст отзыва");
            saveReview.setText(reviewDTO.getText());
            isChanged = true;
        }

        Bot currentBot = saveReview.getBot();
        String currentBotName = currentBot != null ? currentBot.getFio() : null;

        if (!Objects.equals(reviewDTO.getBotName(), currentBotName)) {
            log.info("Обновляем Имя Бота");
            if (currentBot != null) {
                currentBot.setFio(reviewDTO.getBotName());
                botService.save(currentBot);
            } else {
                log.warn("У отзыва ID {} нет бота. Имя бота не может быть обновлено", reviewId);
            }
        }

        String currentBotPassword = currentBot != null ? currentBot.getPassword() : null;
        if (!Objects.equals(reviewDTO.getBotPassword(), currentBotPassword)) {
            log.info("Обновляем Пароль Бота");
            if (currentBot != null && reviewDTO.getBotPassword() != null && !reviewDTO.getBotPassword().isEmpty()) {
                currentBot.setPassword(reviewDTO.getBotPassword());
                botService.save(currentBot);
            } else if (currentBot == null) {
                log.warn("У отзыва ID {} нет бота. Пароль бота не может быть обновлен", reviewId);
            }
        }

        if (!Objects.equals(reviewDTO.getAnswer(), saveReview.getAnswer())) {
            log.info("Обновляем ответ на отзыв");
            saveReview.setAnswer(reviewDTO.getAnswer());
            isChanged = true;
        }

        if (!Objects.equals(reviewDTO.getComment(), extractComment(saveReview.getOrderDetails()))) {
            log.info("Обновляем комментарий отзыва");
            OrderDetails orderDetails = orderDetailsService.getOrderDetailById(reviewDTO.getOrderDetailsId());
            orderDetails.setComment(reviewDTO.getComment());
            orderDetailsService.save(orderDetails);
            isChanged = true;
        }

        if (!Objects.equals(reviewDTO.getUrl(), saveReview.getUrl())) {
            log.info("Обновляем url отзыва");
            saveReview.setUrl(reviewDTO.getUrl());
            isChanged = true;
        }

        if (filialChanged) {
            log.info("Обновляем филиал отзыва");
            Filial newFilial = filialService.getFilial(dtoFilialId);
            validateReviewFilial(saveReview, newFilial);
            reassignBotAfterSave = filialCityChanged(currentFilial, newFilial);
            saveReview.setFilial(newFilial);
            isChanged = true;
        }

        if (productChanged) {
            log.info("Обновляем продукт отзыва");

            if (dtoProduct != null) {
                Product product = productService.findById(dtoProductId);
                saveReview.setProduct(product);
                saveReview.setPrice(product.getPrice());
            } else {
                saveReview.setProduct(null);
                saveReview.setPrice(null);
            }

            reviewRepository.save(saveReview);

            payableRecalculationService.recalculate(saveReview.getOrderDetails());
        }

        if (publishFlagChanged) {
            log.info("Обновляем публикацию отзыва");
            saveReview.setPublish(reviewDTO.isPublish());
            syncExternalConfirmationState(saveReview, oldPublish);
            isChanged = true;
            publishChanged = true;
        }

        if (canManageReviewVigul(userRole)) {
            if (!Objects.equals(reviewDTO.isVigul(), saveReview.isVigul())) {
                log.info("Обновляем выгул отзыва");
                saveReview.setVigul(reviewDTO.isVigul());
                isChanged = true;
            }
        } else if (canWorkerUnsetReviewVigul(userRole, reviewDTO, saveReview)) {
            log.info("Специалист снимает выгул отзыва");
            saveReview.setVigul(false);
            isChanged = true;
        }

        if ("ROLE_ADMIN".equals(userRole) || "ROLE_OWNER".equals(userRole) || "ROLE_MANAGER".equals(userRole)) {
            if (reviewDTO.getCreated() != null && !Objects.equals(reviewDTO.getCreated(), saveReview.getCreated())) {
                log.info("Обновляем дату создания отзыва");
                saveReview.setCreated(reviewDTO.getCreated());
                isChanged = true;
            }

            if (reviewDTO.getChanged() != null && !Objects.equals(reviewDTO.getChanged(), saveReview.getChanged())) {
                log.info("Обновляем дату изменения отзыва");
                saveReview.setChanged(reviewDTO.getChanged());
                isChanged = true;
            }
        }

        if (publishedDateChanged) {
            requireWorkerPublicationDatePermission(userRole, saveReview);
            requirePublicationDateIntegrity(saveReview, reviewDTO.getPublishedDate());
            validateManualPublicationDate(saveReview, reviewDTO.getPublishedDate());
            log.info("Обновляем дату публикации отзыва");
            saveReview.setPublishedDate(reviewDTO.getPublishedDate());
            isChanged = true;
        }

        if (isChanged) {
            reviewRepository.save(saveReview);
            recordReviewAudit(saveReview, oldText, oldPublishedDate, oldPublish);
        }
        if (reassignBotAfterSave) {
            reviewBotChangeService.changeBot(reviewId);
        }
        if (publishChanged) {
            synchronizeOrderCounter(saveReview);
            if (saveReview.isPublish()) {
                gamificationEventService.recordReviewPublished(saveReview);
            }
        }
    }

    private void requireWorkerPublicationDatePermission(String userRole, Review review) {
        if (!"ROLE_WORKER".equals(userRole)) {
            return;
        }

        boolean allowed = Optional.ofNullable(review)
                .map(Review::getOrderDetails)
                .map(OrderDetails::getOrder)
                .map(Order::getCompany)
                .map(company -> company.isAllowWorkerPublicationDateEdit())
                .orElse(false);
        if (!allowed) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Для смены даты публикации обратитесь к менеджеру"
            );
        }
    }

    private void recordReviewAudit(Review review, String oldText, LocalDate oldPublishedDate, boolean oldPublish) {
        Long orderId = Optional.ofNullable(review)
                .map(Review::getOrderDetails)
                .map(OrderDetails::getOrder)
                .map(Order::getId)
                .orElse(null);

        if (!Objects.equals(oldText, review.getText())) {
            businessAuditService.recordSafely(
                    "review_text_changed",
                    "review",
                    review.getId(),
                    orderId,
                    review.getId(),
                    oldText,
                    review.getText(),
                    null
            );
        }
        if (!Objects.equals(oldPublishedDate, review.getPublishedDate())) {
            businessAuditService.recordSafely(
                    "review_publish_date_changed",
                    "review",
                    review.getId(),
                    orderId,
                    review.getId(),
                    oldPublishedDate,
                    review.getPublishedDate(),
                    null
            );
        }
        if (!Objects.equals(oldPublish, review.isPublish())) {
            businessAuditService.recordSafely(
                    "review_publish_flag_changed",
                    "review",
                    review.getId(),
                    orderId,
                    review.getId(),
                    oldPublish,
                    review.isPublish(),
                    null
            );
        }
    }

    private void validateReviewFilial(Review review, Filial filial) {
        if (filial == null || filial.getId() == null) {
            throw new IllegalArgumentException("Филиал не найден");
        }

        Long orderCompanyId = Optional.ofNullable(review)
                .map(Review::getOrderDetails)
                .map(OrderDetails::getOrder)
                .map(Order::getCompany)
                .map(company -> company.getId())
                .orElse(null);
        Long filialCompanyId = Optional.of(filial)
                .map(Filial::getCompany)
                .map(company -> company.getId())
                .orElse(null);

        if (orderCompanyId == null || filialCompanyId == null || !Objects.equals(orderCompanyId, filialCompanyId)) {
            throw new IllegalArgumentException("Филиал не принадлежит компании заказа");
        }
    }

    private boolean filialCityChanged(Filial currentFilial, Filial newFilial) {
        Long currentCityId = currentFilial != null && currentFilial.getCity() != null
                ? currentFilial.getCity().getId()
                : null;
        Long newCityId = newFilial != null && newFilial.getCity() != null
                ? newFilial.getCity().getId()
                : null;
        return !Objects.equals(currentCityId, newCityId);
    }

    private boolean canManageReviewVigul(String userRole) {
        return "ROLE_ADMIN".equals(userRole) || "ROLE_OWNER".equals(userRole) || "ROLE_MANAGER".equals(userRole);
    }

    private boolean canWorkerUnsetReviewVigul(String userRole, ReviewDTO reviewDTO, Review review) {
        return "ROLE_WORKER".equals(userRole) && review.isVigul() && !reviewDTO.isVigul();
    }

    private void synchronizeOrderCounter(Review review) {
        Order order = Optional.ofNullable(review)
                .map(Review::getOrderDetails)
                .map(OrderDetails::getOrder)
                .orElse(null);
        if (order == null || order.getId() == null) {
            log.warn("Не удалось синхронизировать счетчик: у отзыва {} нет заказа",
                    review != null ? review.getId() : null);
            return;
        }

        int actualPublished = reviewRepository.countPublishedByOrderId(order.getId());
        orderStatusCheckerService.validateCounterConsistency(order, actualPublished);
    }

    private void syncExternalConfirmationState(Review review, boolean oldPublish) {
        if (review == null) {
            return;
        }
        if (!oldPublish && review.isPublish()) {
            review.setPublishedMarkedAt(LocalDateTime.now());
            review.setExternalConfirmStatus("PENDING");
            review.setExternalConfirmedAt(null);
            review.setExternalConfirmScreenshotUrl(null);
        } else if (oldPublish && !review.isPublish()) {
            review.setPublishedMarkedAt(null);
            review.setExternalConfirmStatus("PENDING");
            review.setExternalConfirmedAt(null);
            review.setExternalConfirmScreenshotUrl(null);
        }
    }

    @Override
    public int findAllByReviewListStatus(String username) {
        Worker worker = workerService.getWorkerByUserId(userService.findByUserName(username).orElseThrow().getId());
        LocalDate localDate = LocalDate.now();
        return reviewRepository.findAllByReviewsListStatus(localDate, worker);
    }

    @Transactional
    public void deleteReviewsByOrderId(Long reviewId) {
        orderAggregateMutationLockService.lockForReview(reviewId);
        assignmentMutationGuardService.assertReview(reviewId);
        Review review = reviewRepository.findById(reviewId).orElse(null);
        contractorRouteAssignmentGuard.requirePayableMutationAllowed(orderId(review));
        OrderDetails orderDetails = review != null ? review.getOrderDetails() : null;
        reviewRepository.deleteReviewByReviewId(reviewId);
        if (orderDetails != null) {
            List<Review> remaining = new ArrayList<>(safeReviews(orderDetails));
            remaining.removeIf(candidate -> Objects.equals(reviewId, candidate.getId()));
            orderDetails.setReviews(remaining);
            payableRecalculationService.recalculate(orderDetails);
        }
    }

    @Override
    public List<Review> findAllByFilial(Filial filial) {
        return reviewRepository.findAllByFilial(filial);
    }

    @Override
    @Transactional
    public void updateReviewByFilials(Set<Filial> filials, Long categoryId, Long subCategoryId) {
        if (filials == null || filials.isEmpty()) {
            return;
        }

        Category category = categoryService.getCategoryByIdCategory(categoryId);
        SubCategory subCategory = subCategoryService.getSubCategoryById(subCategoryId);
        List<String> reviewTexts = reviewRepository.findDistinctNonPlaceholderTextsByFilials(
                filials,
                REVIEW_TEXT_PLACEHOLDER
        );

        reviewRepository.updateClassificationByFilials(filials, category, subCategory);
        for (int start = 0; start < reviewTexts.size(); start += ARCHIVE_TEXT_UPDATE_BATCH_SIZE) {
            int end = Math.min(start + ARCHIVE_TEXT_UPDATE_BATCH_SIZE, reviewTexts.size());
            reviewArchiveRepository.updateClassificationByTexts(
                    reviewTexts.subList(start, end),
                    category,
                    subCategory
            );
        }
    }

    @Override
    @Transactional
    public void updateOrderDetailAndReview(OrderDetailsDTO orderDetailsDTO, ReviewDTO reviewDTO, Long reviewId) {
        log.info("2. Вошли в обновление данных Отзыва и Деталей Заказа");
        if (orderDetailsDTO == null || orderDetailsDTO.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Детали заказа не указаны");
        }
        orderAggregateMutationLockService.lockForOrderDetail(orderDetailsDTO.getId());
        Review saveReview = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", reviewId)));
        OrderDetails saveOrderDetails = orderDetailsService.getOrderDetailById(orderDetailsDTO.getId());
        contractorRouteAssignmentGuard.requirePayableMutationAllowed(orderId(saveOrderDetails));

        UUID reviewOrderDetailsId = saveReview.getOrderDetails() == null
                ? null
                : saveReview.getOrderDetails().getId();
        if (saveOrderDetails == null
                || saveOrderDetails.getId() == null
                || !Objects.equals(saveOrderDetails.getId(), reviewOrderDetailsId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Отзыв не относится к указанным деталям заказа"
            );
        }

        boolean isChanged = false;
        boolean oldPublish = saveReview.isPublish();

        if (!Objects.equals(reviewDTO.getText(), saveReview.getText())) {
            saveReview.setText(reviewDTO.getText());
            isChanged = true;
        }
        if (!Objects.equals(reviewDTO.getAnswer(), saveReview.getAnswer())) {
            saveReview.setAnswer(reviewDTO.getAnswer());
            isChanged = true;
        }
        if (!Objects.equals(orderDetailsDTO.getComment(), saveOrderDetails.getComment())) {
            saveOrderDetails.setComment(orderDetailsDTO.getComment());
            orderDetailsService.save(saveOrderDetails);
            isChanged = true;
        }
        if (!Objects.equals(reviewDTO.isPublish(), saveReview.isPublish())) {
            saveReview.setPublish(reviewDTO.isPublish());
            syncExternalConfirmationState(saveReview, oldPublish);
            isChanged = true;
        }
        if (!Objects.equals(reviewDTO.getPublishedDate(), saveReview.getPublishedDate())) {
            requirePublicationDateIntegrity(saveReview, reviewDTO.getPublishedDate());
            validateManualPublicationDate(saveReview, reviewDTO.getPublishedDate());
            saveReview.setPublishedDate(reviewDTO.getPublishedDate());
            isChanged = true;
        }

        if (isChanged) {
            reviewRepository.save(saveReview);
            if (!oldPublish && saveReview.isPublish()) {
                gamificationEventService.recordReviewPublished(saveReview);
            }
        }
    }

    @Override
    @Transactional
    public void updateOrderDetailAndReviews(OrderDetailsDTO orderDetailsDTO) {
        if (orderDetailsDTO == null || orderDetailsDTO.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Детали заказа не указаны");
        }
        if (orderDetailsDTO.getReviews() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Отзывы не переданы");
        }

        orderAggregateMutationLockService.lockForOrderDetail(orderDetailsDTO.getId());
        OrderDetails saveOrderDetails = orderDetailsService.getOrderDetailById(orderDetailsDTO.getId());
        contractorRouteAssignmentGuard.requirePayableMutationAllowed(orderId(saveOrderDetails));
        Map<Long, Review> reviewsById = safeReviews(saveOrderDetails).stream()
                .filter(Objects::nonNull)
                .filter(review -> review.getId() != null)
                .collect(Collectors.toMap(Review::getId, review -> review));
        Set<Long> submittedIds = new HashSet<>();
        List<Review> changedReviews = new ArrayList<>();
        List<Review> newlyPublishedReviews = new ArrayList<>();

        for (ReviewDTO reviewDTO : orderDetailsDTO.getReviews()) {
            Long reviewId = reviewDTO == null ? null : reviewDTO.getId();
            Review saveReview = reviewId == null || !submittedIds.add(reviewId)
                    ? null
                    : reviewsById.get(reviewId);
            if (saveReview == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Отзыв не относится к указанным деталям заказа"
                );
            }

            boolean changed = false;
            boolean oldPublish = saveReview.isPublish();
            if (!Objects.equals(reviewDTO.getText(), saveReview.getText())) {
                saveReview.setText(reviewDTO.getText());
                changed = true;
            }
            if (!Objects.equals(reviewDTO.getAnswer(), saveReview.getAnswer())) {
                saveReview.setAnswer(reviewDTO.getAnswer());
                changed = true;
            }
            if (!Objects.equals(reviewDTO.isPublish(), saveReview.isPublish())) {
                saveReview.setPublish(reviewDTO.isPublish());
                syncExternalConfirmationState(saveReview, oldPublish);
                changed = true;
            }
            if (!Objects.equals(reviewDTO.getPublishedDate(), saveReview.getPublishedDate())) {
                requirePublicationDateIntegrity(saveReview, reviewDTO.getPublishedDate());
                validateManualPublicationDate(saveReview, reviewDTO.getPublishedDate());
                saveReview.setPublishedDate(reviewDTO.getPublishedDate());
                changed = true;
            }
            if (changed) {
                changedReviews.add(saveReview);
                if (!oldPublish && saveReview.isPublish()) {
                    newlyPublishedReviews.add(saveReview);
                }
            }
        }

        if (!Objects.equals(orderDetailsDTO.getComment(), saveOrderDetails.getComment())) {
            saveOrderDetails.setComment(orderDetailsDTO.getComment());
            orderDetailsService.save(saveOrderDetails);
        }
        if (!changedReviews.isEmpty()) {
            reviewRepository.saveAll(changedReviews);
            newlyPublishedReviews.forEach(gamificationEventService::recordReviewPublished);
        }
    }

    @Override
    @Transactional
    public boolean updateOrderDetailAndReviewAndPublishDate(OrderDetailsDTO orderDetailsDTO) {
        if (orderDetailsDTO == null || orderDetailsDTO.getId() == null) {
            return false;
        }
        try {
            orderAggregateMutationLockService.lockForOrderDetail(orderDetailsDTO.getId());
            OrderDetails liveDetail = orderDetailsService.getOrderDetailById(orderDetailsDTO.getId());
            contractorRouteAssignmentGuard.requirePayableMutationAllowed(orderId(liveDetail));
            return applyPublicationDates(List.of(liveDetail), List.of(orderDetailsDTO), false);
        } catch (Exception exception) {
            return publicationDateFailure(exception);
        }
    }

    @Override
    @Transactional
    public boolean updateOrderDetailsAndReviewsAndPublishDates(
            Long orderId,
            List<OrderDetailsDTO> orderDetails
    ) {
        if (orderId == null || orderDetails == null || orderDetails.isEmpty()) {
            return false;
        }
        try {
            orderAggregateMutationLockService.lock(orderId);
            contractorRouteAssignmentGuard.requirePayableMutationAllowed(orderId);
            List<OrderDetails> liveDetails = orderDetailsService
                    .getOrderDetailsForReviewCheckByOrderId(orderId);
            return applyPublicationDates(liveDetails, orderDetails, true);
        } catch (Exception exception) {
            return publicationDateFailure(exception);
        }
    }

    private boolean applyPublicationDates(
            List<OrderDetails> liveDetails,
            List<OrderDetailsDTO> submittedDetails,
            boolean requireCompleteAggregate
    ) {
        if (liveDetails == null || liveDetails.isEmpty()) {
            return false;
        }

        Map<UUID, OrderDetails> liveById = liveDetails.stream()
                .filter(Objects::nonNull)
                .filter(detail -> detail.getId() != null)
                .collect(Collectors.toMap(OrderDetails::getId, detail -> detail));
        Map<UUID, OrderDetailsDTO> submittedById = new LinkedHashMap<>();
        for (OrderDetailsDTO detail : submittedDetails) {
            if (detail == null
                    || detail.getId() == null
                    || submittedById.putIfAbsent(detail.getId(), detail) != null
                    || !liveById.containsKey(detail.getId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Карточка не относится к указанному заказу"
                );
            }
        }
        if (requireCompleteAggregate && !submittedById.keySet().equals(liveById.keySet())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Состав заказа изменился. Обновите страницу и повторите одобрение."
            );
        }

        List<Review> changedReviews = new ArrayList<>();
        for (Map.Entry<UUID, OrderDetailsDTO> entry : submittedById.entrySet()) {
            if (!applyPublicationDates(
                    liveById.get(entry.getKey()),
                    entry.getValue(),
                    changedReviews,
                    requireCompleteAggregate
            )) {
                return false;
            }
        }

        if (!changedReviews.isEmpty()) {
            reviewRepository.saveAll(changedReviews);
        }
        return true;
    }

    private boolean applyPublicationDates(
            OrderDetails liveDetail,
            OrderDetailsDTO submittedDetail,
            List<Review> changedReviews,
            boolean requireCompleteReviewSet
    ) {
        List<Review> reviews = safeReviews(liveDetail);
        if (reviews.isEmpty()
                || submittedDetail.getReviews() == null
                || submittedDetail.getReviews().isEmpty()) {
            return false;
        }
        if (submittedDetail.getReviews().stream().anyMatch(this::hasInvalidPublicationText)) {
            return false;
        }

        List<ReviewDTO> reviewDtos = submittedDetail.getReviews().stream()
                .sorted(Comparator.comparing(ReviewDTO::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        Map<Long, Review> reviewsById = reviews.stream()
                .filter(review -> review.getId() != null)
                .collect(Collectors.toMap(Review::getId, review -> review));
        Set<Long> submittedIds = new HashSet<>();
        for (ReviewDTO reviewDTO : reviewDtos) {
            if (reviewDTO == null
                    || reviewDTO.getId() == null
                    || !submittedIds.add(reviewDTO.getId())
                    || !reviewsById.containsKey(reviewDTO.getId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Отзыв не относится к указанным деталям заказа"
                );
            }
        }
        if (requireCompleteReviewSet && !submittedIds.equals(reviewsById.keySet())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Состав отзывов изменился. Обновите страницу и повторите одобрение."
            );
        }

        Bot firstBot = reviews.getFirst().getBot();
        LocalDate startDate = getLocalDate(safeBotCounter(firstBot));
        int totalReviews = reviewDtos.size();
        int monthsNeeded = (int) Math.ceil(totalReviews / 28.0);
        LocalDate endDate = startDate.plusDays(monthsNeeded * 28L - 1);
        List<LocalDate> publishDates = randomPublicationDates(
                startDate,
                endDate,
                totalReviews,
                ThreadLocalRandom.current()
        );

        for (int index = 0; index < totalReviews; index++) {
            ReviewDTO reviewDTO = reviewDtos.get(index);
            Review liveReview = reviewsById.get(reviewDTO.getId());
            if (applyPublicationUpdate(liveReview, reviewDTO, publishDates.get(index))) {
                changedReviews.add(liveReview);
            }
        }

        if (!Objects.equals(submittedDetail.getComment(), liveDetail.getComment())) {
            // The aggregate query returns managed entities inside this write
            // transaction, so dirty checking persists all detail comments
            // without an N-per-detail save loop.
            liveDetail.setComment(submittedDetail.getComment());
        }
        return true;
    }

    private boolean publicationDateFailure(Exception exception) {
        log.error("Ошибка обновления данных, даты публикаций НЕ установлены: ", exception);
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus()
                    .setRollbackOnly();
        }
        return false;
    }

    private boolean hasInvalidPublicationText(ReviewDTO reviewDTO) {
        return reviewDTO == null || isBlankOrPlaceholder(reviewDTO.getText());
    }

    private void validateManualPublicationDate(Review review, LocalDate date) {
        requireAllowed(date);
        requireAllowedAfterPrevious(date, previousReviewPublicationDate(review));
        LocalDate walkNotBefore = reviewAccountWalkScheduleService.minimumPublicationDateForCurrentAccount(review);
        if (walkNotBefore != null && date.isBefore(walkNotBefore)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Для назначенного невыгулянного аккаунта дата публикации должна быть не раньше " + walkNotBefore
            );
        }
    }

    private void requirePublicationDateIntegrity(Review review, LocalDate requestedDate) {
        if (review == null
                || requestedDate != null
                || review.isPublish()
                || review.getPublishedDate() == null) {
            return;
        }
        String orderStatus = Optional.ofNullable(review.getOrderDetails())
                .map(OrderDetails::getOrder)
                .map(Order::getStatus)
                .map(status -> status.getTitle())
                .orElse("");
        if ("Публикация".equals(orderStatus)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Нельзя очистить дату неопубликованного отзыва, пока заказ находится в «Публикации». "
                            + "Сохраните текущую дату или сначала переведите заказ в «Коррекцию»."
            );
        }
    }

    private LocalDate previousReviewPublicationDate(Review review) {
        if (review == null || review.getId() == null || review.getOrderDetails() == null || review.getOrderDetails().getId() == null) {
            return null;
        }

        return reviewRepository.findAllByOrderDetailsId(review.getOrderDetails().getId()).stream()
                .filter(item -> item != null && item.getId() != null && item.getId() < review.getId())
                .sorted(Comparator.comparing(Review::getId).reversed())
                .map(Review::getPublishedDate)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private List<LocalDate> randomPublicationDates(
            LocalDate startDate,
            LocalDate initialEndDate,
            int totalReviews,
            ThreadLocalRandom random
    ) {
        LocalDate endDate = initialEndDate;
        List<LocalDate> candidates = publicationDateCandidates(startDate, endDate);

        while (candidates.size() < totalReviews) {
            endDate = endDate.plusWeeks(1);
            candidates = publicationDateCandidates(startDate, endDate);
        }

        List<LocalDate> selectedDates = new ArrayList<>();
        selectedDates.add(candidates.get(0));

        if (totalReviews == 1) {
            return selectedDates;
        }

        List<LocalDate> remainingCandidates = candidates.subList(1, candidates.size());
        int remainingReviews = totalReviews - 1;
        for (int i = 0; i < remainingReviews; i++) {
            int fromIndex = (int) ((long) i * remainingCandidates.size() / remainingReviews);
            int toIndex = (int) (((long) (i + 1) * remainingCandidates.size() / remainingReviews) - 1);
            selectedDates.add(remainingCandidates.get(random.nextInt(fromIndex, toIndex + 1)));
        }

        Collections.sort(selectedDates);
        return selectedDates;
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

    private LocalDate getLocalDate(int botCounter) {
        return botCounter < 2 ? LocalDate.now().plusDays(2) : LocalDate.now();
    }

    private Long orderId(Review review) {
        return Optional.ofNullable(review)
                .map(Review::getOrderDetails)
                .map(OrderDetails::getOrder)
                .map(Order::getId)
                .orElse(null);
    }

    private Long orderId(OrderDetails details) {
        return Optional.ofNullable(details)
                .map(OrderDetails::getOrder)
                .map(Order::getId)
                .orElse(null);
    }

    private boolean applyPublicationUpdate(Review saveReview, ReviewDTO reviewDTO, LocalDate localDate) {
        boolean isChanged = false;
        boolean oldPublish = saveReview.isPublish();

        if (!saveReview.isPublish()) {
            requireAllowed(localDate);
            saveReview.setPublishedDate(localDate);
            isChanged = true;
        }

        if (!Objects.equals(reviewDTO.getText(), saveReview.getText())) {
            saveReview.setText(reviewDTO.getText());
            isChanged = true;
        }
        if (!Objects.equals(reviewDTO.getUrl(), saveReview.getUrl())) {
            saveReview.setUrl(reviewDTO.getUrl());
            isChanged = true;
        }
        if (!Objects.equals(reviewDTO.getAnswer(), saveReview.getAnswer())) {
            saveReview.setAnswer(reviewDTO.getAnswer());
            isChanged = true;
        }
        if (!Objects.equals(reviewDTO.isPublish(), saveReview.isPublish())) {
            saveReview.setPublish(reviewDTO.isPublish());
            syncExternalConfirmationState(saveReview, oldPublish);
            isChanged = true;
        }

        return isChanged;
    }

    @Override
    public void changeBot(Long reviewId) {
        reviewBotChangeService.changeBot(reviewId);
    }

    @Override
    public void assignNewAccount(Long reviewId) {
        reviewBotChangeService.assignNewAccount(reviewId);
    }

    @Override
    public void deActivateAndChangeBot(Long reviewId, Long botId) {
        reviewBotChangeService.deActivateAndChangeBot(reviewId, botId);
    }


    public List<Bot> findAllBotsMinusFilial(Review review) {
        return reviewBotChangeService.findAllBotsMinusFilial(review);
    }

    @Override
    public ReviewDTOOne toReviewDTOOne(Review review) {
        return reviewDtoMapper.toReviewDTOOne(review);
    }

    public ReviewDTO getReviewDTOById(Long reviewId) {
        try {
            Optional<Review> reviewOptional = reviewRepository.findByIdForDto(reviewId);
            if (reviewOptional.isEmpty()) {
                log.error("Отзыв с ID {} не найден", reviewId);
                return null;
            }

            Review review = reviewOptional.get();
            return reviewDtoMapper.toReviewDTO(review);
        } catch (EntityNotFoundException e) {
            log.error("Ошибка при загрузке отзыва ID: {}. Причина: {}", reviewId, e.getMessage());
            return null;
        }
    }

    public Review getReviewById(Long reviewId) {
        return reviewRepository.findByIdForDto(reviewId).orElse(null);
    }

    @Override
    public boolean updateReviewText(Long orderId, Long reviewId, String text) {
        return reviewEditService.updateReviewText(orderId, reviewId, text);
    }

    @Override
    public boolean updateReviewAnswer(Long orderId, Long reviewId, String answer) {
        return reviewEditService.updateReviewAnswer(orderId, reviewId, answer);
    }

    @Override
    public boolean updateReviewTextFromSharedCheck(Long orderId, Long reviewId, String text) {
        return reviewEditService.updateReviewTextFromSharedCheck(orderId, reviewId, text);
    }

    @Override
    public boolean updateReviewAnswerFromSharedCheck(Long orderId, Long reviewId, String answer) {
        return reviewEditService.updateReviewAnswerFromSharedCheck(orderId, reviewId, answer);
    }

    @Override
    public boolean updateReviewNote(Long orderId, Long reviewId, String comment) {
        return reviewEditService.updateReviewNote(orderId, reviewId, comment);
    }

    public Page<ReviewDTOOne> getAllReviewDTOAndDateToAdminToVigul(LocalDate localDate, int pageNumber, int pageSize) {
        return getAllReviewDTOAndDateToAdminToVigul(localDate, pageNumber, pageSize, "asc");
    }

    public Page<ReviewDTOOne> getAllReviewDTOAndDateToAdminToVigul(LocalDate localDate, int pageNumber, int pageSize, String sortDirection) {
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(
                ReviewBoardMode.VIGUL, ReviewBoardScope.ADMIN,
                localDate, null, null, null, null, "", pageNumber, pageSize, sortDirection
        ));
    }

    public Page<ReviewDTOOne> getAllReviewDTOByManagerByPublishToVigul(LocalDate localDate, Principal principal, int pageNumber, int pageSize) {
        return getAllReviewDTOByManagerByPublishToVigul(localDate, principal, pageNumber, pageSize, "asc");
    }

    public Page<ReviewDTOOne> getAllReviewDTOByManagerByPublishToVigul(LocalDate localDate, Principal principal, int pageNumber, int pageSize, String sortDirection) {
        User user = requireUser(principal);
        Manager manager = managerService.getManagerByUserId(user.getId());
        if (manager == null || manager.getUser() == null || manager.getUser().getWorkers() == null) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }

        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(
                ReviewBoardMode.VIGUL, ReviewBoardScope.MANAGER,
                localDate, null, null, manager, manager.getUser().getWorkers(), "", pageNumber, pageSize, sortDirection
        ));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublishToVigul(LocalDate localDate, Principal principal, int pageNumber, int pageSize) {
        return getAllReviewDTOByOwnerByPublishToVigul(localDate, principal, pageNumber, pageSize, "asc");
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublishToVigul(LocalDate localDate, Principal principal, int pageNumber, int pageSize, String sortDirection) {
        User user = requireUser(principal);
        List<Manager> managerList = user.getManagers() == null ? List.of() : user.getManagers().stream().toList();
        if (managerList.isEmpty()) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }

        Set<Worker> workerList = workerService.getAllWorkersToManagerList(managerList);
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(
                ReviewBoardMode.VIGUL, ReviewBoardScope.OWNER,
                localDate, null, null, null, workerList, "", pageNumber, pageSize, sortDirection
        ));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublishToVigul(LocalDate localDate, Principal principal, int pageNumber, int pageSize) {
        return getAllReviewDTOByWorkerByPublishToVigul(localDate, principal, pageNumber, pageSize, "asc");
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublishToVigul(LocalDate localDate, Principal principal, int pageNumber, int pageSize, String sortDirection) {
        User user = requireUser(principal);
        Worker worker = workerService.getWorkerByUserId(user.getId());
        if (worker == null) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }

        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(
                ReviewBoardMode.VIGUL, ReviewBoardScope.WORKER,
                localDate, null, worker, null, null, "", pageNumber, pageSize, sortDirection
        ));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOAndDateToAdminToVigul(LocalDate localDate, int pageNumber, int pageSize, String sortDirection, String keyword) {
        if (!hasText(keyword)) {
            return getAllReviewDTOAndDateToAdminToVigul(localDate, pageNumber, pageSize, sortDirection);
        }
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(ReviewBoardMode.VIGUL, ReviewBoardScope.ADMIN,
                localDate, null, null, null, null, keyword, pageNumber, pageSize, sortDirection));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByManagerByPublishToVigul(LocalDate localDate, Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword) {
        if (!hasText(keyword)) {
            return getAllReviewDTOByManagerByPublishToVigul(localDate, principal, pageNumber, pageSize, sortDirection);
        }
        User user = requireUser(principal);
        Manager manager = managerService.getManagerByUserId(user.getId());
        if (manager == null || manager.getUser() == null || manager.getUser().getWorkers() == null) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(ReviewBoardMode.VIGUL, ReviewBoardScope.MANAGER,
                localDate, null, null, manager, manager.getUser().getWorkers(), keyword, pageNumber, pageSize, sortDirection));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublishToVigul(LocalDate localDate, Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword) {
        if (!hasText(keyword)) {
            return getAllReviewDTOByOwnerByPublishToVigul(localDate, principal, pageNumber, pageSize, sortDirection);
        }
        User user = requireUser(principal);
        List<Manager> managerList = user.getManagers() == null ? List.of() : user.getManagers().stream().toList();
        if (managerList.isEmpty()) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }
        Set<Worker> workers = workerService.getAllWorkersToManagerList(managerList);
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(ReviewBoardMode.VIGUL, ReviewBoardScope.OWNER,
                localDate, null, null, null, workers, keyword, pageNumber, pageSize, sortDirection));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublishToVigul(LocalDate localDate, Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword) {
        if (!hasText(keyword)) {
            return getAllReviewDTOByWorkerByPublishToVigul(localDate, principal, pageNumber, pageSize, sortDirection);
        }
        User user = requireUser(principal);
        Worker worker = workerService.getWorkerByUserId(user.getId());
        if (worker == null) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(ReviewBoardMode.VIGUL, ReviewBoardScope.WORKER,
                localDate, null, worker, null, null, keyword, pageNumber, pageSize, sortDirection));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublishToVigul(
            Worker worker,
            LocalDate localDate,
            int pageNumber,
            int pageSize,
            String sortDirection,
            String keyword
    ) {
        if (worker == null) {
            return emptyReviewPage(pageNumber, pageSize, sortDirection);
        }
        if (!hasText(keyword)) {
            return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(
                    ReviewBoardMode.VIGUL, ReviewBoardScope.WORKER,
                    localDate, null, worker, null, null, "", pageNumber, pageSize, sortDirection
            ));
        }
        return getReviewDTOPage(reviewBoardQueryService.findReviewIdsForBoard(ReviewBoardMode.VIGUL, ReviewBoardScope.WORKER,
                localDate, null, worker, null, null, keyword, pageNumber, pageSize, sortDirection));
    }

    private Page<ReviewDTOOne> getSafePageReviews(List<Review> reviewPage, int pageNumber, int pageSize) {
        int totalElements = reviewPage.size();

        if (totalElements == 0) {
            Pageable pageable = PageRequest.of(0, pageSize, reviewPublishedDateSortDescending());
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        int totalPages = (int) Math.ceil((double) totalElements / pageSize);
        if (totalPages == 0) {
            totalPages = 1;
        }

        int correctedPageNumber = pageNumber;
        if (pageNumber >= totalPages) {
            correctedPageNumber = totalPages - 1;
        }
        if (pageNumber < 0) {
            correctedPageNumber = 0;
        }

        Pageable pageable = PageRequest.of(correctedPageNumber, pageSize, reviewPublishedDateSortDescending());
        int start = correctedPageNumber * pageSize;
        int end = Math.min(start + pageSize, totalElements);

        if (start > end) {
            start = 0;
            end = Math.min(pageSize, totalElements);
        }

        List<ReviewDTOOne> reviewDTOOnes = reviewPage.subList(start, end)
                .stream()
                .map(this::toReviewDTOOne)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return new PageImpl<>(reviewDTOOnes, pageable, totalElements);
    }

    @Override
    public void changeNagulReview(Long reviewId) {
        reviewNagulService.changeNagulReview(reviewId);
    }

    @Override
    public void performNagulWithExceptions(Long reviewId, String username) {
        reviewNagulService.performNagulWithExceptions(reviewId, username);
    }

    public int countOrdersByWorkerAndStatusPublish(Worker worker, LocalDate localDate) {
        return reviewRepository.countByWorkerAndStatusPublish(worker, localDate);
    }

    public int countOrdersByWorkerAndStatusVigul(Worker worker, LocalDate localDate) {
        return reviewRepository.countByWorkerAndStatusVigul(worker, localDate);
    }

    @Override
    public Map<String, Pair<Long, Long>> getAllPublishAndVigul(LocalDate firstDayOfMonth, LocalDate localDate) {
        Map<String, Pair<Long, Long>> result = new HashMap<>();
        for (Object[] row : reviewRepository.findAllByPublishAndVigul(firstDayOfMonth, localDate, localDate.plusDays(2))) {
            String fio = (String) row[0];
            Pair<Long, Long> counts = Pair.of(((Number) row[2]).longValue(), ((Number) row[1]).longValue());
            result.merge(fio, counts, (left, right) -> Pair.of(
                    left.getFirst() + right.getFirst(),
                    left.getSecond() + right.getSecond()
            ));
        }
        return result;
    }

    @Override
    public Map<String, Long> getAllReviewsToMonth(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        List<Object[]> results = reviewRepository.getAllReviewsToMonth(firstDayOfMonth, lastDayOfMonth);

        Map<String, Long> workerReviews = new HashMap<>();
        Map<String, Long> managerReviews = new HashMap<>();

        for (Object[] row : results) {
            String workerFio = (String) row[0];
            Long workerReviewCount = (Long) row[1];

            String managerFio = (String) row[2];
            Long managerReviewCount = (Long) row[3];

            if (workerFio != null) {
                workerReviews.merge(workerFio, workerReviewCount, Long::sum);
            }
            if (managerFio != null) {
                managerReviews.merge(managerFio, managerReviewCount, Long::sum);
            }
        }

        Map<String, Long> allReviews = new HashMap<>();
        allReviews.putAll(workerReviews);
        allReviews.putAll(managerReviews);
        return allReviews;
    }

    private User requireUser(Principal principal) {
        if (principal == null) {
            throw new UsernameNotFoundException("Principal == null");
        }
        return userService.findByUserNameWithAssignments(principal.getName())
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден: " + principal.getName()));
    }

    private Page<ReviewDTOOne> emptyReviewPage(int pageNumber, int pageSize) {
        return emptyReviewPage(pageNumber, pageSize, "asc");
    }

    private Page<ReviewDTOOne> emptyReviewPage(int pageNumber, int pageSize, String sortDirection) {
        Pageable pageable = reviewBoardQueryService.reviewPageable(pageNumber, pageSize, sortDirection);
        return new PageImpl<>(Collections.emptyList(), pageable, 0);
    }

    private List<Review> safeReviews(OrderDetails orderDetails) {
        if (orderDetails == null || orderDetails.getReviews() == null) {
            return Collections.emptyList();
        }
        return orderDetails.getReviews().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Review::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    private int safeBotCounter(Bot bot) {
        return bot != null ? bot.getCounter() : 0;
    }

    private String extractComment(OrderDetails orderDetails) {
        return orderDetails != null ? orderDetails.getComment() : null;
    }

    private Order extractOrder(Review review) {
        if (review == null || review.getOrderDetails() == null) {
            return null;
        }
        return review.getOrderDetails().getOrder();
    }
}

package com.hunt.otziv.r_review.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.bot.ReviewBotChangeService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.edit.ReviewEditService;
import com.hunt.otziv.r_review.mapper.ReviewDtoMapper;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.model.ReviewArchive;
import com.hunt.otziv.r_review.board.ReviewBoardMode;
import com.hunt.otziv.r_review.board.ReviewBoardQueryService;
import com.hunt.otziv.r_review.board.ReviewBoardScope;
import com.hunt.otziv.r_review.nagul.ReviewNagulService;
import com.hunt.otziv.r_review.repository.ReviewArchiveRepository;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.data.util.Pair;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static com.hunt.otziv.r_review.utils.ReviewBoardSearch.hasText;


@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

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
    private final ReviewEditService reviewEditService;

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
        Pageable pageable = reviewBoardQueryService.reviewPageable(pageNumber, pageSize, sortDirection);
        Page<Long> reviewIds = reviewRepository.findPageIdsByPublishedDateAndPublish(localDate, pageable);
        return getReviewDTOPage(reviewIds);
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

        Pageable pageable = reviewBoardQueryService.reviewPageable(pageNumber, pageSize, sortDirection);
        Page<Long> reviewIds = reviewRepository.findPageIdsByWorkerAndPublishedDateAndPublish(worker, localDate, pageable);
        return getReviewDTOPage(reviewIds);
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

        Pageable pageable = reviewBoardQueryService.reviewPageable(pageNumber, pageSize, sortDirection);
        Page<Long> reviewIds = reviewRepository.findPageIdsByManagerAndPublishedDateAndPublish(
                manager.getUser().getWorkers(), manager, localDate, pageable);
        return getReviewDTOPage(reviewIds);
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
        Pageable pageable = reviewBoardQueryService.reviewPageable(pageNumber, pageSize, sortDirection);
        Page<Long> reviewIds = reviewRepository.findPageIdsByWorkersAndPublishedDateAndPublish(workerList, localDate, pageable);
        return getReviewDTOPage(reviewIds);
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
            Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("publishedDate").descending());
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

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("publishedDate").descending());
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), totalElements);

        if (start >= totalElements) {
            start = 0;
            end = Math.min(pageSize, totalElements);
            pageable = PageRequest.of(0, pageSize, Sort.by("publishedDate").descending());
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

    public boolean hasActiveNagulReviews(Principal principal) {
        return reviewNagulService.hasActiveNagulReviews(principal);
    }

    @Override
    public List<Review> saveAll(List<Review> reviews) {
        return (List<Review>) reviewRepository.saveAll(reviews);
    }

    public Review save(Review review) {
        if (review == null) {
            return null;
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

    public boolean deleteReview(Long reviewId) {
        Review review = reviewRepository.findById(reviewId).orElse(null);
        if (review == null) {
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
        Review saveReview = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new UsernameNotFoundException(String.format("Отзыв '%d' не найден", reviewId)));

        boolean isChanged = false;

        Product dtoProduct = reviewDTO.getProduct();
        Product currentProduct = saveReview.getProduct();
        Long dtoProductId = dtoProduct != null ? dtoProduct.getId() : null;
        Long currentProductId = currentProduct != null ? currentProduct.getId() : null;

        log.info("text: {}", !Objects.equals(reviewDTO.getText(), saveReview.getText()));
        log.info("answer: {}", !Objects.equals(reviewDTO.getAnswer(), saveReview.getAnswer()));
        log.info("comment: {}", !Objects.equals(reviewDTO.getComment(), extractComment(saveReview.getOrderDetails())));
        log.info("url: {}", !Objects.equals(reviewDTO.getUrl(), saveReview.getUrl()));
        log.info("date publish: {}", !Objects.equals(reviewDTO.getPublishedDate(), saveReview.getPublishedDate()));
        log.info("date isPublish: {}", !Objects.equals(reviewDTO.isPublish(), saveReview.isPublish()));
        log.info("Выгул: {}", !Objects.equals(reviewDTO.isVigul(), saveReview.isVigul()));
        log.info("product id: {}", !Objects.equals(dtoProductId, currentProductId));

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

        boolean productChanged = false;
        if (dtoProduct != null && currentProduct != null) {
            if (!Objects.equals(dtoProductId, currentProductId)) {
                productChanged = true;
            }
        } else if (dtoProduct != null || currentProduct != null) {
            productChanged = true;
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

            if (reviewDTO.getOrderDetailsId() != null) {
                recalculateOrderAndDetailsPrice(reviewDTO.getOrderDetailsId());
            }
        }

        if ("ROLE_ADMIN".equals(userRole) || "ROLE_OWNER".equals(userRole)) {
            if (!Objects.equals(reviewDTO.isPublish(), saveReview.isPublish())) {
                log.info("Обновляем публикацию отзыва");
                saveReview.setPublish(reviewDTO.isPublish());
                isChanged = true;
            }
        }

        if ("ROLE_ADMIN".equals(userRole) || "ROLE_OWNER".equals(userRole) || "ROLE_MANAGER".equals(userRole)) {
            if (!Objects.equals(reviewDTO.isVigul(), saveReview.isVigul())) {
                log.info("Обновляем выгул отзыва");
                saveReview.setVigul(reviewDTO.isVigul());
                isChanged = true;
            }
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

        if (!Objects.equals(reviewDTO.getPublishedDate(), saveReview.getPublishedDate())) {
            log.info("Обновляем дату публикации отзыва");
            saveReview.setPublishedDate(reviewDTO.getPublishedDate());
            isChanged = true;
        }

        if (isChanged) {
            reviewRepository.save(saveReview);
        }
    }

    private void recalculateOrderAndDetailsPrice(UUID orderDetailsId) {
        OrderDetails orderDetails = orderDetailsService.getOrderDetailById(orderDetailsId);

        BigDecimal detailTotal = safeReviews(orderDetails).stream()
                .map(Review::getPrice)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        orderDetails.setPrice(detailTotal);
        orderDetailsService.save(orderDetails);

        Order order = orderDetails.getOrder();
        if (order != null) {
            order.setSum(orderDetails.getPrice());
            orderDetailsService.saveOrder(order);
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
        reviewRepository.deleteReviewByReviewId(reviewId);
    }

    @Override
    public List<Review> findAllByFilial(Filial filial) {
        return reviewRepository.findAllByFilial(filial);
    }

    @Override
    public void updateReviewByFilials(Set<Filial> filials, Long categoryId, Long subCategoryId) {
        List<Review> reviews = reviewRepository.findAllByFilials(filials);
        Iterable<ReviewArchive> reviewArchives = reviewArchiveRepository.findAll();

        for (Review review : reviews) {
            review.setCategory(categoryService.getCategoryByIdCategory(categoryId));
            review.setSubCategory(subCategoryService.getSubCategoryById(subCategoryId));
            reviewRepository.save(review);
        }

        for (ReviewArchive reviewArchive : reviewArchives) {
            for (Review review : reviews) {
                if (Objects.equals(review.getText(), reviewArchive.getText()) && !"Текст отзыва".equals(reviewArchive.getText())) {
                    reviewArchive.setCategory(categoryService.getCategoryByIdCategory(categoryId));
                    reviewArchive.setSubCategory(subCategoryService.getSubCategoryById(subCategoryId));
                    reviewArchiveRepository.save(reviewArchive);
                }
            }
        }
    }

    @Override
    @Transactional
    public void updateOrderDetailAndReview(OrderDetailsDTO orderDetailsDTO, ReviewDTO reviewDTO, Long reviewId) {
        log.info("2. Вошли в обновление данных Отзыва и Деталей Заказа");
        Review saveReview = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", reviewId)));
        OrderDetails saveOrderDetails = orderDetailsService.getOrderDetailById(orderDetailsDTO.getId());

        boolean isChanged = false;

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
            isChanged = true;
        }
        if (!Objects.equals(reviewDTO.getPublishedDate(), saveReview.getPublishedDate())) {
            saveReview.setPublishedDate(reviewDTO.getPublishedDate());
            isChanged = true;
        }

        if (isChanged) {
            reviewRepository.save(saveReview);
        }
    }

    @Override
    @Transactional
    public boolean updateOrderDetailAndReviewAndPublishDate(OrderDetailsDTO orderDetailsDTO) {
        log.info("2. Вошли в обновление данных Отзыва и Деталей Заказа + Назначение случайных дат публикации (1–6 дней, растяжка по диапазону)");

        try {
            OrderDetails saveOrderDetails = orderDetailsService.getOrderDetailById(orderDetailsDTO.getId());

            List<Review> reviews = safeReviews(saveOrderDetails);
            if (reviews.isEmpty()) {
                log.error("Ошибка: список отзывов пуст");
                return false;
            }

            if (orderDetailsDTO.getReviews() == null || orderDetailsDTO.getReviews().isEmpty()) {
                log.error("Ошибка: список отзывов в DTO пуст");
                return false;
            }

            Bot firstBot = reviews.get(0).getBot();
            int botCounter = safeBotCounter(firstBot);
            LocalDate startDate = getLocalDate(botCounter);

            int totalReviews = orderDetailsDTO.getReviews().size();
            int monthsNeeded = (int) Math.ceil(totalReviews / 28.0);
            LocalDate endDate = startDate.plusDays(monthsNeeded * 28L - 1);
            long totalDays = ChronoUnit.DAYS.between(startDate, endDate);

            ThreadLocalRandom random = ThreadLocalRandom.current();
            List<Long> offsets = new ArrayList<>();

            offsets.add(random.nextLong(0, Math.min(3, totalDays + 1)));

            if (totalReviews > 1) {
                offsets.add(totalDays - random.nextLong(0, Math.min(3, totalDays + 1)));
            }

            while (offsets.size() < totalReviews) {
                long offset = random.nextLong(0, totalDays + 1);
                if (!offsets.contains(offset)) {
                    offsets.add(offset);
                }
            }

            Collections.sort(offsets);

            for (int i = 1; i < offsets.size(); i++) {
                long prev = offsets.get(i - 1);
                long current = offsets.get(i);
                long gap = current - prev;

                if (gap < 1) {
                    offsets.set(i, prev + 1);
                } else if (gap > 6) {
                    offsets.set(i, prev + 6);
                }
            }

            Collections.sort(offsets);

            for (int i = 0; i < totalReviews; i++) {
                ReviewDTO reviewDTO = orderDetailsDTO.getReviews().get(i);
                LocalDate publishDate = startDate.plusDays(offsets.get(i));
                checkUpdateReview(reviewDTO, publishDate);
                log.info("Обновили дату публикации отзыва №{}: {}", i + 1, publishDate);
            }

            if (!Objects.equals(orderDetailsDTO.getComment(), saveOrderDetails.getComment())) {
                saveOrderDetails.setComment(orderDetailsDTO.getComment());
                orderDetailsService.save(saveOrderDetails);
            }

            return true;

        } catch (Exception e) {
            log.error("Ошибка обновления данных, даты публикаций НЕ установлены: ", e);
            return false;
        }
    }

    private LocalDate getLocalDate(int botCounter) {
        return botCounter < 2 ? LocalDate.now().plusDays(2) : LocalDate.now();
    }

    private void checkUpdateReview(ReviewDTO reviewDTO, LocalDate localDate) {
        Review saveReview = reviewRepository.findById(reviewDTO.getId())
                .orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", reviewDTO.getId())));

        boolean isChanged = false;

        if (!saveReview.isPublish()) {
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
            isChanged = true;
        }

        if (isChanged) {
            reviewRepository.save(saveReview);
        }
    }

    @Override
    public void changeBot(Long reviewId) {
        reviewBotChangeService.changeBot(reviewId);
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
            Optional<Review> reviewOptional = reviewRepository.findById(reviewId);
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
        return reviewRepository.findById(reviewId).orElse(null);
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
    public boolean updateReviewNote(Long orderId, Long reviewId, String comment) {
        return reviewEditService.updateReviewNote(orderId, reviewId, comment);
    }

    public Page<ReviewDTOOne> getAllReviewDTOAndDateToAdminToVigul(LocalDate localDate, int pageNumber, int pageSize) {
        return getAllReviewDTOAndDateToAdminToVigul(localDate, pageNumber, pageSize, "asc");
    }

    public Page<ReviewDTOOne> getAllReviewDTOAndDateToAdminToVigul(LocalDate localDate, int pageNumber, int pageSize, String sortDirection) {
        Pageable pageable = reviewBoardQueryService.reviewPageable(pageNumber, pageSize, sortDirection);
        Page<Long> reviewIds = reviewRepository.findPageIdsByPublishedDateAndPublishToVigul(localDate, pageable);
        return getReviewDTOPage(reviewIds);
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

        Pageable pageable = reviewBoardQueryService.reviewPageable(pageNumber, pageSize, sortDirection);
        Page<Long> reviewIds = reviewRepository.findPageIdsByManagerAndPublishedDateAndPublishToVigul(
                manager.getUser().getWorkers(), manager, localDate, pageable);
        return getReviewDTOPage(reviewIds);
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
        Pageable pageable = reviewBoardQueryService.reviewPageable(pageNumber, pageSize, sortDirection);
        Page<Long> reviewIds = reviewRepository.findPageIdsByWorkersAndPublishedDateAndPublishToVigul(workerList, localDate, pageable);
        return getReviewDTOPage(reviewIds);
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

        Pageable pageable = reviewBoardQueryService.reviewPageable(pageNumber, pageSize, sortDirection);
        Page<Long> reviewIds = reviewRepository.findPageIdsByWorkerAndPublishedDateAndPublishToVigul(worker, localDate, pageable);
        return getReviewDTOPage(reviewIds);
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

    private Page<ReviewDTOOne> getSafePageReviews(List<Review> reviewPage, int pageNumber, int pageSize) {
        int totalElements = reviewPage.size();

        if (totalElements == 0) {
            Pageable pageable = PageRequest.of(0, pageSize, Sort.by("publishedDate").descending());
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

        Pageable pageable = PageRequest.of(correctedPageNumber, pageSize, Sort.by("publishedDate").descending());
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
        return reviewRepository.findAllByPublishAndVigul(firstDayOfMonth, localDate, localDate.plusDays(2)).stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> Pair.of(((Number) row[2]).longValue(), ((Number) row[1]).longValue())
                ));
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
        return userService.findByUserName(principal.getName())
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
        return orderDetails.getReviews();
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

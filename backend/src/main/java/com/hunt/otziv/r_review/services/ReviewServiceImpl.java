package com.hunt.otziv.r_review.services;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.config.email.EmailService;
import com.hunt.otziv.exceptions.BotTemplateNameException;
import com.hunt.otziv.exceptions.NagulTooFastException;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.dto.ProductDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.services.service.BotAssignmentService;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.model.ReviewArchive;
import com.hunt.otziv.r_review.repository.ReviewArchiveRepository;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.data.util.Pair;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;


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
    private final EmailService emailService;
    private final ProductService productService;
    private final FilialService filialService;
    private final BotAssignmentService botAssignmentService;

    @PersistenceContext
    private EntityManager entityManager;

    private static final Long STUB_BOT_ID = 1L;
    private static final int MAX_ACTIVE_REVIEWS_PER_BOT = 3;
    private static final Set<String> TEMPLATE_BOT_NAMES = Set.of(
            "Впишите Имя Фамилию",
            "Впиши Имя Фамилию",
            "Впишите Фамилию Имя"
    );

    @Value("${app.nagul.cooldown}")
    private int NAGUL_COOLDOWN_MINUTES;

    private static final List<String> FORBIDDEN_PATTERNS = Arrays.asList(
            "имя", "фамилия", "фамилию", "впиши", "отчество", "fio", "name", "surname",
            "введите", "заполните", "укажите", "вставьте", "шаблон", "template", "пример"
    );

    private enum ReviewBoardMode {
        PUBLISH,
        ORDER_STATUS,
        VIGUL
    }

    private enum ReviewBoardScope {
        ADMIN,
        WORKER,
        MANAGER,
        OWNER
    }

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
        return countBoardReviewMetrics(reviewBoardScope(role), publishDate, vigulDate, badStatus, principal);
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
        Pageable pageable = reviewPageable(pageNumber, pageSize, sortDirection);
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

        Pageable pageable = reviewPageable(pageNumber, pageSize, sortDirection);
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

        Pageable pageable = reviewPageable(pageNumber, pageSize, sortDirection);
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
        Pageable pageable = reviewPageable(pageNumber, pageSize, sortDirection);
        Page<Long> reviewIds = reviewRepository.findPageIdsByWorkersAndPublishedDateAndPublish(workerList, localDate, pageable);
        return getReviewDTOPage(reviewIds);
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOrderStatusToAdmin(String status, int pageNumber, int pageSize) {
        return getAllReviewDTOByOrderStatusToAdmin(status, pageNumber, pageSize, "asc");
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOrderStatusToAdmin(String status, int pageNumber, int pageSize, String sortDirection) {
        Pageable pageable = reviewPageable(pageNumber, pageSize, sortDirection);
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

        Pageable pageable = reviewPageable(pageNumber, pageSize, sortDirection);
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

        Pageable pageable = reviewPageable(pageNumber, pageSize, sortDirection);
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
        Pageable pageable = reviewPageable(pageNumber, pageSize, sortDirection);
        Page<Long> reviewIds = reviewRepository.findPageIdsByWorkersAndOrderStatus(workerList, status, pageable);
        return getReviewDTOPage(reviewIds);
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOAndDateToAdmin(LocalDate localDate, int pageNumber, int pageSize, String sortDirection, String keyword) {
        if (!hasText(keyword)) {
            return getAllReviewDTOAndDateToAdmin(localDate, pageNumber, pageSize, sortDirection);
        }
        return getReviewDTOPage(findReviewIdsForBoard(ReviewBoardMode.PUBLISH, ReviewBoardScope.ADMIN,
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
        return getReviewDTOPage(findReviewIdsForBoard(ReviewBoardMode.PUBLISH, ReviewBoardScope.WORKER,
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
        return getReviewDTOPage(findReviewIdsForBoard(ReviewBoardMode.PUBLISH, ReviewBoardScope.MANAGER,
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
        return getReviewDTOPage(findReviewIdsForBoard(ReviewBoardMode.PUBLISH, ReviewBoardScope.OWNER,
                localDate, null, null, null, workers, keyword, pageNumber, pageSize, sortDirection));
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOByOrderStatusToAdmin(String status, int pageNumber, int pageSize, String sortDirection, String keyword) {
        if (!hasText(keyword)) {
            return getAllReviewDTOByOrderStatusToAdmin(status, pageNumber, pageSize, sortDirection);
        }
        return getReviewDTOPage(findReviewIdsForBoard(ReviewBoardMode.ORDER_STATUS, ReviewBoardScope.ADMIN,
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
        return getReviewDTOPage(findReviewIdsForBoard(ReviewBoardMode.ORDER_STATUS, ReviewBoardScope.WORKER,
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
        return getReviewDTOPage(findReviewIdsForBoard(ReviewBoardMode.ORDER_STATUS, ReviewBoardScope.MANAGER,
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
        return getReviewDTOPage(findReviewIdsForBoard(ReviewBoardMode.ORDER_STATUS, ReviewBoardScope.OWNER,
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

    private Pageable reviewPageable(int pageNumber, int pageSize, String sortDirection) {
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDirection)
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        Sort sort = Sort.by(direction, "publishedDate").and(Sort.by(direction, "id"));
        return PageRequest.of(Math.max(pageNumber, 0), Math.max(pageSize, 1), sort);
    }

    private Page<Long> findReviewIdsForBoard(
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
        conditions.add(reviewKeywordPredicate(keywordLong != null, keywordUuid != null));

        String where = " WHERE " + String.join(" AND ", conditions);
        String direction = "desc".equalsIgnoreCase(sortDirection) ? "DESC" : "ASC";
        String orderBy = " ORDER BY r.publishedDate " + direction + ", r.id " + direction;

        TypedQuery<Long> idQuery = entityManager.createQuery(
                "SELECT DISTINCT r.id FROM Review r " + joins + where + orderBy,
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

        ReviewBoardScope scope = reviewBoardScope(role);
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

        return toIntCount(countReviewIdsForBoard(mode, scope, localDate, status, worker, manager, workers));
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
        result.put("publish", toIntCount(countReviewIdsForBoard(
                ReviewBoardMode.PUBLISH, scope, publishDate, null, worker, manager, workers)));
        result.put("nagul", toIntCount(countReviewIdsForBoard(
                ReviewBoardMode.VIGUL, scope, vigulDate, null, worker, manager, workers)));
        result.put("bad", toIntCount(countReviewIdsForBoard(
                ReviewBoardMode.ORDER_STATUS, scope, null, badStatus, worker, manager, workers)));
        return result;
    }

    private long countReviewIdsForBoard(
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

    private ReviewBoardScope reviewBoardScope(String role) {
        if ("ADMIN".equalsIgnoreCase(role)) {
            return ReviewBoardScope.ADMIN;
        }
        if ("OWNER".equalsIgnoreCase(role)) {
            return ReviewBoardScope.OWNER;
        }
        if ("MANAGER".equalsIgnoreCase(role)) {
            return ReviewBoardScope.MANAGER;
        }
        return ReviewBoardScope.WORKER;
    }

    private int toIntCount(long count) {
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    private String reviewKeywordPredicate(boolean hasKeywordLong, boolean hasKeywordUuid) {
        List<String> parts = new ArrayList<>(List.of(
                "LOWER(COALESCE(r.text, '')) LIKE :keyword",
                "LOWER(COALESCE(r.answer, '')) LIKE :keyword",
                "LOWER(COALESCE(c.title, '')) LIKE :keyword",
                "LOWER(COALESCE(c.commentsCompany, '')) LIKE :keyword",
                "LOWER(COALESCE(o.zametka, '')) LIKE :keyword",
                "LOWER(COALESCE(os.title, '')) LIKE :keyword",
                "LOWER(COALESCE(city.title, '')) LIKE :keyword",
                "LOWER(COALESCE(f.title, '')) LIKE :keyword",
                "LOWER(COALESCE(b.fio, '')) LIKE :keyword",
                "LOWER(COALESCE(wu.fio, '')) LIKE :keyword",
                "LOWER(COALESCE(mu.fio, '')) LIKE :keyword",
                "LOWER(COALESCE(rp.title, '')) LIKE :keyword",
                "LOWER(COALESCE(dp.title, '')) LIKE :keyword",
                "LOWER(COALESCE(cat.categoryTitle, '')) LIKE :keyword",
                "LOWER(COALESCE(sub.subCategoryTitle, '')) LIKE :keyword",
                "LOWER(COALESCE(d.comment, '')) LIKE :keyword"
        ));

        if (hasKeywordLong) {
            parts.add("r.id = :keywordLong");
            parts.add("o.id = :keywordLong");
            parts.add("c.id = :keywordLong");
        }
        if (hasKeywordUuid) {
            parts.add("d.id = :keywordUuid");
        }

        return "(" + String.join(" OR ", parts) + ")";
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

    private Long parseKeywordLong(String keyword) {
        if (!hasText(keyword)) {
            return null;
        }
        String trimmed = keyword.trim();
        if (!trimmed.matches("\\d+")) {
            return null;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private UUID parseKeywordUuid(String keyword) {
        if (!hasText(keyword)) {
            return null;
        }
        try {
            return UUID.fromString(keyword.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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
        if (principal == null) {
            return false;
        }

        User user = userService.findByUserName(principal.getName()).orElse(null);
        if (user == null) {
            return false;
        }

        Worker worker = workerService.getWorkerByUserId(user.getId());
        if (worker == null) {
            return false;
        }

        LocalDate today = LocalDate.now();
        return reviewRepository.existsActiveNagulReviews(worker, today.plusDays(60));
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
        try {
            log.info("1. Начинаем замену бота для отзыва ID {}", reviewId);
            Review review = getReviewToChangeBot(reviewId);

            if (review.getBot() == null) {
                log.warn("2. Для отзыва ID {} не удалось установить бота (список доступных пуст)", reviewId);
            } else if (Objects.equals(review.getBot().getId(), STUB_BOT_ID)) {
                log.warn("2. Для отзыва ID {} установлен бот-заглушка (нет доступных ботов)", reviewId);
            } else {
                log.info("2. Установлен новый рандомный бот для отзыва ID {}", reviewId);
            }

            reviewRepository.save(review);
            log.info("3. Сохранили отзыв в БД");

        } catch (Exception e) {
            log.error("Ошибка при замене бота для отзыва ID {}: {}", reviewId, e.getMessage(), e);
            throw new RuntimeException("Не удалось заменить бота: " + e.getMessage(), e);
        }
    }

    @Override
    public void deActivateAndChangeBot(Long reviewId, Long botId) {
        try {
            Review review = reviewRepository.findById(reviewId).orElse(null);
            if (review == null) {
                throw new RuntimeException("Отзыв не найден");
            }

            boolean wasVigul = review.isVigul();

            Bot currentBot = review.getBot();
            Long currentBotId = currentBot != null ? currentBot.getId() : null;

            if ((botId == null || botId == 0L) && currentBotId != null && currentBotId > 0) {
                botId = currentBotId;
                log.info("Используем ID реального бота отзыва: {}", botId);
            }

            try {
                if (review.getFilial() != null && review.getFilial().getCity() != null) {
                    List<Bot> cityBots = botService.getFindAllByFilialCityId(review.getFilial().getCity().getId());
                    int botCount = cityBots != null ? cityBots.size() : 0;
                    if (botCount < 50) {
                        String textMail = "Город: " + review.getFilial().getCity().getTitle() + ". Остаток у города: " + botCount;
                        emailService.sendSimpleEmail("o-company-server@mail.ru", "Мало аккаунтов у города", "Необходимо добавить аккаунты для: " + textMail);
                    }
                }
            } catch (Exception e) {
                log.error("Сообщение о деактивации бота не отправилось", e);
            }

            if (botId != null && !Objects.equals(botId, STUB_BOT_ID) && botId > 0) {
                botActiveToFalse(botId);
            }

            Set<Long> excludedBotIds = botId != null && botId > 0 ? Set.of(botId) : Set.of();
            assignBotUsingSharedRules(review, excludedBotIds);

            log.info("Vigul обновлен: {} -> {}", wasVigul, review.isVigul());
            reviewRepository.save(review);

        } catch (Exception e) {
            log.error("Что-то пошло не так и бот не деактивирован", e);
            throw new RuntimeException("Ошибка при деактивации и смене бота: " + e.getMessage(), e);
        }
    }

    private Review getReviewToChangeBot(Long reviewId) {
        Optional<Review> reviewOptional = reviewRepository.findById(reviewId);
        if (reviewOptional.isEmpty()) {
            throw new RuntimeException("Отзыв не найден");
        }

        Review review = reviewOptional.get();
        boolean wasVigul = review.isVigul();

        assignBotUsingSharedRules(review, Set.of());

        log.info("Vigul обновлен: {} -> {}", wasVigul, review.isVigul());
        return review;
    }

    private void assignBotUsingSharedRules(Review review, Collection<Long> excludedBotIds) {
        Bot selectedBot = botAssignmentService.assignBotForReviewChange(review, excludedBotIds);
        review.setBot(selectedBot);

        if (selectedBot == null || STUB_BOT_ID.equals(selectedBot.getId())) {
            if (review.isVigul()) {
                review.setVigul(false);
            }
            return;
        }

        updateVigulBasedOnBotCounter(review);
    }

    private Bot createStubBot() {
        try {
            Bot stubBot = botService.findBotById(STUB_BOT_ID);
            if (stubBot != null) {
                return stubBot;
            }

            Bot temp = new Bot();
            temp.setId(STUB_BOT_ID);
            temp.setFio("Нет доступных аккаунтов");
            temp.setLogin("stub_account");
            temp.setPassword("");
            temp.setCounter(0);
            temp.setActive(false);

            StatusBot stubStatus = new StatusBot();
            stubStatus.setBotStatusTitle("Заглушка");
            temp.setStatus(stubStatus);

            return temp;

        } catch (Exception e) {
            log.error("Ошибка при получении бота-заглушки", e);

            Bot temp = new Bot();
            temp.setId(STUB_BOT_ID);
            temp.setFio("Нет доступных аккаунтов");
            temp.setLogin("stub_account");
            temp.setPassword("");
            temp.setCounter(0);
            temp.setActive(false);
            return temp;
        }
    }

    private boolean botActiveToFalse(Long botId) {
        try {
            if (botId == null || botId <= 0 || STUB_BOT_ID.equals(botId)) {
                return false;
            }

            Bot bot = botService.findBotById(botId);
            if (bot == null) {
                return false;
            }

            bot.setActive(false);
            botService.save(bot);
            return true;

        } catch (Exception e) {
            log.error("3. Ошибка при деактивации бота {}: ", botId, e);
            return false;
        }
    }

    public List<Bot> findAllBotsMinusFilial(Review review) {
        if (review == null) {
            return Collections.emptyList();
        }

        Filial filial = review.getFilial();
        if (filial == null) {
            return Collections.emptyList();
        }

        City city = filial.getCity();
        if (city == null || city.getId() == null) {
            return Collections.emptyList();
        }

        List<Bot> allBots;
        try {
            allBots = botService.getFindAllByFilialCityId(city.getId());
        } catch (Exception e) {
            log.error("Ошибка при получении ботов по ID города: {}", city.getId(), e);
            return Collections.emptyList();
        }

        if (allBots == null || allBots.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> usedBotIdsInThisFilial = getUsedBotIdsInFilial(filial, review.getId());
        Set<Long> usedBotIdsGlobally = getUsedBotIdsGlobally(filial, review.getId());

        boolean vigul = review.isVigul();

        List<Bot> idealBots = allBots.stream()
                .filter(Objects::nonNull)
                .filter(bot -> bot.getId() != null)
                .filter(bot -> !usedBotIdsInThisFilial.contains(bot.getId()))
                .filter(bot -> !usedBotIdsGlobally.contains(bot.getId()))
                .filter(bot -> {
                    if (bot.getStatus() == null) {
                        return false;
                    }
                    String statusTitle = bot.getStatus().getBotStatusTitle();
                    return statusTitle != null && "Новый".equals(statusTitle.trim());
                })
                .collect(Collectors.toList());

        if (!idealBots.isEmpty()) {
            List<Bot> filteredBots = applyVigulFilters(idealBots, vigul);
            if (!filteredBots.isEmpty()) {
                return filteredBots;
            }
        }

        List<Bot> fallbackBots = allBots.stream()
                .filter(Objects::nonNull)
                .filter(bot -> bot.getId() != null)
                .filter(bot -> !usedBotIdsInThisFilial.contains(bot.getId()))
                .filter(bot -> {
                    if (bot.getStatus() == null) {
                        return false;
                    }
                    String statusTitle = bot.getStatus().getBotStatusTitle();
                    return statusTitle != null && "Новый".equals(statusTitle.trim());
                })
                .collect(Collectors.toList());

        if (!fallbackBots.isEmpty()) {
            List<Bot> filteredBots = applyVigulFilters(fallbackBots, vigul);
            if (!filteredBots.isEmpty()) {
                return filteredBots;
            }
        }

        return Collections.emptyList();
    }

    private Set<Long> getUsedBotIdsInFilial(Filial filial, Long currentReviewId) {
        Set<Long> usedBotIds = new HashSet<>();

        try {
            List<Review> allReviewsInFilial = reviewRepository.findAllByFilial(filial);

            if (allReviewsInFilial != null) {
                for (Review existingReview : allReviewsInFilial) {
                    if (existingReview != null
                            && existingReview.getId() != null
                            && !existingReview.getId().equals(currentReviewId)
                            && existingReview.getBot() != null
                            && existingReview.getBot().getId() != null) {
                        usedBotIds.add(existingReview.getBot().getId());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Ошибка при получении использованных ботов для филиала {}", filial.getId(), e);
        }

        return usedBotIds;
    }

    private Set<Long> getUsedBotIdsGlobally(Filial currentFilial, Long currentReviewId) {
        Set<Long> usedBotIds = new HashSet<>();

        try {
            City currentCity = currentFilial.getCity();
            if (currentCity == null || currentCity.getId() == null) {
                return usedBotIds;
            }

            List<Filial> filialsInSameCity = filialService.findByCityId(currentCity.getId());
            if (filialsInSameCity == null || filialsInSameCity.isEmpty()) {
                return usedBotIds;
            }

            List<Long> otherFilialIdsInCity = filialsInSameCity.stream()
                    .filter(filial -> filial != null && filial.getId() != null)
                    .filter(filial -> !filial.getId().equals(currentFilial.getId()))
                    .map(Filial::getId)
                    .collect(Collectors.toList());

            if (otherFilialIdsInCity.isEmpty()) {
                return usedBotIds;
            }

            List<Review> activeReviewsInSameCity = reviewRepository
                    .findByPublishFalseAndBotIsNotNullAndFilialIdIn(otherFilialIdsInCity);

            if (activeReviewsInSameCity != null) {
                for (Review existingReview : activeReviewsInSameCity) {
                    if (existingReview != null
                            && existingReview.getId() != null
                            && !existingReview.getId().equals(currentReviewId)
                            && existingReview.getBot() != null
                            && existingReview.getBot().getId() != null) {
                        usedBotIds.add(existingReview.getBot().getId());
                    }
                }
            }

        } catch (Exception e) {
            log.error("Ошибка при получении глобально использованных ботов", e);
        }

        return usedBotIds;
    }

    private List<Bot> applyVigulFilters(List<Bot> baseBots, boolean vigul) {
        if (!vigul) {
            List<Bot> strictFiltered = baseBots.stream()
                    .filter(this::isTemplateBotName)
                    .collect(Collectors.toList());

            if (!strictFiltered.isEmpty()) {
                return strictFiltered;
            }

            return baseBots;

        } else {
            List<Bot> strictFiltered = baseBots.stream()
                    .filter(bot -> safeBotCounter(bot) >= 3)
                    .collect(Collectors.toList());

            if (!strictFiltered.isEmpty()) {
                return strictFiltered;
            }

            List<Bot> fallbackFiltered = baseBots.stream()
                    .filter(bot -> {
                        int counter = safeBotCounter(bot);
                        return counter >= 0 && counter <= 2;
                    })
                    .collect(Collectors.toList());

            if (!fallbackFiltered.isEmpty()) {
                return fallbackFiltered;
            }

            return baseBots;
        }
    }

    private boolean isBotOverloaded(Bot bot) {
        if (bot == null || bot.getId() == null) {
            return false;
        }

        try {
            List<Review> botActiveReviews = reviewRepository.findByBotAndPublishFalse(bot);
            int maxActiveReviewsPerBot = 3;
            return botActiveReviews != null && botActiveReviews.size() >= maxActiveReviewsPerBot;
        } catch (Exception e) {
            log.error("Ошибка при проверке загруженности бота ID {}", bot.getId(), e);
            return false;
        }
    }

    private void updateVigulBasedOnBotCounter(Review review) {
        if (review == null || review.getBot() == null) {
            return;
        }

        Bot bot = review.getBot();

        if (STUB_BOT_ID.equals(bot.getId())) {
            return;
        }

        int botCounter = safeBotCounter(bot);
        boolean currentVigul = review.isVigul();

        if (currentVigul && botCounter < MAX_ACTIVE_REVIEWS_PER_BOT) {
            review.setVigul(false);
        } else if (!currentVigul && botCounter >= MAX_ACTIVE_REVIEWS_PER_BOT) {
            review.setVigul(true);
        }
    }

    @Override
    public ReviewDTOOne toReviewDTOOne(Review review) {
        try {
            OrderDetails orderDetails = review != null ? review.getOrderDetails() : null;
            Bot bot = review != null ? review.getBot() : null;

            boolean isStubBot = bot != null && bot.getId() != null && STUB_BOT_ID.equals(bot.getId());

            String botFio;
            if (orderDetails == null) {
                botFio = "НЕТ ЗАКАЗА";
            } else if (bot == null) {
                botFio = "Добавьте аккаунты и нажмите сменить";
            } else if (isStubBot) {
                botFio = "Нет доступных аккаунтов";
            } else {
                botFio = Optional.ofNullable(bot.getFio())
                        .filter(name -> !name.trim().isEmpty())
                        .orElse("Бот без имени");
            }

            String companyTitle = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getOrder)
                    .map(Order::getCompany)
                    .map(Company::getTitle)
                    .orElse("НЕТ ЗАКАЗА");

            Long companyId = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getOrder)
                    .map(Order::getCompany)
                    .map(Company::getId)
                    .orElse(null);

            UUID orderDetailsId = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getId)
                    .orElse(null);

            Long orderId = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getOrder)
                    .map(Order::getId)
                    .orElse(null);

            String orderStatus = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getOrder)
                    .map(order -> order.getStatus() != null ? order.getStatus().getTitle() : "")
                    .orElse("");

            Product reviewProduct = review.getProduct();
            Product detailsProduct = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getProduct)
                    .orElse(null);
            String productTitle = Optional.ofNullable(reviewProduct)
                    .map(Product::getTitle)
                    .orElseGet(() -> Optional.ofNullable(detailsProduct)
                            .map(Product::getTitle)
                            .orElse("НЕТ ПРОДУКТА"));
            Long productId = reviewProduct != null ? reviewProduct.getId() : null;
            boolean productPhoto = reviewProduct != null && Boolean.TRUE.equals(reviewProduct.getPhoto());

            String comment = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getComment)
                    .orElse("");

            String orderComments = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getOrder)
                    .map(Order::getZametka)
                    .orElse("");

            String commentCompany = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getOrder)
                    .map(Order::getCompany)
                    .map(Company::getCommentsCompany)
                    .orElse("");

            String workerFio = Optional.ofNullable(review.getWorker())
                    .map(Worker::getUser)
                    .map(User::getFio)
                    .orElse("");

            if (workerFio.isEmpty()) {
                workerFio = Optional.ofNullable(orderDetails)
                        .map(OrderDetails::getOrder)
                        .map(Order::getManager)
                        .map(Manager::getUser)
                        .map(User::getFio)
                        .orElse("");
            }

            String filialCity = Optional.ofNullable(review.getFilial())
                    .map(Filial::getCity)
                    .map(City::getTitle)
                    .orElse("");

            String filialTitle = Optional.ofNullable(review.getFilial())
                    .map(Filial::getTitle)
                    .orElse("");

            String filialUrl = Optional.ofNullable(review.getFilial())
                    .map(Filial::getUrl)
                    .orElse("");

            String category = Optional.ofNullable(review.getCategory())
                    .map(Category::getCategoryTitle)
                    .orElse("Нет категории");

            String subCategory = Optional.ofNullable(review.getSubCategory())
                    .map(SubCategory::getSubCategoryTitle)
                    .orElse("Нет подкатегории");

            LocalDate created = review.getCreated() != null ? review.getCreated() : LocalDate.now();
            LocalDate changed = review.getChanged() != null ? review.getChanged() : created;
            LocalDate publishedDate = review.getPublishedDate();

            Long botId = null;
            String botLogin = "";
            String botPassword = "";
            Integer botCounter = 0;

            if (bot != null) {
                botId = bot.getId();
                botLogin = Optional.ofNullable(bot.getLogin()).orElse("");
                botPassword = Optional.ofNullable(bot.getPassword()).orElse("");
                botCounter = safeBotCounter(bot);
            }

            return ReviewDTOOne.builder()
                    .id(review.getId())
                    .companyId(companyId)
                    .commentCompany(commentCompany)
                    .orderDetailsId(orderDetailsId)
                    .orderId(orderId)
                    .orderStatus(orderStatus)
                    .text(review.getText() != null ? review.getText() : "")
                    .answer(review.getAnswer() != null ? review.getAnswer() : "")
                    .category(category)
                    .subCategory(subCategory)
                    .botId(botId)
                    .botFio(botFio)
                    .botLogin(botLogin)
                    .botPassword(botPassword)
                    .botCounter(botCounter)
                    .companyTitle(companyTitle)
                    .productTitle(productTitle)
                    .filialCity(filialCity)
                    .filialTitle(filialTitle)
                    .filialUrl(filialUrl)
                    .productId(productId)
                    .workerFio(workerFio)
                    .created(created)
                    .changed(changed)
                    .publishedDate(publishedDate)
                    .publish(review.isPublish())
                    .vigul(review.isVigul())
                    .comment(comment)
                    .orderComments(orderComments)
                    .product(reviewProduct)
                    .productPhoto(productPhoto)
                    .price(review.getPrice())
                    .url(review.getUrl() != null ? review.getUrl() : "")
                    .urlPhoto(review.getUrl() != null ? review.getUrl() : "")
                    .build();

        } catch (Exception e) {
            log.error("Ошибка при преобразовании отзыва ID {} в DTO: {}",
                    review != null ? review.getId() : "null", e.getMessage(), e);

            return ReviewDTOOne.builder()
                    .id(review != null ? review.getId() : 0L)
                    .companyTitle("ОШИБКА ПРИ ОБРАБОТКЕ")
                    .botFio("ОШИБКА")
                    .text(review != null && review.getText() != null ? review.getText() : "Не удалось загрузить данные отзыва")
                    .build();
        }
    }

    public ReviewDTO getReviewDTOById(Long reviewId) {
        try {
            Optional<Review> reviewOptional = reviewRepository.findById(reviewId);
            if (reviewOptional.isEmpty()) {
                log.error("Отзыв с ID {} не найден", reviewId);
                return null;
            }

            Review review = reviewOptional.get();
            return convertToReviewDTO(review);
        } catch (EntityNotFoundException e) {
            log.error("Ошибка при загрузке отзыва ID: {}. Причина: {}", reviewId, e.getMessage());
            return null;
        }
    }

    private ReviewDTO convertToReviewDTO(Review review) {
        if (review == null) {
            log.error("Попытка преобразования null Review в DTO");
            return null;
        }

        OrderDetails orderDetails = review.getOrderDetails();

        Bot bot = null;
        boolean isStubBot = false;

        try {
            bot = review.getBot();
            if (bot != null) {
                Long botId = bot.getId();
                isStubBot = botId != null && STUB_BOT_ID.equals(botId);
            }
        } catch (EntityNotFoundException e) {
            log.warn("Бот для отзыва ID {} не найден в базе. Будет использована заглушка", review.getId());
            bot = null;
        }

        String botName = getBotName(orderDetails, bot, isStubBot);

        BotDTO botDTO;
        if (bot != null && !isStubBot) {
            try {
                botDTO = convertToBotDTO(bot);
            } catch (EntityNotFoundException e) {
                botDTO = BotDTO.builder()
                        .id(null)
                        .login("УДАЛЕН")
                        .fio("Бот был удален")
                        .password("")
                        .active(false)
                        .counter(0)
                        .status("Удален")
                        .build();
            }
        } else if (bot == null) {
            botDTO = BotDTO.builder()
                    .id(null)
                    .login("УДАЛЕН")
                    .fio("Бот был удален")
                    .password("")
                    .active(false)
                    .counter(0)
                    .status("Удален")
                    .build();
        } else {
            botDTO = convertToBotDTO(bot);
        }

        String comment = orderDetails != null ? Optional.ofNullable(orderDetails.getComment()).orElse("") : "";
        UUID orderDetailsId = orderDetails != null ? orderDetails.getId() : null;

        return ReviewDTO.builder()
                .id(review.getId())
                .text(Optional.ofNullable(review.getText()).orElse(""))
                .answer(Optional.ofNullable(review.getAnswer()).orElse(""))
                .created(Optional.ofNullable(review.getCreated()).orElse(LocalDate.now()))
                .changed(Optional.ofNullable(review.getChanged()).orElse(LocalDate.now()))
                .publishedDate(review.getPublishedDate())
                .publish(review.isPublish())
                .vigul(review.isVigul())
                .category(convertToCategoryDto(review.getCategory()))
                .subCategory(convertToSubCategoryDto(review.getSubCategory()))
                .bot(botDTO)
                .botName(botName)
                .botPassword(botDTO != null ? botDTO.getPassword() : "")
                .filial(convertToFilialDTO(review.getFilial()))
                .orderDetails(convertToDetailsDTO(orderDetails))
                .worker(convertToWorkerDTO(review.getWorker()))
                .comment(comment)
                .orderDetailsId(orderDetailsId)
                .product(review.getProduct())
                .price(review.getPrice())
                .url(Optional.ofNullable(review.getUrl()).orElse(""))
                .build();
    }

    private String getBotName(OrderDetails orderDetails, Bot bot, boolean isStubBot) {
        if (orderDetails == null) {
            return "НЕТ ЗАКАЗА";
        } else if (bot == null) {
            return "Бот был удален";
        } else if (isStubBot) {
            return "Нет доступных аккаунтов";
        } else {
            try {
                return Optional.ofNullable(bot.getFio())
                        .filter(name -> !name.trim().isEmpty())
                        .orElse("Бот без имени");
            } catch (EntityNotFoundException e) {
                return "Бот удален";
            }
        }
    }

    private BotDTO convertToBotDTO(Bot bot) {
        if (bot == null) {
            return null;
        }

        if (STUB_BOT_ID.equals(bot.getId())) {
            return BotDTO.builder()
                    .id(STUB_BOT_ID)
                    .login("stub")
                    .password("stub")
                    .fio("Нет доступных аккаунтов")
                    .active(false)
                    .counter(0)
                    .status("Заглушка")
                    .worker(null)
                    .build();
        }

        return BotDTO.builder()
                .id(bot.getId())
                .login(Optional.ofNullable(bot.getLogin()).orElse(""))
                .password(Optional.ofNullable(bot.getPassword()).orElse(""))
                .fio(Optional.ofNullable(bot.getFio()).orElse("Аккаунт без имени"))
                .active(bot.isActive())
                .counter(safeBotCounter(bot))
                .status(bot.getStatus() != null
                        ? Optional.ofNullable(bot.getStatus().getBotStatusTitle()).orElse("Неизвестен")
                        : "Неизвестен")
                .worker(bot.getWorker())
                .build();
    }

    private List<ReviewDTO> convertToReviewDTOList(List<Review> reviews) {
        return reviews.stream().map(this::convertToReviewDTO).collect(Collectors.toList());
    }

    private CategoryDTO convertToCategoryDto(Category category) {
        if (category == null) {
            return null;
        }
        CategoryDTO categoryDTO = new CategoryDTO();
        categoryDTO.setId(category.getId());
        categoryDTO.setCategoryTitle(category.getCategoryTitle());
        return categoryDTO;
    }

    private SubCategoryDTO convertToSubCategoryDto(SubCategory subCategory) {
        if (subCategory == null) {
            return null;
        }
        SubCategoryDTO subCategoryDTO = new SubCategoryDTO();
        subCategoryDTO.setId(subCategory.getId());
        subCategoryDTO.setSubCategoryTitle(subCategory.getSubCategoryTitle());
        return subCategoryDTO;
    }

    private FilialDTO convertToFilialDTO(Filial filial) {
        if (filial == null) {
            return null;
        }
        return FilialDTO.builder()
                .id(filial.getId())
                .title(filial.getTitle())
                .url(filial.getUrl())
                .build();
    }

    private WorkerDTO convertToWorkerDTO(Worker worker) {
        if (worker == null) {
            return null;
        }
        return WorkerDTO.builder()
                .workerId(worker.getId())
                .user(worker.getUser())
                .build();
    }

    private List<OrderDetailsDTO> convertToDetailsDTOList(List<OrderDetails> details) {
        if (details == null) {
            return Collections.emptyList();
        }
        return details.stream().map(this::convertToDetailsDTO).collect(Collectors.toList());
    }

    private OrderDetailsDTO convertToDetailsDTO(OrderDetails orderDetails) {
        if (orderDetails == null) {
            return null;
        }
        return OrderDetailsDTO.builder()
                .id(orderDetails.getId())
                .amount(orderDetails.getAmount())
                .price(orderDetails.getPrice())
                .publishedDate(orderDetails.getPublishedDate())
                .product(convertToProductDTO(orderDetails.getProduct()))
                .order(convertToOrderDTO(orderDetails.getOrder()))
                .comment(orderDetails.getComment())
                .build();
    }

    private ProductDTO convertToProductDTO(Product product) {
        if (product == null) {
            return null;
        }
        return ProductDTO.builder()
                .id(product.getId())
                .title(product.getTitle())
                .price(product.getPrice())
                .build();
    }

    private OrderDTO convertToOrderDTO(Order order) {
        if (order == null) {
            return null;
        }
        return OrderDTO.builder()
                .id(order.getId())
                .company(convertToCompanyDTO(order.getCompany()))
                .build();
    }

    private CompanyDTO convertToCompanyDTO(Company company) {
        if (company == null) {
            return null;
        }
        return CompanyDTO.builder()
                .id(company.getId())
                .title(company.getTitle())
                .build();
    }

    public Review getReviewById(Long reviewId) {
        return reviewRepository.findById(reviewId).orElse(null);
    }

    @Override
    @Transactional
    public boolean updateReviewText(Long orderId, Long reviewId, String text) {
        Review review = findReviewForOrder(orderId, reviewId);
        if (review == null) {
            return false;
        }

        review.setText(text);
        reviewRepository.save(review);
        return true;
    }

    @Override
    @Transactional
    public boolean updateReviewAnswer(Long orderId, Long reviewId, String answer) {
        Review review = findReviewForOrder(orderId, reviewId);
        if (review == null) {
            return false;
        }

        review.setAnswer(answer);
        reviewRepository.save(review);
        return true;
    }

    @Override
    @Transactional
    public boolean updateReviewNote(Long orderId, Long reviewId, String comment) {
        Review review = findReviewForOrder(orderId, reviewId);
        if (review == null || review.getOrderDetails() == null) {
            return false;
        }

        OrderDetails orderDetails = review.getOrderDetails();
        orderDetails.setComment(comment);
        orderDetailsService.save(orderDetails);
        return true;
    }

    private Review findReviewForOrder(Long orderId, Long reviewId) {
        Review review = reviewRepository.findById(reviewId).orElse(null);
        if (review == null || review.getOrderDetails() == null || review.getOrderDetails().getOrder() == null) {
            return null;
        }

        Long reviewOrderId = review.getOrderDetails().getOrder().getId();
        if (!Objects.equals(orderId, reviewOrderId)) {
            return null;
        }

        return review;
    }

    private Bot claimReserveBot(Review review, Set<Long> excludedBotIds) {
        if (review == null || review.getFilial() == null || review.getFilial().getCity() == null) {
            return null;
        }

        Optional<Bot> reserveBot = botService.claimReserveBotForCity(review.getFilial().getCity(), excludedBotIds);
        reserveBot.ifPresent(bot -> log.warn(
                "Для отзыва ID {} назначен резервный бот ID {} ({}) и закреплен за городом {}",
                review.getId(),
                bot.getId(),
                bot.getFio(),
                review.getFilial().getCity().getTitle()
        ));
        return reserveBot.orElse(null);
    }

    private Category convertCategoryDTOToCategory(CategoryDTO categoryDTO) {
        return categoryService.getCategoryByIdCategory(categoryDTO.getId());
    }

    private SubCategory convertSubCompanyDTOToSubCategory(SubCategoryDTO subCategoryDTO) {
        return subCategoryService.getSubCategoryById(subCategoryDTO.getId());
    }

    public Page<ReviewDTOOne> getAllReviewDTOAndDateToAdminToVigul(LocalDate localDate, int pageNumber, int pageSize) {
        return getAllReviewDTOAndDateToAdminToVigul(localDate, pageNumber, pageSize, "asc");
    }

    public Page<ReviewDTOOne> getAllReviewDTOAndDateToAdminToVigul(LocalDate localDate, int pageNumber, int pageSize, String sortDirection) {
        Pageable pageable = reviewPageable(pageNumber, pageSize, sortDirection);
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

        Pageable pageable = reviewPageable(pageNumber, pageSize, sortDirection);
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
        Pageable pageable = reviewPageable(pageNumber, pageSize, sortDirection);
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

        Pageable pageable = reviewPageable(pageNumber, pageSize, sortDirection);
        Page<Long> reviewIds = reviewRepository.findPageIdsByWorkerAndPublishedDateAndPublishToVigul(worker, localDate, pageable);
        return getReviewDTOPage(reviewIds);
    }

    @Override
    public Page<ReviewDTOOne> getAllReviewDTOAndDateToAdminToVigul(LocalDate localDate, int pageNumber, int pageSize, String sortDirection, String keyword) {
        if (!hasText(keyword)) {
            return getAllReviewDTOAndDateToAdminToVigul(localDate, pageNumber, pageSize, sortDirection);
        }
        return getReviewDTOPage(findReviewIdsForBoard(ReviewBoardMode.VIGUL, ReviewBoardScope.ADMIN,
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
        return getReviewDTOPage(findReviewIdsForBoard(ReviewBoardMode.VIGUL, ReviewBoardScope.MANAGER,
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
        return getReviewDTOPage(findReviewIdsForBoard(ReviewBoardMode.VIGUL, ReviewBoardScope.OWNER,
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
        return getReviewDTOPage(findReviewIdsForBoard(ReviewBoardMode.VIGUL, ReviewBoardScope.WORKER,
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
        Review review = reviewRepository.findById(reviewId).orElse(null);
        if (review == null) {
            return;
        }
        review.setVigul(true);
        reviewRepository.save(review);
    }

    @Override
    @Transactional
    public void performNagulWithExceptions(Long reviewId, String username) {
        User currentUser = userService.findByUserName(username)
                .orElseThrow(() -> new RuntimeException("Пользователь не найден"));

        List<String> roles = currentUser.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toList());

        boolean isWorker = roles.contains("ROLE_WORKER");
        Worker worker = null;

        if (isWorker) {
            worker = workerService.getWorkerByUserId(currentUser.getId());

            if (worker == null) {
                throw new RuntimeException("Ошибка: не найдена информация о работнике");
            }

            validateNagulCooldown(worker);
        }

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new RuntimeException("Отзыв не найден"));

        validateBotName(review);

        review.setVigul(true);

        if (isWorker && worker != null) {
            worker.setLastNagulTime(LocalDateTime.now());
            workerService.save(worker);
        }

        reviewRepository.save(review);
    }

    private void validateNagulCooldown(Worker worker) {
        if (worker.getLastNagulTime() != null) {
            LocalDateTime now = LocalDateTime.now();
            Duration duration = Duration.between(worker.getLastNagulTime(), now);

            if (duration.toMinutes() < NAGUL_COOLDOWN_MINUTES) {
                long secondsLeft = NAGUL_COOLDOWN_MINUTES * 60L - duration.getSeconds();
                long minutesLeft = secondsLeft / 60;
                long remainingSeconds = secondsLeft % 60;

                throw new NagulTooFastException(minutesLeft, remainingSeconds);
            }
        }
    }

    private void validateBotName(Review review) {
        if (review.getBot() == null || review.getBot().getFio() == null) {
            return;
        }

        String botFio = review.getBot().getFio().trim();
        String botFioLower = botFio.toLowerCase();

        if (botFio.isEmpty()) {
            throw new BotTemplateNameException("Имя бота не может быть пустым");
        }

        if (botFio.matches(".*\\d.*")) {
            throw new BotTemplateNameException("Аккаунт не выгулян: имя содержит цифры");
        }

        List<String> forbiddenFullNames = Arrays.asList(
                "имя фамилию",
                "имя фамилия",
                "впиши имя фамилию",
                "впиши имя фамилия",
                "фамилия имя",
                "фамилию имя"
        );

        if (forbiddenFullNames.contains(botFioLower)) {
            throw new BotTemplateNameException("Аккаунт не выгулян: используется шаблонное имя");
        }

        String[] parts = botFio.split("\\s+");

        if (parts.length < 2 || parts.length > 3) {
            throw new BotTemplateNameException("Аккаунт не выгулян: имя должно быть в формате 'Имя Фамилия' или 'Имя Фамилия И.О.'");
        }

        for (int i = 0; i < 2; i++) {
            String word = parts[i];
            String wordLower = word.toLowerCase();

            for (String pattern : FORBIDDEN_PATTERNS) {
                if (wordLower.equals(pattern.toLowerCase())) {
                    throw new BotTemplateNameException("Аккаунт не выгулян: используется шаблонное имя");
                }
            }

            if (!word.matches("^[А-ЯA-Z][а-яa-z]+$")) {
                throw new BotTemplateNameException("Аккаунт не выгулян: неверный формат имени или фамилии");
            }

            if (word.length() < 2) {
                throw new BotTemplateNameException("Аккаунт не выгулян: имя или фамилия слишком короткие");
            }
        }

        if (parts.length == 3) {
            String initials = parts[2];
            if (!initials.matches("^[А-ЯA-Z](\\.?[А-ЯA-Z])?\\.?$")) {
                throw new BotTemplateNameException("Аккаунт не выгулян: неверный формат инициалов. Допустимые форматы: С.И., СИ, С., С, С.И, СИ.");
            }

            if (initials.contains("..")) {
                throw new BotTemplateNameException("Аккаунт не выгулян: некорректные инициалы (две точки подряд)");
            }

            String lettersOnly = initials.replace(".", "");
            for (char c : lettersOnly.toCharArray()) {
                if (!Character.isUpperCase(c)) {
                    throw new BotTemplateNameException("Аккаунт не выгулян: инициалы должны быть заглавными буквами");
                }
            }
        }

        if (parts[0].equalsIgnoreCase(parts[1])) {
            throw new BotTemplateNameException("Аккаунт не выгулян: имя и фамилия не могут быть одинаковыми");
        }
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
        Pageable pageable = reviewPageable(pageNumber, pageSize, sortDirection);
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

    private boolean isTemplateBotName(Bot bot) {
        return bot != null && bot.getFio() != null && TEMPLATE_BOT_NAMES.contains(bot.getFio().trim());
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

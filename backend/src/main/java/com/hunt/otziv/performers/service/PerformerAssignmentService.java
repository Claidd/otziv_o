package com.hunt.otziv.performers.service;

import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.service.CityDistanceService;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.performers.dto.PerformerAssignmentResponse;
import com.hunt.otziv.performers.dto.PerformerBoardResponse;
import com.hunt.otziv.performers.dto.PerformerProblemRequest;
import com.hunt.otziv.performers.dto.PerformerPublishRequest;
import com.hunt.otziv.performers.model.*;
import com.hunt.otziv.performers.repository.*;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class PerformerAssignmentService {

    private static final String STATUS_PUBLISHED = "Опубликовано";
    private static final Collection<PerformerAssignmentStatus> TERMINAL_SUCCESS = List.of(
            PerformerAssignmentStatus.VERIFIED,
            PerformerAssignmentStatus.PAID
    );

    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;
    private final PerformerProfileRepository performerProfileRepository;
    private final PerformerCityRepository performerCityRepository;
    private final ReviewPerformerAssignmentRepository assignmentRepository;
    private final ReviewPerformerOfferRepository offerRepository;
    private final PerformerTaskEvidenceRepository evidenceRepository;
    private final PerformerPayoutRepository payoutRepository;
    private final PerformerAssignmentMapper mapper;
    private final PerformerTelegramNotificationService telegramNotificationService;
    private final OrderStatusTransitionService orderStatusTransitionService;
    private final PerformerRolloutService rolloutService;
    private final PerformerAssignmentScreenshotStorage screenshotStorage;
    private final CityDistanceService cityDistanceService;

    @Value("${performers.offer.ttl-minutes:10}")
    private int offerTtlMinutes;

    @Value("${performers.offer.batch-size:20}")
    private int offerBatchSize;

    @Value("${performers.offer.candidate-pool-size:500}")
    private int offerCandidatePoolSize;

    @Value("${performers.publish.delay-days:2}")
    private int publishDelayDays;

    @Value("${performers.assignment.create-lead-days:2}")
    private int assignmentCreateLeadDays;

    @Value("${performers.assignment.batch-size:100}")
    private int assignmentBatchSize;

    @Value("${performers.payout.default-amount:0}")
    private BigDecimal defaultPayoutAmount;

    @Transactional
    public int createAssignmentsForOrder(Long orderId) {
        Order order = orderRepository.findByIdForOrderDto(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        List<Review> reviews = reviewRepository.getAllByOrderId(orderId);
        LocalDate cutoffDate = assignmentCutoffDate();
        int created = 0;

        for (Review review : reviews) {
            if (createAssignmentIfEligible(order, review, cutoffDate)) {
                created++;
            }
        }

        if (created > 0) {
            log.info("Созданы задания исполнителям для заказа {}: {}", orderId, created);
        }
        return created;
    }

    @Transactional
    public int createDueAssignments() {
        LocalDate cutoffDate = assignmentCutoffDate();
        List<Review> reviews = reviewRepository.findPerformerAssignmentCandidates(
                cutoffDate,
                PageRequest.of(0, assignmentBatchSize)
        );
        int created = 0;
        for (Review review : reviews) {
            if (createAssignmentIfEligible(order(review), review, cutoffDate)) {
                created++;
            }
        }
        if (created > 0) {
            log.info("Созданы задания исполнителям по датам публикации до {}: {}", cutoffDate, created);
        }
        return created;
    }

    @Transactional
    public int offerQueuedAssignments() {
        List<ReviewPerformerAssignment> assignments = assignmentRepository.findQueue(
                List.of(PerformerAssignmentStatus.CREATED),
                PageRequest.of(0, offerBatchSize)
        );
        int offered = 0;
        for (ReviewPerformerAssignment assignment : assignments) {
            if (!rolloutService.isAllowed(assignment)) {
                continue;
            }
            if (createOffer(assignment)) {
                offered++;
            }
        }
        return offered;
    }

    @Transactional
    public int expireOffers() {
        List<ReviewPerformerOffer> offers = offerRepository.findExpired(LocalDateTime.now(), PageRequest.of(0, offerBatchSize));
        int expired = 0;
        for (ReviewPerformerOffer offer : offers) {
            offer.setStatus(PerformerOfferStatus.EXPIRED);
            offer.setRespondedAt(LocalDateTime.now());
            offerRepository.save(offer);

            PerformerProfile performer = offer.getPerformer();
            performer.setExpiredOfferCount(performer.getExpiredOfferCount() + 1);
            performerProfileRepository.save(performer);

            ReviewPerformerAssignment assignment = offer.getAssignment();
            if (assignment.getStatus() == PerformerAssignmentStatus.OFFERING) {
                assignment.setStatus(PerformerAssignmentStatus.CREATED);
                assignmentRepository.save(assignment);
            }
            expired++;
        }
        return expired;
    }

    @Transactional
    public int notifyReadyToPublish() {
        List<ReviewPerformerAssignment> assignments = assignmentRepository.findReadyToPublish(
                LocalDateTime.now(),
                PageRequest.of(0, offerBatchSize)
        );
        assignments.forEach(telegramNotificationService::sendReadyToPublish);
        return assignments.size();
    }

    @Transactional(readOnly = true)
    public PerformerBoardResponse board(String username) {
        PerformerProfile performer = performer(username);
        return new PerformerBoardResponse(
                offerRepository.findOfferedByPerformer(performer.getId())
                        .stream().map(offer -> mapper.toResponse(offer.getAssignment())).toList(),
                assignmentRepository.findByPerformerForBoard(
                        performer.getId(),
                        List.of(PerformerAssignmentStatus.ACCEPTED, PerformerAssignmentStatus.WALKED)
                ).stream().map(mapper::toResponse).toList(),
                assignmentRepository.findByPerformerForBoard(performer.getId(), List.of(PerformerAssignmentStatus.WAITING_PUBLICATION))
                        .stream().map(mapper::toResponse).toList(),
                assignmentRepository.findByPerformerForBoard(performer.getId(), List.of(PerformerAssignmentStatus.PUBLISHED_CLAIMED))
                        .stream().map(mapper::toResponse).toList(),
                assignmentRepository.findByPerformerForBoard(performer.getId(), List.of(PerformerAssignmentStatus.VERIFIED, PerformerAssignmentStatus.PAID))
                        .stream().map(mapper::toResponse).toList()
        );
    }

    @Transactional
    public PerformerAssignmentResponse acceptOffer(Long offerId, String username) {
        PerformerProfile performer = performer(username);
        return acceptOfferInternal(offerId, performer.getId());
    }

    @Transactional
    public PerformerAssignmentResponse acceptOfferFromTelegram(
            Long offerId,
            Long telegramUserId,
            Long telegramChatId
    ) {
        ReviewPerformerOffer offer = offerRepository.findByIdForAction(offerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Предложение не найдено"));
        PerformerProfile performer = requireActiveTelegramPerformer(offer, telegramUserId, telegramChatId);
        return acceptOfferInternal(offer, performer.getId());
    }

    @Transactional
    public void declineOffer(Long offerId, String username, String reason) {
        PerformerProfile performer = performer(username);
        declineOfferInternal(offerId, performer.getId(), reason);
    }

    @Transactional
    public void declineOfferFromTelegram(Long offerId, Long telegramUserId, Long telegramChatId) {
        ReviewPerformerOffer offer = offerRepository.findByIdForAction(offerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Предложение не найдено"));
        PerformerProfile performer = requireActiveTelegramPerformer(offer, telegramUserId, telegramChatId);
        declineOfferInternal(offer, performer.getId(), "Отказ из Telegram");
    }

    @Transactional
    public PerformerAssignmentResponse markWalked(Long assignmentId, String username) {
        PerformerProfile performer = performer(username);
        ReviewPerformerAssignment assignment = assignmentForPerformer(assignmentId, performer);
        if (assignment.getStatus() != PerformerAssignmentStatus.ACCEPTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Отметить выгул можно только по принятому заданию");
        }
        LocalDateTime now = LocalDateTime.now();
        assignment.setStatus(PerformerAssignmentStatus.WAITING_PUBLICATION);
        assignment.setWalkedAt(now);
        assignment.setPublishAvailableAt(now.plusDays(Math.max(0, publishDelayDays)));
        if (assignment.getReview() != null) {
            assignment.getReview().setVigul(true);
            reviewRepository.save(assignment.getReview());
        }
        assignmentRepository.save(assignment);
        evidenceRepository.save(PerformerTaskEvidence.builder()
                .assignment(assignment)
                .type(PerformerTaskEvidenceType.WALK)
                .comment("Исполнитель отметил выгул")
                .build());
        return mapper.toResponse(assignment);
    }

    @Transactional
    public PerformerAssignmentResponse markPublished(Long assignmentId, String username, PerformerPublishRequest request) {
        PerformerProfile performer = performer(username);
        ReviewPerformerAssignment assignment = assignmentForPerformer(assignmentId, performer);
        if (assignment.getStatus() != PerformerAssignmentStatus.WAITING_PUBLICATION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Опубликовать можно только после выгула и выдержки");
        }
        if (assignment.getPublishAvailableAt() != null && assignment.getPublishAvailableAt().isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Публикация станет доступна " + assignment.getPublishAvailableAt());
        }
        if (request == null || !hasText(request.getFinalText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите опубликованный текст");
        }

        LocalDateTime now = LocalDateTime.now();
        String finalText = request.getFinalText().trim();
        assignment.setStatus(PerformerAssignmentStatus.PUBLISHED_CLAIMED);
        assignment.setPublishedClaimedAt(now);
        assignment.setPerformerFinalText(finalText);
        assignment.setTextChangedByPerformer(!normalized(finalText).equals(normalized(assignment.getClientApprovedTextSnapshot())));
        assignment.setPublicationUrl(trimToNull(request.getPublicationUrl()));

        Review review = assignment.getReview();
        if (review != null) {
            review.setPublish(true);
            review.setPublishedMarkedAt(now);
            review.setPublishedDate(LocalDate.now());
            review.setUrl(trimToNull(request.getPublicationUrl()));
            review.setExternalConfirmStatus("PENDING");
            reviewRepository.save(review);
        }

        assignmentRepository.save(assignment);
        evidenceRepository.save(PerformerTaskEvidence.builder()
                .assignment(assignment)
                .type(PerformerTaskEvidenceType.PUBLISH)
                .comment(trimToNull(request.getComment()))
                .build());
        return mapper.toResponse(assignment);
    }

    @Transactional
    public PerformerAssignmentResponse uploadPublicationScreenshot(Long assignmentId, String username, MultipartFile file) {
        PerformerProfile performer = performer(username);
        ReviewPerformerAssignment assignment = assignmentForPerformer(assignmentId, performer);
        if (assignment.getStatus() == PerformerAssignmentStatus.REJECTED
                || assignment.getStatus() == PerformerAssignmentStatus.CANCELLED
                || assignment.getStatus() == PerformerAssignmentStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "К этому заданию уже нельзя загрузить скриншот");
        }
        String url = screenshotStorage.store(
                file,
                assignment.getId(),
                PerformerAssignmentScreenshotStorage.ScreenshotKind.PERFORMER_PUBLICATION,
                assignment.getPerformerPublicationScreenshotUrl()
        );
        assignment.setPerformerPublicationScreenshotUrl(url);
        assignmentRepository.save(assignment);
        return mapper.toResponse(assignment);
    }

    @Transactional
    public PerformerAssignmentResponse reportProblem(Long assignmentId, String username, PerformerProblemRequest request) {
        PerformerProfile performer = performer(username);
        ReviewPerformerAssignment assignment = assignmentForPerformer(assignmentId, performer);
        String comment = request == null ? "" : request.getComment();
        if (!hasText(comment)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Опишите проблему");
        }
        assignment.setStatus(PerformerAssignmentStatus.REJECTED);
        assignment.setRejectReason(comment.trim());
        assignment.setCancelledAt(LocalDateTime.now());
        assignmentRepository.save(assignment);
        evidenceRepository.save(PerformerTaskEvidence.builder()
                .assignment(assignment)
                .type(PerformerTaskEvidenceType.PROBLEM)
                .comment(comment.trim())
                .build());
        performer.setCancelledCount(performer.getCancelledCount() + 1);
        performerProfileRepository.save(performer);
        return mapper.toResponse(assignment);
    }

    @Transactional
    public void markVerifiedByReview(Long reviewId) {
        ReviewPerformerAssignment assignment = assignmentRepository.findByReviewId(reviewId).orElse(null);
        if (assignment == null || assignment.getStatus() == PerformerAssignmentStatus.VERIFIED || assignment.getStatus() == PerformerAssignmentStatus.PAID) {
            return;
        }
        if (assignment.getStatus() != PerformerAssignmentStatus.PUBLISHED_CLAIMED) {
            return;
        }
        assignment.setStatus(PerformerAssignmentStatus.VERIFIED);
        assignment.setVerifiedAt(LocalDateTime.now());
        assignmentRepository.save(assignment);
        approvePayout(assignment);
        maybeCloseOrder(assignment.getOrder().getId());
    }

    @Transactional(readOnly = true)
    public String textForExternalCheck(Review review) {
        if (review == null || review.getId() == null) {
            return "";
        }
        return assignmentRepository.findByReviewId(review.getId())
                .map(ReviewPerformerAssignment::getPerformerFinalText)
                .filter(this::hasText)
                .orElse(review.getText());
    }

    private boolean createOffer(ReviewPerformerAssignment assignment) {
        if (assignment.getCity() == null || assignment.getOrder() == null) {
            assignment.setStatus(PerformerAssignmentStatus.REJECTED);
            assignment.setRejectReason("Не указан город задания");
            assignmentRepository.save(assignment);
            return false;
        }
        List<PerformerProfile> candidates = performerProfileRepository.findOfferCandidates(
                assignment.getCity().getId(),
                assignment.getOrder().getId(),
                assignment.getOrder().getCompany() != null ? assignment.getOrder().getCompany().getId() : null,
                assignment.getId(),
                PerformerProfileStatus.ACTIVE,
                PageRequest.of(0, Math.max(1, offerCandidatePoolSize))
        );
        if (candidates.isEmpty()) {
            return false;
        }

        PerformerProfile performer = bestCandidate(assignment.getCity().getId(), candidates);
        LocalDateTime now = LocalDateTime.now();
        ReviewPerformerOffer offer = ReviewPerformerOffer.builder()
                .assignment(assignment)
                .performer(performer)
                .status(PerformerOfferStatus.OFFERED)
                .offeredAt(now)
                .expiresAt(now.plusMinutes(Math.max(1, offerTtlMinutes)))
                .telegramChatId(performer.getUser().getTelegramChatId())
                .build();
        offerRepository.save(offer);

        assignment.setStatus(PerformerAssignmentStatus.OFFERING);
        assignmentRepository.save(assignment);

        telegramNotificationService.sendOffer(offer)
                .ifPresent(messageId -> {
                    offer.setTelegramMessageId(messageId);
                    offerRepository.save(offer);
                });
        return true;
    }

    private PerformerProfile bestCandidate(Long cityId, List<PerformerProfile> candidates) {
        Map<Long, Integer> distanceByCity = cityDistanceService.distancesFrom(cityId);
        Map<Long, List<Long>> cityIdsByPerformer = cityIdsByPerformer(candidates);
        return candidates.stream()
                .min(Comparator
                        .comparingInt((PerformerProfile performer) -> cityBucket(performer, cityId, distanceByCity, cityIdsByPerformer))
                        .thenComparingInt(performer -> cityDistance(performer, cityId, distanceByCity, cityIdsByPerformer))
                        .thenComparing(PerformerProfile::getRating, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PerformerProfile::getReliabilityScore, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PerformerProfile::getCompletedCount, Comparator.reverseOrder())
                        .thenComparing(PerformerProfile::getId))
                .orElse(candidates.getFirst());
    }

    private Map<Long, List<Long>> cityIdsByPerformer(List<PerformerProfile> candidates) {
        List<Long> performerIds = candidates.stream()
                .map(PerformerProfile::getId)
                .filter(Objects::nonNull)
                .toList();
        if (performerIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> result = new HashMap<>();
        performerCityRepository.findActiveCityIdsByPerformerIds(performerIds)
                .forEach(row -> result.computeIfAbsent(row.getPerformerId(), ignored -> new ArrayList<>()).add(row.getCityId()));
        return result;
    }

    private int cityBucket(
            PerformerProfile performer,
            Long cityId,
            Map<Long, Integer> distanceByCity,
            Map<Long, List<Long>> cityIdsByPerformer
    ) {
        List<Long> performerCityIds = performerCityIds(performer, cityIdsByPerformer);
        if (performerCityIds.isEmpty()) {
            return 2;
        }
        if (performerCityIds.stream().anyMatch(candidateCityId -> Objects.equals(candidateCityId, cityId))) {
            return 0;
        }
        return performerCityIds.stream().anyMatch(distanceByCity::containsKey) ? 1 : 2;
    }

    private int cityDistance(
            PerformerProfile performer,
            Long cityId,
            Map<Long, Integer> distanceByCity,
            Map<Long, List<Long>> cityIdsByPerformer
    ) {
        List<Long> performerCityIds = performerCityIds(performer, cityIdsByPerformer);
        if (performerCityIds.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        if (performerCityIds.stream().anyMatch(candidateCityId -> Objects.equals(candidateCityId, cityId))) {
            return 0;
        }
        return performerCityIds.stream()
                .map(candidateCityId -> distanceByCity.getOrDefault(candidateCityId, Integer.MAX_VALUE))
                .min(Integer::compareTo)
                .orElse(Integer.MAX_VALUE);
    }

    private List<Long> performerCityIds(PerformerProfile performer, Map<Long, List<Long>> cityIdsByPerformer) {
        if (performer == null) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        if (performer.getCity() != null && performer.getCity().getId() != null) {
            result.add(performer.getCity().getId());
        }
        result.addAll(cityIdsByPerformer.getOrDefault(performer.getId(), List.of()));
        return result.stream().filter(Objects::nonNull).distinct().toList();
    }

    private PerformerAssignmentResponse acceptOfferInternal(Long offerId, Long performerId) {
        ReviewPerformerOffer offer = offerRepository.findByIdForAction(offerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Предложение не найдено"));
        return acceptOfferInternal(offer, performerId);
    }

    private PerformerAssignmentResponse acceptOfferInternal(ReviewPerformerOffer offer, Long performerId) {
        if (!Objects.equals(offer.getPerformer().getId(), performerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Это предложение назначено другому исполнителю");
        }
        if (offer.getStatus() != PerformerOfferStatus.OFFERED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Предложение уже обработано");
        }
        if (offer.getExpiresAt().isBefore(LocalDateTime.now())) {
            offer.setStatus(PerformerOfferStatus.EXPIRED);
            offer.setRespondedAt(LocalDateTime.now());
            offerRepository.save(offer);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Время предложения истекло");
        }
        ReviewPerformerAssignment assignment = offer.getAssignment();
        if (assignment.getStatus() != PerformerAssignmentStatus.OFFERING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Задание уже недоступно");
        }

        offer.setStatus(PerformerOfferStatus.ACCEPTED);
        offer.setRespondedAt(LocalDateTime.now());
        offerRepository.save(offer);

        offerRepository.findByAssignmentIdAndStatuses(assignment.getId(), List.of(PerformerOfferStatus.OFFERED))
                .forEach(other -> {
                    if (!Objects.equals(other.getId(), offer.getId())) {
                        other.setStatus(PerformerOfferStatus.SKIPPED);
                        other.setRespondedAt(LocalDateTime.now());
                        offerRepository.save(other);
                    }
                });

        assignment.setPerformer(offer.getPerformer());
        assignment.setStatus(PerformerAssignmentStatus.ACCEPTED);
        assignment.setAcceptedAt(LocalDateTime.now());
        assignmentRepository.save(assignment);
        telegramNotificationService.sendAccepted(assignment);
        return mapper.toResponse(assignment);
    }

    private void declineOfferInternal(Long offerId, Long performerId, String reason) {
        ReviewPerformerOffer offer = offerRepository.findByIdForAction(offerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Предложение не найдено"));
        declineOfferInternal(offer, performerId, reason);
    }

    private void declineOfferInternal(ReviewPerformerOffer offer, Long performerId, String reason) {
        if (!Objects.equals(offer.getPerformer().getId(), performerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Это предложение назначено другому исполнителю");
        }
        if (offer.getStatus() != PerformerOfferStatus.OFFERED) {
            return;
        }
        offer.setStatus(PerformerOfferStatus.DECLINED);
        offer.setRespondedAt(LocalDateTime.now());
        offer.setDeclineReason(trimToNull(reason));
        offerRepository.save(offer);

        ReviewPerformerAssignment assignment = offer.getAssignment();
        if (assignment.getStatus() == PerformerAssignmentStatus.OFFERING) {
            assignment.setStatus(PerformerAssignmentStatus.CREATED);
            assignmentRepository.save(assignment);
        }
    }

    private PerformerProfile requireActiveTelegramPerformer(
            ReviewPerformerOffer offer,
            Long telegramUserId,
            Long telegramChatId
    ) {
        PerformerProfile performer = offer == null ? null : offer.getPerformer();
        User user = performer == null ? null : performer.getUser();
        Long expectedChatId = user == null ? null : user.getTelegramChatId();
        Long offeredChatId = offer == null ? null : offer.getTelegramChatId();
        boolean identityMatches = expectedChatId != null
                && Objects.equals(expectedChatId, telegramUserId)
                && Objects.equals(expectedChatId, telegramChatId)
                && (offeredChatId == null || Objects.equals(expectedChatId, offeredChatId));
        if (!identityMatches) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Команда принадлежит другому Telegram-аккаунту"
            );
        }
        if (performer.getStatus() != PerformerProfileStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Профиль исполнителя еще не активирован");
        }
        return performer;
    }

    private boolean createAssignmentIfEligible(Order order, Review review, LocalDate cutoffDate) {
        if (order == null || review == null || review.getId() == null) {
            return false;
        }
        Product product = product(review);
        if (product == null || !product.isRequiresPerformer()) {
            return false;
        }
        if (review.isPublish()) {
            return false;
        }
        if (review.getPublishedDate() == null || review.getPublishedDate().isAfter(cutoffDate)) {
            return false;
        }
        Filial filial = filial(order, review);
        City city = filial != null ? filial.getCity() : null;
        if (!rolloutService.isAllowed(product, city)) {
            return false;
        }
        if (assignmentRepository.existsByReviewId(review.getId())) {
            return false;
        }

        ReviewPerformerAssignment assignment = ReviewPerformerAssignment.builder()
                .order(order)
                .orderDetails(review.getOrderDetails())
                .review(review)
                .city(city)
                .filial(filial)
                .platform(platform(product, filial))
                .status(PerformerAssignmentStatus.CREATED)
                .payoutAmount(payoutAmount(review, product))
                .clientApprovedTextSnapshot(review.getText())
                .instruction(instruction(order, filial))
                .build();
        assignmentRepository.save(assignment);
        return true;
    }

    private void approvePayout(ReviewPerformerAssignment assignment) {
        if (assignment.getPerformer() == null || payoutRepository.existsByAssignmentId(assignment.getId())) {
            return;
        }
        BigDecimal amount = assignment.getPayoutAmount() == null ? defaultPayoutAmount : assignment.getPayoutAmount();
        PerformerPayout payout = PerformerPayout.builder()
                .assignment(assignment)
                .performer(assignment.getPerformer())
                .amount(amount)
                .status(PerformerPayoutStatus.APPROVED)
                .approvedAt(LocalDateTime.now())
                .build();
        payoutRepository.save(payout);

        PerformerProfile performer = assignment.getPerformer();
        performer.setCompletedCount(performer.getCompletedCount() + 1);
        performer.setLastActiveAt(LocalDateTime.now());
        performerProfileRepository.save(performer);
    }

    private void maybeCloseOrder(Long orderId) {
        long total = assignmentRepository.countByOrderId(orderId);
        if (total == 0) {
            return;
        }
        long notVerified = assignmentRepository.countNotInStatusesByOrderId(orderId, TERMINAL_SUCCESS);
        if (notVerified > 0) {
            return;
        }
        try {
            orderStatusTransitionService.changeStatusForOrder(orderId, STATUS_PUBLISHED);
        } catch (Exception e) {
            log.warn("Не удалось автоматически перевести заказ {} в '{}'", orderId, STATUS_PUBLISHED, e);
        }
    }

    private ReviewPerformerAssignment assignmentForPerformer(Long assignmentId, PerformerProfile performer) {
        ReviewPerformerAssignment assignment = assignmentRepository.findByIdForDetails(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Задание не найдено"));
        if (assignment.getPerformer() == null || !Objects.equals(assignment.getPerformer().getId(), performer.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Задание назначено другому исполнителю");
        }
        return assignment;
    }

    private PerformerProfile performer(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не найден"));
        PerformerProfile performer = performerProfileRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Профиль исполнителя не найден"));
        if (performer.getStatus() != PerformerProfileStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Профиль исполнителя еще не активирован");
        }
        return performer;
    }

    private Product product(Review review) {
        if (review.getProduct() != null) {
            return review.getProduct();
        }
        OrderDetails details = review.getOrderDetails();
        return details != null ? details.getProduct() : null;
    }

    private Order order(Review review) {
        OrderDetails details = review != null ? review.getOrderDetails() : null;
        return details != null ? details.getOrder() : null;
    }

    private Filial filial(Order order, Review review) {
        if (order != null && order.getFilial() != null) {
            return order.getFilial();
        }
        return review != null ? review.getFilial() : null;
    }

    private PerformerPlatform platform(Product product, Filial filial) {
        String target = product != null ? product.getTargetPlatform() : null;
        if (hasText(target)) {
            try {
                return PerformerPlatform.valueOf(target.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return PerformerPlatform.OTHER;
            }
        }
        String url = filial == null ? "" : safe(filial.getUrl()).toLowerCase(Locale.ROOT);
        if (url.contains("yandex") || url.contains("ya.ru")) {
            return PerformerPlatform.YANDEX;
        }
        if (url.contains("google") || url.contains("maps.app.goo.gl")) {
            return PerformerPlatform.GOOGLE;
        }
        if (url.contains("2gis")) {
            return PerformerPlatform.GIS;
        }
        return PerformerPlatform.OTHER;
    }

    private BigDecimal payoutAmount(Review review, Product product) {
        if (product != null && product.isRequiresPerformer()) {
            BigDecimal percent = product.getPerformerRewardPercent() == null
                    ? BigDecimal.ZERO
                    : product.getPerformerRewardPercent();
            BigDecimal price = product.getPrice() == null ? BigDecimal.ZERO : product.getPrice();
            if (percent.compareTo(BigDecimal.ZERO) <= 0 || price.compareTo(BigDecimal.ZERO) <= 0) {
                return defaultPayoutAmount;
            }
            return price.multiply(percent)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        if (review != null && review.getPrice() != null) {
            return review.getPrice();
        }
        if (product != null && product.getPrice() != null) {
            return product.getPrice();
        }
        return defaultPayoutAmount;
    }

    private LocalDate assignmentCutoffDate() {
        return LocalDate.now().plusDays(Math.max(0, assignmentCreateLeadDays));
    }

    private String instruction(Order order, Filial filial) {
        String company = order != null && order.getCompany() != null ? safe(order.getCompany().getTitle()) : "компанию";
        String url = filial != null ? safe(filial.getUrl()) : "";
        return "Проверьте " + company + ": посетите точку, вручную найдите карточку на площадке, изучите ее, при необходимости позвоните или постройте маршрут. "
                + "Публикуйте отзыв только если фактический опыт соответствует черновику."
                + (url.isBlank() ? "" : " Ссылка на карточку: " + url);
    }

    private String normalized(String value) {
        return safe(value).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

package com.hunt.otziv.performers.service;

import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.repository.CityRepository;
import com.hunt.otziv.performers.dto.AdminPerformerControlResponse;
import com.hunt.otziv.performers.dto.AdminPerformerManualRunResponse;
import com.hunt.otziv.performers.dto.AdminPerformerResponse;
import com.hunt.otziv.performers.dto.AdminPerformerVerifyAssignmentRequest;
import com.hunt.otziv.performers.dto.PerformerAssignmentResponse;
import com.hunt.otziv.performers.dto.PerformerCityReportResponse;
import com.hunt.otziv.performers.model.PerformerAssignmentStatus;
import com.hunt.otziv.performers.model.PerformerProfile;
import com.hunt.otziv.performers.model.PerformerProfileStatus;
import com.hunt.otziv.performers.model.ReviewPerformerAssignment;
import com.hunt.otziv.performers.repository.PerformerProfileRepository;
import com.hunt.otziv.performers.repository.ReviewPerformerAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AdminPerformerService {

    private final PerformerProfileRepository performerProfileRepository;
    private final ReviewPerformerAssignmentRepository assignmentRepository;
    private final CityRepository cityRepository;
    private final PerformerAssignmentMapper assignmentMapper;
    private final PerformerAssignmentService assignmentService;
    private final PerformerRolloutService rolloutService;
    private final PerformerAssignmentScreenshotStorage screenshotStorage;

    @Transactional(readOnly = true)
    public AdminPerformerControlResponse control() {
        List<PerformerProfile> performers = performerProfileRepository.findAllForAdmin(EnumSet.allOf(PerformerProfileStatus.class));
        List<ReviewPerformerAssignment> assignments = assignmentRepository.findAllForAdmin(EnumSet.allOf(PerformerAssignmentStatus.class));
        List<City> cities = cityRepository.findAll();
        return new AdminPerformerControlResponse(
                performers.stream().map(this::toResponse).toList(),
                assignments.stream().map(assignmentMapper::toResponse).toList(),
                cities.stream().map(city -> cityReport(city, performers, assignments)).toList(),
                rolloutService.settings()
        );
    }

    @Transactional
    public AdminPerformerResponse updateStatus(Long performerId, PerformerProfileStatus status, String reason) {
        PerformerProfile performer = performerProfileRepository.findById(performerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Исполнитель не найден"));
        performer.setStatus(status);
        performer.setBlockReason(reason);
        performer.setModeratedAt(LocalDateTime.now());
        performerProfileRepository.save(performer);
        return toResponse(performer);
    }

    @Transactional
    public PerformerAssignmentResponse verifyAssignment(Long assignmentId, AdminPerformerVerifyAssignmentRequest request) {
        ReviewPerformerAssignment assignment = assignmentRepository.findByIdForDetails(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Задание не найдено"));
        if (assignment.getReview() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У задания нет отзыва");
        }
        if (request != null) {
            if (hasText(request.getManagerNote())) {
                assignment.setManagerNote(trimToNull(request.getManagerNote()));
                assignmentRepository.save(assignment);
            }
        }
        if (assignment.getStatus() != PerformerAssignmentStatus.PUBLISHED_CLAIMED
                && assignment.getStatus() != PerformerAssignmentStatus.VERIFIED) {
            assignment.setStatus(PerformerAssignmentStatus.PUBLISHED_CLAIMED);
            assignment.setPublishedClaimedAt(LocalDateTime.now());
            assignmentRepository.save(assignment);
        }
        assignmentService.markVerifiedByReview(assignment.getReview().getId());
        return assignmentRepository.findByIdForDetails(assignmentId)
                .map(assignmentMapper::toResponse)
                .orElseThrow();
    }

    @Transactional
    public PerformerAssignmentResponse uploadManagerConfirmationScreenshot(Long assignmentId, MultipartFile file) {
        ReviewPerformerAssignment assignment = assignmentRepository.findByIdForDetails(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Задание не найдено"));
        String url = screenshotStorage.store(
                file,
                assignment.getId(),
                PerformerAssignmentScreenshotStorage.ScreenshotKind.MANAGER_CONFIRMATION,
                assignment.getManagerConfirmationScreenshotUrl()
        );
        assignment.setManagerConfirmationScreenshotUrl(url);
        assignmentRepository.save(assignment);
        return assignmentMapper.toResponse(assignment);
    }

    @Transactional
    public AdminPerformerManualRunResponse createAssignmentsForOrder(Long orderId) {
        int created = assignmentService.createAssignmentsForOrder(orderId);
        return new AdminPerformerManualRunResponse(created, 0, 0, 0);
    }

    @Transactional
    public AdminPerformerManualRunResponse runSchedulerOnce() {
        int created = assignmentService.createDueAssignments();
        int expired = assignmentService.expireOffers();
        int offered = assignmentService.offerQueuedAssignments();
        int ready = assignmentService.notifyReadyToPublish();
        return new AdminPerformerManualRunResponse(created, expired, offered, ready);
    }

    private AdminPerformerResponse toResponse(PerformerProfile performer) {
        return new AdminPerformerResponse(
                performer.getId(),
                performer.getUser() != null ? performer.getUser().getId() : null,
                performer.getUser() != null ? safe(performer.getUser().getUsername()) : "",
                performer.getUser() != null ? safe(performer.getUser().getFio()) : "",
                performer.getUser() != null ? safe(performer.getUser().getPhoneNumber()) : "",
                performer.getCity() != null ? safe(performer.getCity().getTitle()) : "",
                performer.getGender() != null ? performer.getGender().name() : "",
                performer.getStatus() != null ? performer.getStatus().name() : "",
                performer.getRating(),
                performer.getReliabilityScore(),
                performer.getCompletedCount(),
                performer.getCancelledCount(),
                performer.getExpiredOfferCount(),
                performer.getFailedCheckCount(),
                performer.getUser() != null ? performer.getUser().getTelegramChatId() : null
        );
    }

    private PerformerCityReportResponse cityReport(
            City city,
            List<PerformerProfile> performers,
            List<ReviewPerformerAssignment> assignments
    ) {
        long activePerformers = performers.stream()
                .filter(performer -> performer.getStatus() == PerformerProfileStatus.ACTIVE)
                .filter(performer -> performer.getCity() != null && Objects.equals(performer.getCity().getId(), city.getId()))
                .count();
        long queue = countCityAssignments(city, assignments, PerformerAssignmentStatus.CREATED, PerformerAssignmentStatus.OFFERING);
        long active = countCityAssignments(city, assignments,
                PerformerAssignmentStatus.ACCEPTED,
                PerformerAssignmentStatus.WALKED,
                PerformerAssignmentStatus.WAITING_PUBLICATION,
                PerformerAssignmentStatus.PUBLISHED_CLAIMED);
        long verified = countCityAssignments(city, assignments, PerformerAssignmentStatus.VERIFIED, PerformerAssignmentStatus.PAID);
        long rejected = countCityAssignments(city, assignments, PerformerAssignmentStatus.REJECTED, PerformerAssignmentStatus.CANCELLED);
        return new PerformerCityReportResponse(city.getId(), safe(city.getTitle()), activePerformers, queue, active, verified, rejected);
    }

    private long countCityAssignments(City city, List<ReviewPerformerAssignment> assignments, PerformerAssignmentStatus... statuses) {
        List<PerformerAssignmentStatus> statusList = List.of(statuses);
        return assignments.stream()
                .filter(assignment -> assignment.getCity() != null && Objects.equals(assignment.getCity().getId(), city.getId()))
                .filter(assignment -> statusList.contains(assignment.getStatus()))
                .count();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

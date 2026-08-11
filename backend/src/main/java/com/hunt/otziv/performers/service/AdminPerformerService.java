package com.hunt.otziv.performers.service;

import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.repository.CityRepository;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentProfileService;
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
import com.hunt.otziv.u_users.dto.UpdateKeycloakUserRequest;
import com.hunt.otziv.u_users.keycloak.client.KeycloakAdminClient;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.service.UserAuthEpochService;
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
    private final UserRepository userRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final UserAuthEpochService authEpochService;
    private final ContractorPaymentProfileService contractorPaymentProfileService;

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
    public AdminPerformerResponse updateStatus(
            Long performerId,
            PerformerProfileStatus status,
            String reason,
            boolean phoneVerified,
            String moderatorUsername
    ) {
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Статус не передан");
        }
        PerformerProfile performer = performerProfileRepository.findById(performerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Исполнитель не найден"));
        PerformerProfileStatus previousStatus = performer.getStatus();
        LocalDateTime now = LocalDateTime.now();

        if (previousStatus != PerformerProfileStatus.ACTIVE && status == PerformerProfileStatus.ACTIVE) {
            validateActivation(performer, phoneVerified, reason, now);
            performer.setPhoneVerifiedAt(now);
            performer.setPhoneVerificationMethod("ADMIN_MANUAL");
            performer.setPhoneVerificationNote(trimToNull(reason));
        }

        synchronizeAccountEnabled(performer.getUser(), status == PerformerProfileStatus.ACTIVE);
        userRepository.flush();
        contractorPaymentProfileService.ensureForUser(performer.getUser().getId());
        performer.setStatus(status);
        performer.setBlockReason(status == PerformerProfileStatus.ACTIVE ? null : trimToNull(reason));
        performer.setModeratedAt(now);
        if (hasText(moderatorUsername)) {
            userRepository.findByUsername(moderatorUsername.trim()).ifPresent(performer::setModeratedBy);
        }
        performerProfileRepository.save(performer);
        return toResponse(performer);
    }

    private void validateActivation(
            PerformerProfile performer,
            boolean phoneVerified,
            String reason,
            LocalDateTime now
    ) {
        if (!phoneVerified || !hasText(reason)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Перед активацией вручную проверьте телефон и укажите способ проверки в причине"
            );
        }

        // This flag records only that the profile was already ACTIVE when the
        // secure lifecycle migration ran. Legacy consent/verification evidence
        // is deliberately not invented; a fresh manual phone check is still
        // required above whenever such a profile is reactivated.
        if (performer.isLegacyApprovedBeforeSecureLifecycle()) {
            return;
        }

        boolean previouslyApproved = performer.getPhoneVerifiedAt() != null;
        boolean pendingWindowValid = performer.getRegistrationExpiresAt() != null
                && performer.getRegistrationExpiresAt().isAfter(now);
        if (!previouslyApproved && !pendingWindowValid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Срок заявки истёк; нужна новая регистрация");
        }
        if (!hasCurrentRegistrationConsents(performer)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Нет согласий с актуальными версиями условий");
        }
        if (!hasLinkedPersonalTelegram(performer)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сначала исполнитель должен привязать личный Telegram");
        }
    }

    private void synchronizeAccountEnabled(User user, boolean enabled) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У исполнителя отсутствует учётная запись");
        }
        if (hasText(user.getKeycloakId())) {
            UpdateKeycloakUserRequest request = new UpdateKeycloakUserRequest();
            request.setUsername(user.getUsername());
            request.setEmail(user.getEmail());
            request.setFio(user.getFio());
            request.setPhoneNumber(user.getPhoneNumber());
            request.setEnabled(enabled);
            keycloakAdminClient.updateUser(user.getKeycloakId(), user.getUsername(), request);
        }
        if (user.isActive() != enabled) {
            if (enabled) {
                authEpochService.reactivated(user);
            } else {
                authEpochService.deactivated(user);
            }
            userRepository.save(user);
        }
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
                performer.getUser() != null ? performer.getUser().getTelegramChatId() : null,
                performer.getTelegramLinkedAt(),
                performer.getRegistrationExpiresAt(),
                performer.getPhoneVerifiedAt(),
                safe(performer.getPhoneVerificationMethod()),
                safe(performer.getPersonalDataConsentVersion()),
                safe(performer.getRulesConsentVersion()),
                safe(performer.getHonestReviewConsentVersion()),
                activationReady(performer, LocalDateTime.now()),
                performer.isLegacyApprovedBeforeSecureLifecycle(),
                activationWarning(performer, LocalDateTime.now())
        );
    }

    private boolean activationReady(PerformerProfile performer, LocalDateTime now) {
        if (performer.getStatus() == PerformerProfileStatus.ACTIVE) {
            return false;
        }
        if (performer.isLegacyApprovedBeforeSecureLifecycle()) {
            return performer.getUser() != null;
        }
        boolean pendingWindowValid = performer.getRegistrationExpiresAt() != null
                && performer.getRegistrationExpiresAt().isAfter(now);
        return (performer.getPhoneVerifiedAt() != null || pendingWindowValid)
                && hasLinkedPersonalTelegram(performer)
                && hasCurrentRegistrationConsents(performer);
    }

    private boolean hasLinkedPersonalTelegram(PerformerProfile performer) {
        return performer.getTelegramLinkedAt() != null
                && performer.getUser() != null
                && performer.getUser().getTelegramChatId() != null;
    }

    private boolean hasCurrentRegistrationConsents(PerformerProfile performer) {
        return performer.getPersonalDataAcceptedAt() != null
                && performer.getRulesAcceptedAt() != null
                && performer.getHonestReviewAcceptedAt() != null
                && PerformerRegistrationService.PERSONAL_DATA_CONSENT_VERSION.equals(
                        performer.getPersonalDataConsentVersion())
                && PerformerRegistrationService.RULES_CONSENT_VERSION.equals(performer.getRulesConsentVersion())
                && PerformerRegistrationService.HONEST_REVIEW_CONSENT_VERSION.equals(
                        performer.getHonestReviewConsentVersion());
    }

    private String activationWarning(PerformerProfile performer, LocalDateTime now) {
        if (performer.isLegacyApprovedBeforeSecureLifecycle()) {
            return "Legacy-профиль: был ACTIVE до защищённого цикла регистрации; исторические согласия и проверка телефона не подтверждаются. Перед повторной активацией вручную проверьте телефон.";
        }
        if (performer.getStatus() != PerformerProfileStatus.ACTIVE
                && performer.getPhoneVerifiedAt() == null
                && (performer.getRegistrationExpiresAt() == null
                    || !performer.getRegistrationExpiresAt().isAfter(now))) {
            return "Заявка истекла или создана без защищённого цикла; требуется новая регистрация.";
        }
        return "";
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

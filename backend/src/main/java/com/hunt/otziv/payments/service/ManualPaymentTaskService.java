package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.dto.CreateManualPaymentTaskRequest;
import com.hunt.otziv.payments.dto.ManualPaymentTaskResponse;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskStatus;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.repository.ManualPaymentTaskRepository;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ManualPaymentTaskService {

    private static final Set<PaymentLinkStatus> RESERVED_STATUSES = Set.of(
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED,
            PaymentLinkStatus.CONFIRMED
    );
    private static final Set<PaymentLinkStatus> CONFIRMED_STATUSES = Set.of(PaymentLinkStatus.CONFIRMED);
    private static final Set<PaymentLinkStatus> PENDING_STATUSES = Set.of(
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED
    );
    private static final Set<PaymentMethod> MANUAL_PAYMENT_METHODS = Set.of(
            PaymentMethod.MANUAL_MOBILE_BANK,
            PaymentMethod.MANUAL_EXTERNAL_LINK
    );

    private final ManualPaymentTaskRepository manualPaymentTaskRepository;
    private final PaymentLinkRepository paymentLinkRepository;
    private final ManagerRepository managerRepository;
    private final PaymentProfileService paymentProfileService;

    @Transactional(readOnly = true)
    public List<ManualPaymentTaskResponse> managerTasks(Long userId) {
        return manualPaymentTaskRepository.findAllByManagerUserId(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ManualPaymentTaskResponse createManagerTask(
            Long userId,
            CreateManualPaymentTaskRequest request,
            String actor
    ) {
        Manager manager = managerByUserId(userId);
        return createTask(manager, request, actor);
    }

    @Transactional
    public ManualPaymentTaskResponse createManagementTask(
            CreateManualPaymentTaskRequest request,
            String actor
    ) {
        Manager manager = managerById(request == null ? null : request.managerId());
        return createTask(manager, request, actor);
    }

    private ManualPaymentTaskResponse createTask(
            Manager manager,
            CreateManualPaymentTaskRequest request,
            String actor
    ) {
        PaymentProfile profile = paymentProfileService.selectForManager(manager);
        ManualPaymentTask task = new ManualPaymentTask();
        task.setManager(manager);
        task.setPaymentProfile(profile);
        task.setStatus(ManualPaymentTaskStatus.ACTIVE);
        ManualPaymentType type = parseManualPaymentType(request == null ? null : request.manualPaymentType());
        task.setManualPaymentType(type);
        task.setManualPhone(limit(request == null ? null : request.manualPhone(), 32));
        task.setManualRecipientName(recipientOrDefault(request == null ? null : request.manualRecipientName()));
        task.setManualPaymentUrl(paymentUrlOrDefault(request == null ? null : request.manualPaymentUrl()));
        task.setManualPaymentButtonLabel(buttonLabelOrDefault(request == null ? null : request.manualPaymentButtonLabel()));
        validatePaymentTarget(task);
        task.setTargetAmountKopecks(requiredPositive(request == null ? null : request.targetAmountKopecks()));
        task.setComment(limit(request == null ? null : request.comment(), 255));
        task.setCreatedBy(limit(actor, 160));
        task.setUpdatedBy(limit(actor, 160));
        return toResponse(manualPaymentTaskRepository.save(task));
    }

    @Transactional(readOnly = true)
    public List<ManualPaymentTaskResponse> managementTasks() {
        return manualPaymentTaskRepository.findAllForManagement().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ManualPaymentTaskResponse updateManagerTaskStatus(
            Long userId,
            Long taskId,
            String status,
            String actor
    ) {
        ManualPaymentTask task = taskById(taskId);
        Manager manager = task.getManager();
        Long ownerUserId = manager == null || manager.getUser() == null ? null : manager.getUser().getId();
        if (userId == null || !userId.equals(ownerUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Можно менять только свои платежные задания");
        }
        return updateStatus(task, status, actor);
    }

    @Transactional
    public ManualPaymentTaskResponse updateManagementTaskStatus(
            Long taskId,
            String status,
            String actor
    ) {
        return updateStatus(taskById(taskId), status, actor);
    }

    @Transactional(readOnly = true)
    public Optional<ManualPaymentTask> findRoutableTask(
            Manager manager,
            PaymentProfile profile,
            long amountKopecks,
            Long excludedLinkId
    ) {
        if (manager == null
                || manager.getId() == null
                || profile == null
                || profile.getId() == null
                || amountKopecks <= 0) {
            return Optional.empty();
        }

        return manualPaymentTaskRepository
                .findActiveForRouting(manager.getId(), profile.getId(), ManualPaymentTaskStatus.ACTIVE)
                .stream()
                .filter(task -> isRoutable(task, amountKopecks, excludedLinkId))
                .findFirst();
    }

    @Transactional
    public void completeIfConfirmedTargetReached(ManualPaymentTask task) {
        if (task == null || task.getId() == null || task.getStatus() != ManualPaymentTaskStatus.ACTIVE) {
            return;
        }
        long confirmed = taskAmount(task.getId(), CONFIRMED_STATUSES);
        if (confirmed < task.getTargetAmountKopecks()) {
            return;
        }
        task.setStatus(ManualPaymentTaskStatus.COMPLETED);
        task.setCompletedAt(LocalDateTime.now());
        manualPaymentTaskRepository.save(task);
    }

    private ManualPaymentTaskResponse updateStatus(ManualPaymentTask task, String status, String actor) {
        ManualPaymentTaskStatus newStatus = parseStatus(status);
        task.setStatus(newStatus);
        task.setUpdatedBy(limit(actor, 160));
        if (newStatus == ManualPaymentTaskStatus.COMPLETED) {
            task.setCompletedAt(task.getCompletedAt() == null ? LocalDateTime.now() : task.getCompletedAt());
        } else if (newStatus == ManualPaymentTaskStatus.ACTIVE) {
            task.setCompletedAt(null);
        }
        return toResponse(manualPaymentTaskRepository.save(task));
    }

    private ManualPaymentTask taskById(Long taskId) {
        if (taskId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежное задание не найдено");
        }
        return manualPaymentTaskRepository.findByIdWithDetails(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежное задание не найдено"));
    }

    private Manager managerByUserId(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не найден");
        }
        return managerRepository.findByUserIdWithPaymentProfile(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Профиль менеджера не найден"));
    }

    private Manager managerById(Long managerId) {
        if (managerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите менеджера для задания");
        }
        return managerRepository.findByIdWithPaymentProfile(managerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер не найден"));
    }

    private boolean isRoutable(ManualPaymentTask task, long amountKopecks, Long excludedLinkId) {
        if (task.getStatus() != ManualPaymentTaskStatus.ACTIVE
                || !hasPaymentTarget(task)
                || task.getTargetAmountKopecks() <= 0) {
            return false;
        }
        long reserved = taskAmount(task.getId(), RESERVED_STATUSES, excludedLinkId);
        return reserved + amountKopecks <= task.getTargetAmountKopecks();
    }

    private ManualPaymentTaskResponse toResponse(ManualPaymentTask task) {
        Long taskId = task.getId();
        long reserved = taskAmount(taskId, RESERVED_STATUSES);
        long confirmed = taskAmount(taskId, CONFIRMED_STATUSES);
        long pendingAmount = Math.max(0, reserved - confirmed);
        long remaining = Math.max(0, task.getTargetAmountKopecks() - reserved);
        long pendingCount = taskId == null ? 0 : paymentLinkRepository.countActiveManualPendingForTask(
                taskId,
                MANUAL_PAYMENT_METHODS,
                PENDING_STATUSES,
                LocalDateTime.now()
        );
        Manager manager = task.getManager();
        User user = manager == null ? null : manager.getUser();
        PaymentProfile profile = task.getPaymentProfile();
        return new ManualPaymentTaskResponse(
                task.getId(),
                manager == null ? null : manager.getId(),
                managerTitle(user),
                user == null ? "" : normalize(user.getUsername()),
                profile == null ? null : profile.getId(),
                profile == null ? "" : normalize(profile.getName()),
                task.getStatus() == null ? ManualPaymentTaskStatus.ACTIVE.name() : task.getStatus().name(),
                manualPaymentType(task).name(),
                normalize(task.getManualPhone()),
                recipientOrDefault(task.getManualRecipientName()),
                paymentUrlOrDefault(task.getManualPaymentUrl()),
                buttonLabelOrDefault(task.getManualPaymentButtonLabel()),
                task.getTargetAmountKopecks(),
                reserved,
                confirmed,
                pendingAmount,
                remaining,
                pendingCount,
                normalize(task.getComment()),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt(),
                isRoutableForAnyAmount(task, remaining)
        );
    }

    private boolean isRoutableForAnyAmount(ManualPaymentTask task, long remaining) {
        return task.getStatus() == ManualPaymentTaskStatus.ACTIVE
                && remaining > 0
                && hasPaymentTarget(task);
    }

    private long taskAmount(Long taskId, Collection<PaymentLinkStatus> statuses) {
        return taskAmount(taskId, statuses, null);
    }

    private long taskAmount(Long taskId, Collection<PaymentLinkStatus> statuses, Long excludedLinkId) {
        if (taskId == null) {
            return 0;
        }
        return paymentLinkRepository.sumManualReservedAndConfirmedForTask(
                taskId,
                MANUAL_PAYMENT_METHODS,
                statuses,
                LocalDateTime.now(),
                PaymentLinkStatus.CONFIRMED,
                excludedLinkId
        );
    }

    private ManualPaymentType manualPaymentType(ManualPaymentTask task) {
        return task.getManualPaymentType() == null ? ManualPaymentType.MOBILE_BANK : task.getManualPaymentType();
    }

    private boolean hasPaymentTarget(ManualPaymentTask task) {
        if (manualPaymentType(task) == ManualPaymentType.MOBILE_BANK) {
            return !normalize(task.getManualPhone()).isBlank()
                    && !normalize(task.getManualRecipientName()).isBlank();
        }
        return !normalize(task.getManualPaymentUrl()).isBlank();
    }

    private void validatePaymentTarget(ManualPaymentTask task) {
        if (manualPaymentType(task) == ManualPaymentType.MOBILE_BANK) {
            if (normalize(task.getManualPhone()).isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите телефон");
            }
            if (normalize(task.getManualRecipientName()).isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите получателя");
            }
            return;
        }
        if (normalize(task.getManualPaymentUrl()).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите ссылку Альфа-Банка");
        }
    }

    private ManualPaymentTaskStatus parseStatus(String value) {
        String clean = normalize(value).toUpperCase(Locale.ROOT);
        if (clean.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите статус задания");
        }
        try {
            return ManualPaymentTaskStatus.valueOf(clean);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный статус задания", e);
        }
    }

    private long requiredPositive(Long value) {
        if (value == null || value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите сумму задания");
        }
        return value;
    }

    private ManualPaymentType parseManualPaymentType(String value) {
        String clean = normalize(value).toUpperCase(Locale.ROOT);
        if (clean.isBlank()) {
            return ManualPaymentType.MOBILE_BANK;
        }
        try {
            return ManualPaymentType.valueOf(clean);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный тип ручной оплаты", e);
        }
    }

    private String paymentUrlOrDefault(String value) {
        String clean = limit(value, 512);
        return clean.isBlank() ? ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL : clean;
    }

    private String buttonLabelOrDefault(String value) {
        String clean = limit(value, 80);
        return clean.isBlank() ? ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_BUTTON_LABEL : clean;
    }

    private String recipientOrDefault(String value) {
        String clean = limit(value, 160);
        return clean.isBlank() || ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_BUTTON_LABEL.equals(clean)
                ? ManualPaymentType.DEFAULT_MANUAL_RECIPIENT_NAME
                : clean;
    }

    private String managerTitle(User user) {
        String fio = user == null ? "" : normalize(user.getFio());
        return fio.isBlank() ? (user == null ? "" : normalize(user.getUsername())) : fio;
    }

    private String limit(String value, int maxLength) {
        String clean = normalize(value);
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

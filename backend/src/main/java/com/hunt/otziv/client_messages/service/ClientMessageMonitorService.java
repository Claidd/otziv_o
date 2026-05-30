package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.dto.ArchiveCompanyCandidateDiagnostics;
import com.hunt.otziv.client_messages.dto.ClientMessageMonitorResponse;
import com.hunt.otziv.client_messages.dto.ClientMessagePreview;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageAttempt;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageAttemptStatus;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ArchiveCompanyMessageCandidateRepository;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageAttemptRepository;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import java.time.Clock;
import java.time.format.DateTimeParseException;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.function.Function;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ClientMessageMonitorService {

    private static final int QUEUE_LIMIT = 30;
    private static final int ATTEMPT_LIMIT = 40;

    private final ScheduledClientMessageStateRepository stateRepository;
    private final ScheduledClientMessageAttemptRepository attemptRepository;
    private final OrderRepository orderRepository;
    private final CompanyRepository companyRepository;
    private final AppSettingService appSettingService;
    private final ClientMessageSlotPlanner slotPlanner;
    private final ArchiveCompanyMessageCandidateRepository archiveCandidateRepository;
    private final ClientMessagePreviewService previewService;
    private final Clock clock = Clock.systemDefaultZone();

    @Transactional(readOnly = true)
    public ClientMessageMonitorResponse snapshot() {
        boolean enabled = monitorEnabled();
        boolean workerEnabled = appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_WORKER_ENABLED, true);
        boolean liveEnabled = appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true);
        String businessWindows = businessWindows();
        LocalDateTime nowStorage = LocalDateTime.now(clock);
        LocalDateTime nowIrkutsk = nowIrkutsk();
        boolean windowAllowed = slotPlanner.isAllowedNow(nowIrkutsk, businessWindows);
        LocalDateTime rawPausedUntil = clientMessagesPausedUntil();
        boolean paused = rawPausedUntil != null && rawPausedUntil.isAfter(nowStorage);
        LocalDateTime pausedUntil = paused ? toIrkutskTime(rawPausedUntil) : null;
        String pauseReason = paused ? appSettingService.getString(AppSettingService.CLIENT_MESSAGES_PAUSE_REASON, null) : null;

        if (!enabled) {
            return new ClientMessageMonitorResponse(
                    false,
                    workerEnabled,
                    liveEnabled,
                    windowAllowed,
                    businessWindows,
                    nowIrkutsk,
                    nowIrkutsk,
                    null,
                    pausedUntil,
                    pauseReason,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    archiveDiagnostics(nowStorage),
                    scenarioSummaries(
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            workerEnabled,
                            liveEnabled,
                            windowAllowed,
                            paused
                    ),
                    List.of(),
                    List.of()
            );
        }

        LocalDateTime todayStartStorage = toStorageTime(nowIrkutsk.toLocalDate().atStartOfDay());
        LocalDateTime sevenDaysStartStorage = toStorageTime(nowIrkutsk.minusDays(7).toLocalDate().atStartOfDay());
        Map<ClientMessageScenario, Long> activeByScenario = scenarioCountMap(
                stateRepository.countByStatusGrouped(ScheduledMessageStateStatus.ACTIVE)
        );
        Map<ClientMessageScenario, Long> dueByScenario = scenarioCountMap(
                stateRepository.countDueByScenario(ScheduledMessageStateStatus.ACTIVE, nowStorage)
        );
        Map<ClientMessageScenario, Long> missingChannelByScenario = nativeScenarioCountMap(
                stateRepository.countMissingChannelBindingsByScenario(ScheduledMessageStateStatus.ACTIVE.name())
        );
        Map<ClientMessageScenario, Long> dueMissingChannelByScenario = nativeScenarioCountMap(
                stateRepository.countDueMissingChannelBindingsByScenario(ScheduledMessageStateStatus.ACTIVE.name(), nowStorage)
        );
        Map<ClientMessageScenario, Long> sentTodayByScenario = attemptCountMap(
                attemptRepository.countByStatusSinceGrouped(ScheduledMessageAttemptStatus.SENT, todayStartStorage)
        );
        Map<ClientMessageScenario, Long> sentSevenDaysByScenario = attemptCountMap(
                attemptRepository.countByStatusSinceGrouped(ScheduledMessageAttemptStatus.SENT, sevenDaysStartStorage)
        );
        Map<ClientMessageScenario, Long> failedTodayByScenario = attemptCountMap(
                attemptRepository.countByStatusSinceGrouped(ScheduledMessageAttemptStatus.FAILED, todayStartStorage)
        );
        Map<ClientMessageScenario, Long> skippedTodayByScenario = attemptCountMap(
                attemptRepository.countByStatusSinceGrouped(ScheduledMessageAttemptStatus.SKIPPED, todayStartStorage)
        );

        List<ScheduledClientMessageState> queueStates = stateRepository.findMonitorQueue(
                ScheduledMessageStateStatus.ACTIVE,
                PageRequest.of(0, QUEUE_LIMIT)
        );
        List<ScheduledClientMessageAttempt> recentAttempts = attemptRepository.findRecent(PageRequest.of(0, ATTEMPT_LIMIT));
        Resolution resolution = resolve(queueStates, recentAttempts);

        long sentToday = attemptRepository.countByStatusAndAttemptedAtGreaterThanEqual(
                ScheduledMessageAttemptStatus.SENT,
                todayStartStorage
        );
        long failedToday = attemptRepository.countByStatusAndAttemptedAtGreaterThanEqual(
                ScheduledMessageAttemptStatus.FAILED,
                todayStartStorage
        );
        long skippedToday = attemptRepository.countByStatusAndAttemptedAtGreaterThanEqual(
                ScheduledMessageAttemptStatus.SKIPPED,
                todayStartStorage
        );
        long missingChannelBindings = missingChannelByScenario.values().stream().mapToLong(Long::longValue).sum();
        long waitingForWindow = waitingForWindow(dueByScenario, workerEnabled, windowAllowed, paused);
        long readyToSendNow = readyToSendNow(dueByScenario, dueMissingChannelByScenario, workerEnabled, liveEnabled, windowAllowed, paused);
        LocalDateTime nextAttemptAt = stateRepository.findNextAttempt(
                        ScheduledMessageStateStatus.ACTIVE,
                        PageRequest.of(0, 1)
                ).stream()
                .map(ScheduledClientMessageState::getNextAttemptAt)
                .filter(Objects::nonNull)
                .findFirst()
                .map(this::toIrkutskTime)
                .orElse(null);

        return new ClientMessageMonitorResponse(
                true,
                workerEnabled,
                liveEnabled,
                windowAllowed,
                businessWindows,
                nowIrkutsk,
                nowIrkutsk,
                nextAttemptAt,
                pausedUntil,
                pauseReason,
                stateRepository.countByStatus(ScheduledMessageStateStatus.ACTIVE),
                stateRepository.countDue(ScheduledMessageStateStatus.ACTIVE, nowStorage),
                readyToSendNow,
                waitingForWindow,
                missingChannelBindings,
                sentToday,
                failedToday,
                skippedToday,
                stateRepository.countByStatus(ScheduledMessageStateStatus.DISABLED),
                archiveDiagnostics(nowStorage),
                scenarioSummaries(
                        activeByScenario,
                        dueByScenario,
                        missingChannelByScenario,
                        dueMissingChannelByScenario,
                        sentTodayByScenario,
                        sentSevenDaysByScenario,
                        failedTodayByScenario,
                        skippedTodayByScenario,
                        workerEnabled,
                        liveEnabled,
                        windowAllowed,
                        paused
                ),
                queueStates.stream().map((state) -> queueItem(state, resolution, nowStorage, workerEnabled, liveEnabled, windowAllowed, paused)).toList(),
                recentAttempts.stream().map((attempt) -> attemptItem(attempt, resolution)).toList()
        );
    }

    @Transactional(readOnly = true)
    public boolean monitorEnabled() {
        return appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_MONITOR_ENABLED, false);
    }

    @Transactional
    public boolean setMonitorEnabled(boolean enabled) {
        return appSettingService.setBoolean(AppSettingService.CLIENT_MESSAGES_MONITOR_ENABLED, enabled);
    }

    @Transactional
    public ClientMessageMonitorResponse retryNow(Long stateId) {
        ScheduledClientMessageState state = activeState(stateId);
        LocalDateTime nowStorage = LocalDateTime.now(clock).withNano(0);
        state.setNextAttemptAt(nowStorage);
        state.setLockedUntil(null);
        state.setLastErrorCode(null);
        state.setLastErrorMessage(null);
        stateRepository.save(state);
        recordManualAttempt(state, "manual_retry_now", "Кандидат вручную поставлен на ближайшую попытку");
        return snapshot();
    }

    @Transactional
    public ClientMessageMonitorResponse disable(Long stateId) {
        ScheduledClientMessageState state = activeState(stateId);
        LocalDateTime nowStorage = LocalDateTime.now(clock).withNano(0);
        state.setStatus(ScheduledMessageStateStatus.DISABLED);
        state.setNextAttemptAt(null);
        state.setLockedUntil(null);
        state.setLastAttemptAt(nowStorage);
        state.setLastErrorCode("manual_disabled");
        state.setLastErrorMessage("Отключено вручную из мониторинга");
        stateRepository.save(state);
        recordManualAttempt(state, "manual_disabled", "Кандидат отключен вручную из мониторинга");
        return snapshot();
    }

    @Transactional
    public ClientMessageMonitorResponse markDone(Long stateId) {
        ScheduledClientMessageState state = activeState(stateId);
        LocalDateTime nowStorage = LocalDateTime.now(clock).withNano(0);
        state.setStatus(ScheduledMessageStateStatus.DONE);
        state.setNextAttemptAt(null);
        state.setLockedUntil(null);
        state.setLastAttemptAt(nowStorage);
        state.setLastErrorCode(null);
        state.setLastErrorMessage(null);
        stateRepository.save(state);
        recordManualAttempt(state, "manual_done", "Кандидат помечен выполненным вручную из мониторинга");
        return snapshot();
    }

    private List<ClientMessageMonitorResponse.ScenarioSummary> scenarioSummaries(
            Map<ClientMessageScenario, Long> activeByScenario,
            Map<ClientMessageScenario, Long> dueByScenario,
            Map<ClientMessageScenario, Long> missingChannelByScenario,
            Map<ClientMessageScenario, Long> dueMissingChannelByScenario,
            Map<ClientMessageScenario, Long> sentTodayByScenario,
            Map<ClientMessageScenario, Long> sentSevenDaysByScenario,
            Map<ClientMessageScenario, Long> failedTodayByScenario,
            Map<ClientMessageScenario, Long> skippedTodayByScenario,
            boolean workerEnabled,
            boolean liveEnabled,
            boolean windowAllowed,
            boolean paused
    ) {
        List<ClientMessageMonitorResponse.ScenarioSummary> result = new ArrayList<>();
        for (ClientMessageScenario scenario : ClientMessageScenario.values()) {
            ScheduledClientMessageAttempt lastError = attemptRepository
                    .findFirstByScenarioAndStatusOrderByAttemptedAtDesc(scenario, ScheduledMessageAttemptStatus.FAILED)
                    .orElse(null);
            if (lastError != null && isResolvedConfigurationError(lastError, liveEnabled)) {
                lastError = null;
            }
            long dueNow = dueByScenario.getOrDefault(scenario, 0L);
            long dueMissingChannel = dueMissingChannelByScenario.getOrDefault(scenario, 0L);
            result.add(new ClientMessageMonitorResponse.ScenarioSummary(
                    scenario.name(),
                    scenarioLabel(scenario),
                    activeByScenario.getOrDefault(scenario, 0L),
                    dueNow,
                    readyToSendNow(scenario, dueNow, dueMissingChannel, workerEnabled, liveEnabled, windowAllowed, paused),
                    waitingForWindow(scenario, dueNow, workerEnabled, windowAllowed, paused),
                    missingChannelByScenario.getOrDefault(scenario, 0L),
                    sentTodayByScenario.getOrDefault(scenario, 0L),
                    sentSevenDaysByScenario.getOrDefault(scenario, 0L),
                    failedTodayByScenario.getOrDefault(scenario, 0L),
                    skippedTodayByScenario.getOrDefault(scenario, 0L),
                    lastError == null ? null : firstText(lastError.getErrorMessage(), lastError.getErrorCode()),
                    lastError == null ? null : toIrkutskTime(lastError.getAttemptedAt())
            ));
        }
        return result;
    }

    private ClientMessageMonitorResponse.QueueItem queueItem(
            ScheduledClientMessageState state,
            Resolution resolution,
            LocalDateTime nowStorage,
            boolean workerEnabled,
            boolean liveEnabled,
            boolean windowAllowed,
            boolean paused
    ) {
        TargetInfo target = targetInfo(state.getCompanyId(), state.getOrderId(), resolution);
        ClientMessagePreview preview = previewService.preview(state, target.order(), target.company());
        QueueReadiness readiness = queueReadiness(state, target.company(), nowStorage, workerEnabled, liveEnabled, windowAllowed, paused);
        return new ClientMessageMonitorResponse.QueueItem(
                state.getId(),
                state.getScenario().name(),
                scenarioLabel(state.getScenario()),
                state.getTargetType().name(),
                state.getTargetKey(),
                target.companyId(),
                target.companyTitle(),
                state.getOrderId(),
                target.orderTitle(),
                target.statusTitle(),
                toIrkutskTime(state.getNextAttemptAt()),
                toIrkutskTime(state.getLastAttemptAt()),
                toIrkutskTime(state.getLastSuccessAt()),
                state.getLastErrorCode(),
                state.getLastErrorMessage(),
                state.getSentCount(),
                state.getConsecutiveFailures(),
                preview.expectedChannel(),
                preview.channelDetails(),
                preview.paymentInstructionSource(),
                preview.messagePreview(),
                readiness.code(),
                readiness.label(),
                readiness.reason(),
                target.link()
        );
    }

    private ScheduledClientMessageState activeState(Long stateId) {
        if (stateId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не указан кандидат автоответчика");
        }
        ScheduledClientMessageState state = stateRepository.findById(stateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Кандидат автоответчика не найден"));
        if (state.getStatus() != ScheduledMessageStateStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Кандидат уже не активен");
        }
        return state;
    }

    private void recordManualAttempt(ScheduledClientMessageState state, String code, String message) {
        attemptRepository.save(ScheduledClientMessageAttempt.builder()
                .stateId(state.getId())
                .scenario(state.getScenario())
                .targetType(state.getTargetType())
                .targetKey(state.getTargetKey())
                .companyId(state.getCompanyId())
                .orderId(state.getOrderId())
                .archiveOrderId(state.getArchiveOrderId())
                .status(ScheduledMessageAttemptStatus.SKIPPED)
                .errorCode(code)
                .errorMessage(message)
                .messagePreview(message)
                .durationMs(0L)
                .build());
    }

    private ClientMessageMonitorResponse.AttemptItem attemptItem(
            ScheduledClientMessageAttempt attempt,
            Resolution resolution
    ) {
        TargetInfo target = targetInfo(attempt.getCompanyId(), attempt.getOrderId(), resolution);
        return new ClientMessageMonitorResponse.AttemptItem(
                attempt.getId(),
                attempt.getStateId(),
                attempt.getScenario().name(),
                scenarioLabel(attempt.getScenario()),
                attempt.getTargetType().name(),
                attempt.getTargetKey(),
                target.companyId(),
                target.companyTitle(),
                attempt.getOrderId(),
                target.orderTitle(),
                attempt.getStatus().name(),
                attemptStatusLabel(attempt.getStatus()),
                attempt.getChannel(),
                attempt.getErrorCode(),
                attempt.getErrorMessage(),
                attempt.getMessagePreview(),
                attempt.getDurationMs(),
                toIrkutskTime(attempt.getAttemptedAt()),
                target.link()
        );
    }

    private Resolution resolve(
            List<ScheduledClientMessageState> states,
            List<ScheduledClientMessageAttempt> attempts
    ) {
        Set<Long> orderIds = new LinkedHashSet<>();
        Set<Long> companyIds = new LinkedHashSet<>();
        states.forEach((state) -> {
            if (state.getOrderId() != null) {
                orderIds.add(state.getOrderId());
            }
            if (state.getCompanyId() != null) {
                companyIds.add(state.getCompanyId());
            }
        });
        attempts.forEach((attempt) -> {
            if (attempt.getOrderId() != null) {
                orderIds.add(attempt.getOrderId());
            }
            if (attempt.getCompanyId() != null) {
                companyIds.add(attempt.getCompanyId());
            }
        });

        Map<Long, Order> orders = orderIds.stream()
                .map((id) -> orderRepository.findByIdForMutation(id).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Order::getId, Function.identity(), (left, right) -> left));
        orders.values().stream()
                .map(Order::getCompany)
                .filter(Objects::nonNull)
                .map(Company::getId)
                .filter(Objects::nonNull)
                .forEach(companyIds::add);
        Map<Long, Company> companies = StreamSupport.stream(companyRepository.findAllById(companyIds).spliterator(), false)
                .collect(Collectors.toMap(Company::getId, Function.identity(), (left, right) -> left));
        return new Resolution(orders, companies);
    }

    private TargetInfo targetInfo(Long companyId, Long orderId, Resolution resolution) {
        Order order = orderId == null ? null : resolution.orders().get(orderId);
        Company company = order != null && order.getCompany() != null
                ? order.getCompany()
                : companyId == null ? null : resolution.companies().get(companyId);
        Long resolvedCompanyId = company == null ? companyId : company.getId();
        String companyTitle = company == null || !hasText(company.getTitle()) ? "Компания #" + nullSafe(resolvedCompanyId) : company.getTitle();
        String orderTitle = order == null
                ? null
                : List.of(companyTitle, filialTitle(order)).stream().filter(this::hasText).collect(Collectors.joining(" - "));
        String statusTitle = order != null
                ? orderStatusTitle(order)
                : companyStatusTitle(company);
        String link = order != null && resolvedCompanyId != null
                ? "/manager/orders/" + resolvedCompanyId + "/" + order.getId()
                : resolvedCompanyId == null ? null : "/manager";
        return new TargetInfo(resolvedCompanyId, companyTitle, orderTitle, statusTitle, link, order, company);
    }

    private String scenarioLabel(ClientMessageScenario scenario) {
        return switch (scenario) {
            case CLIENT_TEXT_REMINDER -> "Ожидание текста клиента";
            case REVIEW_CHECK_REMINDER -> "Проверка отзывов";
            case REVIEW_CHECK_DELIVERY_RETRY -> "Повтор проверки отзывов";
            case REVIEW_CHECK_AUTO_ARCHIVE -> "Автоархив проверки";
            case PAYMENT_INVOICE_RETRY -> "Повтор счета";
            case PAYMENT_REMINDER -> "Оплата";
            case PAYMENT_OVERDUE_ESCALATION -> "Просрочка оплаты";
            case ARCHIVE_REORDER_OFFER -> "Архивные компании";
            case BAD_REVIEW_INVOICE -> "Счет после плохого отзыва";
            case BAD_REVIEW_AUTO_BAN -> "Автобан после плохих";
            case REVIEW_RECOVERY_NOTICE -> "Все отзывы восстановлены";
        };
    }

    private String attemptStatusLabel(ScheduledMessageAttemptStatus status) {
        return switch (status) {
            case SENT -> "Отправлено";
            case FAILED -> "Ошибка";
            case SKIPPED -> "Пропущено";
        };
    }

    private String orderStatusTitle(Order order) {
        return order == null || order.getStatus() == null || !hasText(order.getStatus().getTitle())
                ? ""
                : order.getStatus().getTitle();
    }

    private String companyStatusTitle(Company company) {
        return company == null || company.getStatus() == null || !hasText(company.getStatus().getTitle())
                ? ""
                : company.getStatus().getTitle();
    }

    private String filialTitle(Order order) {
        return order == null || order.getFilial() == null || !hasText(order.getFilial().getTitle())
                ? ""
                : order.getFilial().getTitle();
    }

    private Map<ClientMessageScenario, Long> scenarioCountMap(List<ScheduledClientMessageStateRepository.ScenarioCount> counts) {
        Map<ClientMessageScenario, Long> result = new EnumMap<>(ClientMessageScenario.class);
        counts.forEach((count) -> result.put(count.getScenario(), count.getTotal()));
        return result;
    }

    private Map<ClientMessageScenario, Long> nativeScenarioCountMap(List<ScheduledClientMessageStateRepository.NativeScenarioCount> counts) {
        Map<ClientMessageScenario, Long> result = new EnumMap<>(ClientMessageScenario.class);
        counts.forEach((count) -> {
            try {
                result.put(ClientMessageScenario.valueOf(count.getScenario()), count.getTotal());
            } catch (RuntimeException ignored) {
                // Ignore stale or unknown scenario values from older rows.
            }
        });
        return result;
    }

    private Map<ClientMessageScenario, Long> attemptCountMap(List<ScheduledClientMessageAttemptRepository.ScenarioCount> counts) {
        Map<ClientMessageScenario, Long> result = new EnumMap<>(ClientMessageScenario.class);
        counts.forEach((count) -> result.put(count.getScenario(), count.getTotal()));
        return result;
    }

    private long readyToSendNow(
            Map<ClientMessageScenario, Long> dueByScenario,
            Map<ClientMessageScenario, Long> dueMissingChannelByScenario,
            boolean workerEnabled,
            boolean liveEnabled,
            boolean windowAllowed,
            boolean paused
    ) {
        return Arrays.stream(ClientMessageScenario.values())
                .mapToLong(scenario -> readyToSendNow(
                        scenario,
                        dueByScenario.getOrDefault(scenario, 0L),
                        dueMissingChannelByScenario.getOrDefault(scenario, 0L),
                        workerEnabled,
                        liveEnabled,
                        windowAllowed,
                        paused
                ))
                .sum();
    }

    private long readyToSendNow(
            ClientMessageScenario scenario,
            long dueNow,
            long dueMissingChannel,
            boolean workerEnabled,
            boolean liveEnabled,
            boolean windowAllowed,
            boolean paused
    ) {
        if (!workerEnabled || paused || dueNow <= 0) {
            return 0;
        }
        if (requiresClientMessageSlot(scenario) && !windowAllowed) {
            return 0;
        }
        if (requiresClientChat(scenario) && !liveEnabled) {
            return 0;
        }
        long blockedByChannel = requiresClientChat(scenario) ? dueMissingChannel : 0;
        return Math.max(0, dueNow - blockedByChannel);
    }

    private long waitingForWindow(
            Map<ClientMessageScenario, Long> dueByScenario,
            boolean workerEnabled,
            boolean windowAllowed,
            boolean paused
    ) {
        if (!workerEnabled || paused || windowAllowed) {
            return 0;
        }
        return Arrays.stream(ClientMessageScenario.values())
                .filter(this::requiresClientMessageSlot)
                .mapToLong(scenario -> dueByScenario.getOrDefault(scenario, 0L))
                .sum();
    }

    private long waitingForWindow(
            ClientMessageScenario scenario,
            long dueNow,
            boolean workerEnabled,
            boolean windowAllowed,
            boolean paused
    ) {
        return workerEnabled && !paused && !windowAllowed && requiresClientMessageSlot(scenario) ? dueNow : 0;
    }

    private boolean isResolvedConfigurationError(ScheduledClientMessageAttempt attempt, boolean liveEnabled) {
        if (attempt == null || !liveEnabled) {
            return false;
        }
        String code = attempt.getErrorCode();
        return "client_messages_live_disabled".equals(code);
    }

    private QueueReadiness queueReadiness(
            ScheduledClientMessageState state,
            Company company,
            LocalDateTime nowStorage,
            boolean workerEnabled,
            boolean liveEnabled,
            boolean windowAllowed,
            boolean paused
    ) {
        if (!workerEnabled) {
            return new QueueReadiness("WORKER_DISABLED", "Автоответчик выключен", "Фоновый worker выключен настройкой");
        }
        if (paused) {
            return new QueueReadiness("PAUSED", "Пауза", "Автоответчик временно на паузе");
        }
        if (state.getLockedUntil() != null && state.getLockedUntil().isAfter(nowStorage)) {
            return new QueueReadiness("LOCKED", "В обработке", "Кандидат уже взят worker-ом");
        }
        if (state.getNextAttemptAt() == null || state.getNextAttemptAt().isAfter(nowStorage)) {
            return new QueueReadiness("SCHEDULED", "Ждет расписания", "Время следующей попытки еще не наступило");
        }
        if (requiresClientChat(state.getScenario()) && !liveEnabled) {
            return new QueueReadiness("DRY_RUN", "Dry-run", "Реальная отправка фоновых сообщений выключена");
        }
        if (requiresClientChat(state.getScenario()) && missingChannelBinding(company)) {
            return new QueueReadiness("MISSING_CHANNEL", "Нет chatId", missingChannelReason(company));
        }
        if (requiresClientMessageSlot(state.getScenario()) && !windowAllowed) {
            return new QueueReadiness("WAITING_WINDOW", "Ждет окно", "Отправка начнется в ближайшее рабочее окно");
        }
        return new QueueReadiness(
                requiresClientChat(state.getScenario()) ? "READY_TO_SEND" : "READY_TO_RUN",
                requiresClientChat(state.getScenario()) ? "Готово к отправке" : "Готово к действию",
                requiresClientChat(state.getScenario()) ? "Канал привязан, окно открыто" : "Системное действие готово"
        );
    }

    private boolean requiresClientMessageSlot(ClientMessageScenario scenario) {
        return scenario != ClientMessageScenario.REVIEW_CHECK_AUTO_ARCHIVE
                && scenario != ClientMessageScenario.BAD_REVIEW_AUTO_BAN;
    }

    private boolean requiresClientChat(ClientMessageScenario scenario) {
        return scenario == ClientMessageScenario.CLIENT_TEXT_REMINDER
                || scenario == ClientMessageScenario.REVIEW_CHECK_REMINDER
                || scenario == ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY
                || scenario == ClientMessageScenario.PAYMENT_INVOICE_RETRY
                || scenario == ClientMessageScenario.PAYMENT_REMINDER
                || scenario == ClientMessageScenario.ARCHIVE_REORDER_OFFER
                || scenario == ClientMessageScenario.BAD_REVIEW_INVOICE
                || scenario == ClientMessageScenario.REVIEW_RECOVERY_NOTICE;
    }

    private boolean missingChannelBinding(Company company) {
        String channel = expectedChannel(company);
        return switch (channel) {
            case "WhatsApp" -> company == null || !hasText(company.getGroupId());
            case "Telegram" -> company == null || company.getTelegramGroupChatId() == null;
            case "MAX" -> company == null || company.getMaxGroupChatId() == null;
            default -> true;
        };
    }

    private String missingChannelReason(Company company) {
        String channel = expectedChannel(company);
        return switch (channel) {
            case "WhatsApp" -> "Для WhatsApp-группы не задан groupId";
            case "Telegram" -> "Для Telegram-группы не задан chatId";
            case "MAX" -> "Для MAX-группы не задан chatId";
            default -> "Ссылка на чат не указана или не распознана";
        };
    }

    private String expectedChannel(Company company) {
        if (company == null || !hasText(company.getUrlChat())) {
            return "ANY";
        }
        String normalized = company.getUrlChat().trim().toLowerCase();
        if (normalized.matches("^(?:https?://)?chat\\.whatsapp\\.com/.+")) {
            return "WhatsApp";
        }
        if (normalized.matches("^(?:https?://)?(?:t\\.me|telegram\\.me|telegram\\.dog)/.+")
                || normalized.startsWith("tg://resolve?")) {
            return "Telegram";
        }
        if (normalized.matches("^(?:https?://)?(?:web\\.)?max\\.ru/.+")) {
            return "MAX";
        }
        return "ANY";
    }

    private ClientMessageMonitorResponse.ArchiveDiagnostics archiveDiagnostics(LocalDateTime nowStorage) {
        String archiveCompanyStatus = appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_ARCHIVE_COMPANY_STATUS,
                ScheduledClientMessageService.DEFAULT_ARCHIVE_COMPANY_STATUS
        );
        ArchiveCompanyCandidateDiagnostics diagnostics = archiveCandidateRepository.diagnostics(
                nowStorage.minusMonths(appSettingService.getInt(
                        AppSettingService.CLIENT_MESSAGES_ARCHIVE_REORDER_MONTHS,
                        ScheduledClientMessageService.DEFAULT_ARCHIVE_REORDER_MONTHS
                )),
                archiveCompanyStatus,
                listSetting(
                        AppSettingService.CLIENT_MESSAGES_ARCHIVE_INACTIVE_ORDER_STATUSES,
                        ScheduledClientMessageService.DEFAULT_ARCHIVE_INACTIVE_ORDER_STATUSES
                ),
                listSetting(
                        AppSettingService.CLIENT_MESSAGES_OPEN_NEXT_ORDER_REQUEST_STATUSES,
                        ScheduledClientMessageService.DEFAULT_OPEN_NEXT_ORDER_REQUEST_STATUSES
                )
        );
        return new ClientMessageMonitorResponse.ArchiveDiagnostics(
                diagnostics.status(),
                diagnostics.totalInStatus(),
                diagnostics.ready(),
                diagnostics.tooFresh(),
                diagnostics.withoutChat(),
                diagnostics.blockedByActiveOrder(),
                diagnostics.blockedByOpenRequest()
        );
    }

    private String businessWindows() {
        String windows = appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_BUSINESS_WINDOWS,
                ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC
        );
        return ClientMessageSlotPlanner.isValidWindowsSpec(windows)
                ? windows
                : ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC;
    }

    private LocalDateTime nowIrkutsk() {
        return ZonedDateTime.now(clock)
                .withZoneSameInstant(ClientMessageSlotPlanner.IRKUTSK_ZONE)
                .toLocalDateTime()
                .withNano(0);
    }

    private LocalDateTime toIrkutskTime(LocalDateTime storageTime) {
        if (storageTime == null) {
            return null;
        }
        ZoneId storageZone = clock.getZone();
        return storageTime.atZone(storageZone)
                .withZoneSameInstant(ClientMessageSlotPlanner.IRKUTSK_ZONE)
                .toLocalDateTime()
                .withNano(0);
    }

    private LocalDateTime toStorageTime(LocalDateTime irkutskTime) {
        ZoneId storageZone = clock.getZone();
        return irkutskTime.atZone(ClientMessageSlotPlanner.IRKUTSK_ZONE)
                .withZoneSameInstant(storageZone)
                .toLocalDateTime();
    }

    private String firstText(String primary, String fallback) {
        return hasText(primary) ? primary : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String nullSafe(Long value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private LocalDateTime clientMessagesPausedUntil() {
        return parseLocalDateTime(appSettingService.getString(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL, null));
    }

    private LocalDateTime parseLocalDateTime(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private List<String> listSetting(String key, String fallbackCsv) {
        List<String> values = splitCsv(appSettingService.getString(key, fallbackCsv));
        return values.isEmpty() ? splitCsv(fallbackCsv) : values;
    }

    private List<String> splitCsv(String value) {
        if (!hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .distinct()
                .toList();
    }

    private record Resolution(
            Map<Long, Order> orders,
            Map<Long, Company> companies
    ) {
    }

    private record TargetInfo(
            Long companyId,
            String companyTitle,
            String orderTitle,
            String statusTitle,
            String link,
            Order order,
            Company company
    ) {
    }

    private record QueueReadiness(
            String code,
            String label,
            String reason
    ) {
    }
}

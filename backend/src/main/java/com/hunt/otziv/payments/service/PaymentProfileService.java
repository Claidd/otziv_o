package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.ManagerManualPaymentSettingsResponse;
import com.hunt.otziv.payments.dto.ManagerPaymentProfileAssignmentRequest;
import com.hunt.otziv.payments.dto.ManagerPaymentProfileResponse;
import com.hunt.otziv.payments.dto.PaymentProfilePolicyRequest;
import com.hunt.otziv.payments.dto.PaymentProfileResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.dto.TbankPaymentProfilesResponse;
import com.hunt.otziv.payments.dto.UpdateManagerManualPaymentSettingsRequest;
import com.hunt.otziv.payments.dto.UpdateManagerPaymentProfilesRequest;
import com.hunt.otziv.payments.dto.UpdatePaymentProfilePoliciesRequest;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentPolicy;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.model.TbankRuntimeMode;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.repository.PaymentProfileRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PaymentProfileService {

    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");
    private static final Set<PaymentLinkStatus> MANUAL_USAGE_STATUSES = Set.of(
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED,
            PaymentLinkStatus.CONFIRMED
    );
    private static final Set<PaymentLinkStatus> MANUAL_CONFIRMED_STATUSES = Set.of(
            PaymentLinkStatus.CONFIRMED
    );
    private static final Set<PaymentLinkStatus> MANUAL_PENDING_STATUSES = Set.of(
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED
    );
    private static final Set<PaymentMethod> MANUAL_PAYMENT_METHODS = Set.of(
            PaymentMethod.MANUAL_MOBILE_BANK,
            PaymentMethod.MANUAL_EXTERNAL_LINK
    );

    private final PaymentProfileRepository paymentProfileRepository;
    private final PaymentLinkRepository paymentLinkRepository;
    private final ManagerRepository managerRepository;
    private final TbankPaymentProperties properties;
    private final TbankRuntimeSettingsService runtimeSettingsService;

    @Transactional(readOnly = true)
    public TbankPaymentProfilesResponse managementState() {
        return new TbankPaymentProfilesResponse(profileResponses(), managerResponses());
    }

    @Transactional(readOnly = true)
    public ManagerManualPaymentSettingsResponse managerManualPaymentSettings(Long userId) {
        Manager manager = managerByUserId(userId);
        return managerManualPaymentSettings(manager, selectForManager(manager));
    }

    @Transactional
    public ManagerManualPaymentSettingsResponse updateManagerManualPaymentSettings(
            Long userId,
            UpdateManagerManualPaymentSettingsRequest request
    ) {
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Реквизиты общего платежного профиля меняет админ или владелец. Менеджер может создавать ручные задания."
        );
    }

    @Transactional
    public TbankPaymentProfilesResponse updateManagerAssignments(UpdateManagerPaymentProfilesRequest request) {
        List<ManagerPaymentProfileAssignmentRequest> assignments = request == null || request.assignments() == null
                ? List.of()
                : request.assignments();

        Map<Long, PaymentProfile> profiles = new HashMap<>();
        for (PaymentProfile profile : paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc()) {
            profiles.put(profile.getId(), profile);
        }

        for (ManagerPaymentProfileAssignmentRequest assignment : assignments) {
            if (assignment == null || assignment.managerId() == null) {
                continue;
            }
            Manager manager = managerRepository.findById(assignment.managerId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер не найден"));
            PaymentProfile profile = null;
            if (assignment.paymentProfileId() != null) {
                profile = Optional.ofNullable(profiles.get(assignment.paymentProfileId()))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежный профиль не найден"));
            }
            manager.setPaymentProfile(profile);
            managerRepository.save(manager);
        }

        return managementState();
    }

    @Transactional
    public TbankPaymentProfilesResponse updateProfilePolicies(UpdatePaymentProfilePoliciesRequest request) {
        List<PaymentProfilePolicyRequest> updates = request == null || request.profiles() == null
                ? List.of()
                : request.profiles();
        Map<Long, PaymentProfile> profiles = new HashMap<>();
        for (PaymentProfile profile : paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc()) {
            profiles.put(profile.getId(), profile);
        }

        for (PaymentProfilePolicyRequest update : updates) {
            if (update == null || update.profileId() == null) {
                continue;
            }
            PaymentProfile profile = Optional.ofNullable(profiles.get(update.profileId()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежный профиль не найден"));
            PaymentPolicy policy = parsePolicy(update.paymentPolicy());
            profile.setPaymentPolicy(policy);
            profile.setManualPaymentType(parseManualPaymentType(update.manualPaymentType()));
            profile.setManualPhone(limit(update.manualPhone(), 32));
            profile.setManualRecipientName(recipientOrDefault(update.manualRecipientName()));
            applyManualPaymentUrlUpdate(profile, update);
            profile.setManualPaymentButtonLabel(buttonLabelOrDefault(update.manualPaymentButtonLabel()));
            profile.setManualComment(limit(update.manualComment(), 255));
            profile.setManualMonthlySoftLimitKopecks(nonNegative(update.manualMonthlySoftLimitKopecks()));
            profile.setManualMonthlyHardLimitKopecks(nonNegative(update.manualMonthlyHardLimitKopecks()));
            validateManualPolicy(profile);
            paymentProfileRepository.save(profile);
        }

        return managementState();
    }

    @Transactional(readOnly = true)
    public PaymentProfile selectForManager(Manager manager) {
        if (manager != null && manager.getPaymentProfile() != null) {
            return manager.getPaymentProfile();
        }
        return defaultEntityProfile();
    }

    @Transactional(readOnly = true)
    public PaymentProfile defaultEntityProfile() {
        return paymentProfileRepository.findFirstByDefaultProfileTrueOrderByIdAsc()
                .or(() -> paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc().stream().findFirst())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Не настроены платежные профили"));
    }

    @Transactional
    public PaymentProfile lockForRouting(PaymentProfile profile) {
        if (profile == null || profile.getId() == null) {
            return profile;
        }
        return paymentProfileRepository.findByIdForUpdate(profile.getId()).orElse(profile);
    }

    @Transactional
    public PaymentProfile lockByIdForRouting(Long profileId) {
        if (profileId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У платежного маршрута не указан профиль");
        }
        return paymentProfileRepository.findByIdForUpdate(profileId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Платежный профиль общего счета больше не существует"
                ));
    }

    @Transactional(readOnly = true)
    public Optional<PaymentProfile> findByCode(String code) {
        String clean = normalize(code);
        return clean.isBlank() ? Optional.empty() : paymentProfileRepository.findByCode(clean);
    }

    public String provider(PaymentProfile profile) {
        try {
            return profile == null
                    ? PaymentProfile.PROVIDER_TBANK
                    : profile.normalizedProvider();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Платежный профиль содержит неподдерживаемого провайдера",
                    exception
            );
        }
    }

    public boolean isTbankProvider(PaymentProfile profile) {
        return PaymentProfile.PROVIDER_TBANK.equals(provider(profile));
    }

    public boolean isTochkaProvider(PaymentProfile profile) {
        return PaymentProfile.PROVIDER_TOCHKA.equals(provider(profile));
    }

    @Transactional(readOnly = true)
    public Optional<PaymentProfile> findByTerminalKey(String terminalKey) {
        String clean = normalize(terminalKey);
        if (clean.isBlank()) {
            return Optional.empty();
        }
        Optional<PaymentProfile> byStoredTerminal = paymentProfileRepository.findByTerminalKey(clean)
                .filter(this::isTbankProvider);
        if (byStoredTerminal.isPresent()) {
            return byStoredTerminal;
        }
        return paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc().stream()
                .filter(this::isTbankProvider)
                .filter(profile -> properties.matchesAnyTerminal(profile, clean))
                .findFirst();
    }

    @Transactional(readOnly = true)
    public Map<String, PaymentProfile> findByTerminalKeys(Collection<String> terminalKeys) {
        Set<String> cleanKeys = new java.util.LinkedHashSet<>();
        for (String terminalKey : terminalKeys == null ? List.<String>of() : terminalKeys) {
            String clean = normalize(terminalKey);
            if (!clean.isBlank()) {
                cleanKeys.add(clean);
            }
        }
        if (cleanKeys.isEmpty()) {
            return Map.of();
        }

        List<PaymentProfile> profiles = paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc();
        Map<String, PaymentProfile> resolved = new HashMap<>();
        for (PaymentProfile profile : profiles) {
            if (!isTbankProvider(profile)) {
                continue;
            }
            String storedTerminal = normalize(profile == null ? null : profile.getTerminalKey());
            if (cleanKeys.contains(storedTerminal)) {
                resolved.putIfAbsent(storedTerminal, profile);
            }
        }
        for (String terminalKey : cleanKeys) {
            if (resolved.containsKey(terminalKey)) {
                continue;
            }
            profiles.stream()
                    .filter(this::isTbankProvider)
                    .filter(profile -> properties.matchesAnyTerminal(profile, terminalKey))
                    .findFirst()
                    .ifPresent(profile -> resolved.put(terminalKey, profile));
        }
        return Map.copyOf(resolved);
    }

    public TbankPaymentProfile toRuntime(PaymentProfile profile) {
        return toRuntime(profile, runtimeSettingsService.runtimeMode());
    }

    public TbankPaymentProfile toRuntimeForTerminal(PaymentProfile profile, String terminalKey) {
        requireTbankProvider(profile);
        return properties.runtimeProfileForTerminal(profile, terminalKey);
    }

    public boolean isTestTerminal(String terminalKey) {
        return properties.isConfiguredTestTerminal(terminalKey);
    }

    private TbankPaymentProfile toRuntime(PaymentProfile profile, TbankRuntimeMode runtimeMode) {
        if (profile == null) {
            return properties.defaultProfile(runtimeMode);
        }
        requireTbankProvider(profile);
        String terminalKey = properties.terminalKeyFor(profile, runtimeMode);
        return new TbankPaymentProfile(
                profile.getId(),
                profile.getCode(),
                profile.getName(),
                profile.isEnabled(),
                terminalKey,
                properties.passwordFor(profile, runtimeMode),
                runtimeMode.isTest() || profile.isTestMode() || properties.isTestMode(terminalKey)
        );
    }

    private void requireTbankProvider(PaymentProfile profile) {
        if (!isTbankProvider(profile)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Профиль Точки нельзя использовать для запроса в T-Bank"
            );
        }
    }

    private List<PaymentProfileResponse> profileResponses() {
        return paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc().stream()
                .map(this::profileResponse)
                .toList();
    }

    private List<ManagerPaymentProfileResponse> managerResponses() {
        return managerRepository.findAllForPaymentProfileAssignments().stream()
                .map(this::managerResponse)
                .toList();
    }

    private PaymentProfileResponse profileResponse(PaymentProfile profile) {
        String normalizedProvider = provider(profile);
        TbankPaymentProfile runtimeProfile = PaymentProfile.PROVIDER_TBANK.equals(normalizedProvider)
                ? toRuntime(profile)
                : null;
        String runtimeTerminalKey = runtimeProfile == null
                ? normalize(profile.getTerminalKey())
                : runtimeProfile.terminalKey();
        boolean runtimeTestMode = runtimeProfile == null
                ? profile.isTestMode()
                : runtimeProfile.testMode();
        boolean hasRuntimeCredentials = runtimeProfile != null && runtimeProfile.hasCredentials();
        LocalDateTime periodStart = currentMonthStart();
        LocalDateTime periodEnd = periodStart.plusMonths(1);
        long manualUsed = manualMonthlyUsed(profile, periodStart, periodEnd);
        long manualConfirmed = manualMonthlyConfirmed(profile, periodStart, periodEnd);
        long manualPendingAmount = manualMonthlyPendingAmount(profile, periodStart, periodEnd);
        long manualPending = manualMonthlyPendingCount(profile, periodStart, periodEnd);
        return new PaymentProfileResponse(
                profile.getId(),
                profile.getCode(),
                normalizedProvider,
                profile.getName(),
                runtimeTerminalKey,
                profile.getPasswordEnvKey(),
                profile.isEnabled(),
                profile.isDefaultProfile(),
                runtimeTestMode,
                hasRuntimeCredentials,
                paymentPolicy(profile).name(),
                manualPaymentType(profile).name(),
                normalize(profile.getManualPhone()),
                recipientOrDefault(profile.getManualRecipientName()),
                paymentUrlForRead(profile.getManualPaymentUrl()),
                manualPaymentUrlConfigured(profile.getManualPaymentUrl()),
                buttonLabelOrDefault(profile.getManualPaymentButtonLabel()),
                limit(profile.getManualComment(), 255),
                manualLimitOrDefault(profile.getManualMonthlySoftLimitKopecks()),
                manualLimitOrDefault(profile.getManualMonthlyHardLimitKopecks()),
                manualUsed,
                manualConfirmed,
                manualPendingAmount,
                manualPending
        );
    }

    private ManagerPaymentProfileResponse managerResponse(Manager manager) {
        User user = manager.getUser();
        PaymentProfile profile = manager.getPaymentProfile();
        return new ManagerPaymentProfileResponse(
                manager.getId(),
                user == null ? "Менеджер #" + manager.getId() : fallback(user.getFio(), user.getUsername()),
                user == null ? "" : normalize(user.getUsername()),
                profile == null ? null : profile.getId(),
                profile == null ? "" : profile.getName()
        );
    }

    private ManagerManualPaymentSettingsResponse managerManualPaymentSettings(Manager manager, PaymentProfile profile) {
        return new ManagerManualPaymentSettingsResponse(
                profile.getId(),
                profile.getName(),
                paymentPolicy(profile).name(),
                paymentPolicy(profile) == PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK,
                manualPaymentType(profile).name(),
                normalize(profile.getManualPhone()),
                recipientOrDefault(profile.getManualRecipientName()),
                paymentUrlForRead(profile.getManualPaymentUrl()),
                buttonLabelOrDefault(profile.getManualPaymentButtonLabel())
        );
    }

    private Manager managerByUserId(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не найден");
        }
        return managerRepository.findByUserIdWithPaymentProfile(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Профиль менеджера не найден"));
    }

    private String fallback(String value, String fallback) {
        String clean = normalize(value).toUpperCase(Locale.ROOT);
        return clean.isBlank() ? normalize(fallback) : clean;
    }

    private long manualMonthlyUsed(PaymentProfile profile, LocalDateTime periodStart, LocalDateTime periodEnd) {
        return manualMonthlyAmount(profile, MANUAL_USAGE_STATUSES, periodStart, periodEnd);
    }

    private long manualMonthlyConfirmed(PaymentProfile profile, LocalDateTime periodStart, LocalDateTime periodEnd) {
        return manualMonthlyAmount(profile, MANUAL_CONFIRMED_STATUSES, periodStart, periodEnd);
    }

    private long manualMonthlyPendingAmount(PaymentProfile profile, LocalDateTime periodStart, LocalDateTime periodEnd) {
        return manualMonthlyAmount(profile, MANUAL_PENDING_STATUSES, periodStart, periodEnd);
    }

    private long manualMonthlyAmount(
            PaymentProfile profile,
            Set<PaymentLinkStatus> statuses,
            LocalDateTime periodStart,
            LocalDateTime periodEnd
    ) {
        if (profile.getId() == null) {
            return 0;
        }
        return paymentLinkRepository.sumManualReservedAndConfirmedForPeriod(
                profile.getId(),
                MANUAL_PAYMENT_METHODS,
                statuses,
                periodStart,
                periodEnd,
                LocalDateTime.now(MOSCOW_ZONE),
                PaymentLinkStatus.CONFIRMED,
                null
        );
    }

    private long manualMonthlyPendingCount(PaymentProfile profile, LocalDateTime periodStart, LocalDateTime periodEnd) {
        if (profile.getId() == null) {
            return 0;
        }
        return paymentLinkRepository.countManualReservedAndConfirmedForPeriod(
                profile.getId(),
                MANUAL_PAYMENT_METHODS,
                MANUAL_PENDING_STATUSES,
                periodStart,
                periodEnd,
                LocalDateTime.now(MOSCOW_ZONE)
        );
    }

    private PaymentPolicy paymentPolicy(PaymentProfile profile) {
        return profile.getPaymentPolicy() == null ? PaymentPolicy.T_BANK_ONLY : profile.getPaymentPolicy();
    }

    private ManualPaymentType manualPaymentType(PaymentProfile profile) {
        return profile.getManualPaymentType() == null ? ManualPaymentType.MOBILE_BANK : profile.getManualPaymentType();
    }

    private PaymentPolicy parsePolicy(String value) {
        String clean = normalize(value).toUpperCase(Locale.ROOT);
        if (clean.isBlank()) {
            return PaymentPolicy.T_BANK_ONLY;
        }
        try {
            return PaymentPolicy.valueOf(clean);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная платежная политика", e);
        }
    }

    private void validateManualPolicy(PaymentProfile profile) {
        if (paymentPolicy(profile) != PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK) {
            return;
        }
        if (manualPaymentType(profile) == ManualPaymentType.MOBILE_BANK) {
            if (normalize(profile.getManualPhone()).isBlank() || normalize(profile.getManualRecipientName()).isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для ручной оплаты укажите телефон и получателя");
            }
        } else if (paymentUrlForRead(profile.getManualPaymentUrl()).isBlank()
                && !PaymentUrlPolicy.isUnsafeConfigured(
                        profile.getManualPaymentUrl(),
                        PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL
                )) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для ручной оплаты укажите ссылку Альфа-Банка");
        }
        long hardLimit = limitValue(profile.getManualMonthlyHardLimitKopecks(), profile.getManualMonthlySoftLimitKopecks());
        if (hardLimit <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для ручной оплаты укажите месячный лимит");
        }
        Long softLimit = profile.getManualMonthlySoftLimitKopecks();
        Long hardLimitConfigured = profile.getManualMonthlyHardLimitKopecks();
        if (softLimit != null && hardLimitConfigured != null && softLimit > hardLimitConfigured) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Мягкий лимит не может быть больше жесткого");
        }
    }

    private long limitValue(Long hardLimit, Long softLimit) {
        if (hardLimit != null && hardLimit > 0) {
            return hardLimit;
        }
        return softLimit == null ? 0 : softLimit;
    }

    private Long nonNegative(Long value) {
        if (value == null) {
            return PaymentProfile.DEFAULT_MANUAL_MONTHLY_LIMIT_KOPECKS;
        }
        return Math.max(0, value);
    }

    private Long manualLimitOrDefault(Long value) {
        return value == null || value <= 0 ? PaymentProfile.DEFAULT_MANUAL_MONTHLY_LIMIT_KOPECKS : value;
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
        return PaymentUrlPolicy.requireOrDefault(
                value,
                ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL,
                PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL,
                HttpStatus.BAD_REQUEST,
                "Ссылка ручной оплаты должна использовать http или https"
        );
    }

    private void applyManualPaymentUrlUpdate(PaymentProfile profile, PaymentProfilePolicyRequest update) {
        String persisted = profile.getManualPaymentUrl();
        boolean quarantined = PaymentUrlPolicy.isUnsafeConfigured(
                persisted,
                PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL
        );
        boolean replacementConfirmed = Boolean.TRUE.equals(update.manualPaymentUrlReplacementConfirmed());
        if (quarantined && !replacementConfirmed) {
            // Mixed-version clients used to post the default URL shown by their
            // UI. Preserve the quarantined raw value until a current client
            // explicitly confirms a replacement.
            return;
        }
        if (replacementConfirmed) {
            profile.setManualPaymentUrl(PaymentUrlPolicy.require(
                    update.manualPaymentUrl(),
                    PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL,
                    HttpStatus.BAD_REQUEST,
                    "Укажите безопасную ссылку ручной оплаты для замены"
            ));
            return;
        }
        if (update.manualPaymentUrl() != null) {
            profile.setManualPaymentUrl(paymentUrlOrDefault(update.manualPaymentUrl()));
        }
    }

    private String paymentUrlForRead(String value) {
        return PaymentUrlPolicy.safeOrDefault(
                value,
                ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL,
                PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL
        );
    }

    private boolean manualPaymentUrlConfigured(String value) {
        return !paymentUrlForRead(value).isBlank();
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

    private String limit(String value, int maxLength) {
        String clean = normalize(value);
        if (clean.length() <= maxLength) {
            return clean;
        }
        return clean.substring(0, maxLength);
    }

    private LocalDateTime currentMonthStart() {
        LocalDate today = LocalDate.now(MOSCOW_ZONE);
        return today.withDayOfMonth(1).atStartOfDay();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

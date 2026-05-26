package com.hunt.otziv.payments;

import com.hunt.otziv.payments.dto.ManagerPaymentProfileAssignmentRequest;
import com.hunt.otziv.payments.dto.ManagerPaymentProfileResponse;
import com.hunt.otziv.payments.dto.PaymentProfileResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfilesResponse;
import com.hunt.otziv.payments.dto.UpdateManagerPaymentProfilesRequest;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentProfileService {

    private final PaymentProfileRepository paymentProfileRepository;
    private final ManagerRepository managerRepository;
    private final TbankPaymentProperties properties;
    private final TbankRuntimeSettingsService runtimeSettingsService;

    @Transactional(readOnly = true)
    public TbankPaymentProfilesResponse managementState() {
        return new TbankPaymentProfilesResponse(profileResponses(), managerResponses());
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

    @Transactional(readOnly = true)
    public Optional<PaymentProfile> findByCode(String code) {
        String clean = normalize(code);
        return clean.isBlank() ? Optional.empty() : paymentProfileRepository.findByCode(clean);
    }

    @Transactional(readOnly = true)
    public Optional<PaymentProfile> findByTerminalKey(String terminalKey) {
        String clean = normalize(terminalKey);
        if (clean.isBlank()) {
            return Optional.empty();
        }
        Optional<PaymentProfile> byStoredTerminal = paymentProfileRepository.findByTerminalKey(clean);
        if (byStoredTerminal.isPresent()) {
            return byStoredTerminal;
        }
        return paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc().stream()
                .filter(profile -> properties.matchesAnyTerminal(profile, clean))
                .findFirst();
    }

    public TbankPaymentProfile toRuntime(PaymentProfile profile) {
        return toRuntime(profile, runtimeSettingsService.runtimeMode());
    }

    public TbankPaymentProfile toRuntimeForTerminal(PaymentProfile profile, String terminalKey) {
        return properties.runtimeProfileForTerminal(profile, terminalKey);
    }

    public boolean isTestTerminal(String terminalKey) {
        return properties.isConfiguredTestTerminal(terminalKey);
    }

    private TbankPaymentProfile toRuntime(PaymentProfile profile, TbankRuntimeMode runtimeMode) {
        if (profile == null) {
            return properties.defaultProfile(runtimeMode);
        }
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
        TbankPaymentProfile runtimeProfile = toRuntime(profile);
        return new PaymentProfileResponse(
                profile.getId(),
                profile.getCode(),
                profile.getProvider(),
                profile.getName(),
                runtimeProfile.terminalKey(),
                profile.getPasswordEnvKey(),
                profile.isEnabled(),
                profile.isDefaultProfile(),
                runtimeProfile.testMode(),
                runtimeProfile.hasCredentials()
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

    private String fallback(String value, String fallback) {
        String clean = normalize(value);
        return clean.isBlank() ? normalize(fallback) : clean;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

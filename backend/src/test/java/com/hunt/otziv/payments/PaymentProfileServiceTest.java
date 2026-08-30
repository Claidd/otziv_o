package com.hunt.otziv.payments;

import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.PaymentProfilePolicyRequest;
import com.hunt.otziv.payments.dto.ManagerPaymentProfileAssignmentRequest;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.dto.UpdateManagerManualPaymentSettingsRequest;
import com.hunt.otziv.payments.dto.UpdateManagerPaymentProfilesRequest;
import com.hunt.otziv.payments.dto.UpdatePaymentProfilePoliciesRequest;
import com.hunt.otziv.payments.model.PaymentPolicy;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.model.TbankRuntimeMode;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.repository.PaymentProfileRepository;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentProfileServiceTest {

    @Mock
    private PaymentProfileRepository paymentProfileRepository;

    @Mock
    private PaymentLinkRepository paymentLinkRepository;

    @Mock
    private ManagerRepository managerRepository;

    @Mock
    private TbankRuntimeSettingsService runtimeSettingsService;

    @Mock
    private TochkaPaymentProfileResolver tochkaPaymentProfileResolver;

    @Test
    void managerCannotUpdateSharedManualPaymentRequisites() {
        TbankPaymentProperties properties = new TbankPaymentProperties();
        PaymentProfileService service = new PaymentProfileService(
                paymentProfileRepository,
                paymentLinkRepository,
                managerRepository,
                properties,
                runtimeSettingsService,
                tochkaPaymentProfileResolver
        );
        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.updateManagerManualPaymentSettings(
                        10L,
                        new UpdateManagerManualPaymentSettingsRequest(
                                "MOBILE_BANK",
                                " 79041256288 ",
                                " Мария Р ",
                                null,
                                null
                        )
                )
        );

        assertEquals(403, error.getStatusCode().value());
        verify(paymentProfileRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void legacyUnsafeManualUrlDoesNotBreakSettingsResponseOrChangeRecipient() {
        PaymentProfile profile = profile();
        profile.setManualPaymentType(ManualPaymentType.EXTERNAL_LINK);
        profile.setManualPaymentUrl("javascript:alert(document.cookie)");
        Manager manager = manager(profile);
        when(managerRepository.findByUserIdWithPaymentProfile(10L)).thenReturn(Optional.of(manager));
        PaymentProfileService service = new PaymentProfileService(
                paymentProfileRepository,
                paymentLinkRepository,
                managerRepository,
                new TbankPaymentProperties(),
                runtimeSettingsService,
                tochkaPaymentProfileResolver
        );

        var response = service.managerManualPaymentSettings(10L);

        assertEquals("", response.manualPaymentUrl());
        verify(paymentProfileRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void managementStateMarksOnlyUnsafeNonblankLegacyManualUrlAsUnconfigured() {
        PaymentProfile safe = profile();
        safe.setId(21L);
        safe.setManualPaymentUrl("https://pay.example/safe");
        PaymentProfile defaulted = profile();
        defaulted.setId(22L);
        defaulted.setManualPaymentUrl(null);
        PaymentProfile unsafe = profile();
        unsafe.setId(23L);
        unsafe.setManualPaymentUrl("javascript:alert(document.cookie)");
        when(paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc())
                .thenReturn(List.of(safe, defaulted, unsafe));
        when(managerRepository.findAllForPaymentProfileAssignments()).thenReturn(List.of());
        when(runtimeSettingsService.runtimeMode()).thenReturn(TbankRuntimeMode.LIVE);
        PaymentProfileService service = new PaymentProfileService(
                paymentProfileRepository,
                paymentLinkRepository,
                managerRepository,
                new TbankPaymentProperties(),
                runtimeSettingsService,
                tochkaPaymentProfileResolver
        );

        var profiles = service.managementState().profiles();

        assertTrue(profiles.get(0).manualPaymentUrlConfigured());
        assertEquals("https://pay.example/safe", profiles.get(0).manualPaymentUrl());
        assertTrue(profiles.get(1).manualPaymentUrlConfigured());
        assertEquals(ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL, profiles.get(1).manualPaymentUrl());
        assertFalse(profiles.get(2).manualPaymentUrlConfigured());
        assertEquals("", profiles.get(2).manualPaymentUrl());
    }

    @Test
    void legacyPolicyUpdateCannotReplaceQuarantinedValueWithDisplayedDefault() {
        PaymentProfile profile = profile();
        profile.setManualPaymentType(ManualPaymentType.EXTERNAL_LINK);
        profile.setManualPaymentUrl("javascript:legacy-recipient()");
        when(paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc())
                .thenReturn(List.of(profile));
        when(managerRepository.findAllForPaymentProfileAssignments()).thenReturn(List.of());
        when(runtimeSettingsService.runtimeMode()).thenReturn(TbankRuntimeMode.LIVE);
        PaymentProfileService service = new PaymentProfileService(
                paymentProfileRepository,
                paymentLinkRepository,
                managerRepository,
                new TbankPaymentProperties(),
                runtimeSettingsService,
                tochkaPaymentProfileResolver
        );
        PaymentProfilePolicyRequest update = new PaymentProfilePolicyRequest(
                profile.getId(),
                PaymentPolicy.T_BANK_ONLY.name(),
                ManualPaymentType.EXTERNAL_LINK.name(),
                "",
                "Получатель",
                ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL,
                "Оплатить",
                "",
                100_000L,
                200_000L
        );

        var response = service.updateProfilePolicies(new UpdatePaymentProfilePoliciesRequest(List.of(update)));

        assertEquals("javascript:legacy-recipient()", profile.getManualPaymentUrl());
        assertFalse(response.profiles().get(0).manualPaymentUrlConfigured());
        assertEquals("", response.profiles().get(0).manualPaymentUrl());
        verify(paymentProfileRepository).save(profile);
    }

    @Test
    void currentPolicyUpdateCanExplicitlyReplaceQuarantinedValue() {
        PaymentProfile profile = profile();
        profile.setManualPaymentType(ManualPaymentType.EXTERNAL_LINK);
        profile.setManualPaymentUrl("javascript:legacy-recipient()");
        when(paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc())
                .thenReturn(List.of(profile));
        when(managerRepository.findAllForPaymentProfileAssignments()).thenReturn(List.of());
        when(runtimeSettingsService.runtimeMode()).thenReturn(TbankRuntimeMode.LIVE);
        PaymentProfileService service = new PaymentProfileService(
                paymentProfileRepository,
                paymentLinkRepository,
                managerRepository,
                new TbankPaymentProperties(),
                runtimeSettingsService,
                tochkaPaymentProfileResolver
        );
        PaymentProfilePolicyRequest update = new PaymentProfilePolicyRequest(
                profile.getId(),
                PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK.name(),
                ManualPaymentType.EXTERNAL_LINK.name(),
                "",
                "Получатель",
                "https://pay.example/replacement",
                "Оплатить",
                "",
                100_000L,
                200_000L,
                true
        );

        var response = service.updateProfilePolicies(new UpdatePaymentProfilePoliciesRequest(List.of(update)));

        assertEquals("https://pay.example/replacement", profile.getManualPaymentUrl());
        assertTrue(response.profiles().get(0).manualPaymentUrlConfigured());
        assertEquals("https://pay.example/replacement", response.profiles().get(0).manualPaymentUrl());
    }

    @Test
    void terminalProfilesAreResolvedInOneBatchRead() {
        PaymentProfile first = profile();
        first.setId(21L);
        first.setTerminalKey("terminal-one");
        first.setName("Первый магазин");
        PaymentProfile second = profile();
        second.setId(22L);
        second.setTerminalKey("terminal-two");
        second.setName("Второй магазин");
        when(paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc())
                .thenReturn(List.of(first, second));
        PaymentProfileService service = new PaymentProfileService(
                paymentProfileRepository,
                paymentLinkRepository,
                managerRepository,
                new TbankPaymentProperties(),
                runtimeSettingsService,
                tochkaPaymentProfileResolver
        );

        var profiles = service.findByTerminalKeys(List.of(
                " terminal-two ",
                "terminal-one",
                "unknown-terminal"
        ));

        assertEquals(first, profiles.get("terminal-one"));
        assertEquals(second, profiles.get("terminal-two"));
        assertFalse(profiles.containsKey("unknown-terminal"));
        verify(paymentProfileRepository).findAllByOrderByDefaultProfileDescNameAsc();
        verify(paymentProfileRepository, never()).findByTerminalKey("terminal-one");
        verify(paymentProfileRepository, never()).findByTerminalKey("terminal-two");
    }

    @Test
    void tbankRuntimeConversionRejectsTochkaProfile() {
        PaymentProfile tochka = profile();
        tochka.setProvider(" tochka ");
        when(runtimeSettingsService.runtimeMode()).thenReturn(TbankRuntimeMode.LIVE);
        PaymentProfileService service = new PaymentProfileService(
                paymentProfileRepository,
                paymentLinkRepository,
                managerRepository,
                new TbankPaymentProperties(),
                runtimeSettingsService,
                tochkaPaymentProfileResolver
        );

        ResponseStatusException runtimeError = assertThrows(
                ResponseStatusException.class,
                () -> service.toRuntime(tochka)
        );
        ResponseStatusException terminalError = assertThrows(
                ResponseStatusException.class,
                () -> service.toRuntimeForTerminal(tochka, "merchant-id")
        );

        assertEquals(409, runtimeError.getStatusCode().value());
        assertEquals(409, terminalError.getStatusCode().value());
        assertEquals(PaymentProfile.PROVIDER_TOCHKA, service.provider(tochka));
        assertTrue(service.isTochkaProvider(tochka));
    }

    @Test
    void managementStateUsesExactTochkaRuntimeWithoutExposingProviderSecrets() {
        PaymentProfile tochka = profile();
        tochka.setCode("tochka-primary");
        tochka.setName("Точка Банк");
        tochka.setProvider(PaymentProfile.PROVIDER_TOCHKA);
        tochka.setTerminalKey("tochka-profile-placeholder");
        tochka.setPasswordEnvKey("jwt-must-not-leave-backend");
        when(paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc())
                .thenReturn(List.of(tochka));
        when(managerRepository.findAllForPaymentProfileAssignments()).thenReturn(List.of());
        TochkaPaymentProfile runtime = tochkaRuntime("merchant-provider-9876", "jwt-live-secret", true);
        when(tochkaPaymentProfileResolver.resolveForExistingPayment(tochka)).thenReturn(runtime);
        when(tochkaPaymentProfileResolver.resolve(tochka)).thenReturn(runtime);
        PaymentProfileService service = new PaymentProfileService(
                paymentProfileRepository,
                paymentLinkRepository,
                managerRepository,
                new TbankPaymentProperties(),
                runtimeSettingsService,
                tochkaPaymentProfileResolver
        );

        var response = service.managementState().profiles().getFirst();

        assertEquals(PaymentProfile.PROVIDER_TOCHKA, response.provider());
        assertEquals("Точка · ••••9876", response.terminalKey());
        assertTrue(response.testMode());
        assertTrue(response.hasPassword());
        assertTrue(response.operational());
        assertEquals(null, response.passwordEnvKey());
        assertFalse(response.toString().contains("merchant-provider-9876"));
        assertFalse(response.toString().contains("jwt-live-secret"));
        assertFalse(response.toString().contains("jwt-must-not-leave-backend"));
        verify(runtimeSettingsService, never()).runtimeMode();
    }

    @Test
    void managementStateFailsClosedWhenTochkaRuntimeCannotBeResolved() {
        PaymentProfile tochka = profile();
        tochka.setCode("tochka-primary");
        tochka.setProvider(PaymentProfile.PROVIDER_TOCHKA);
        tochka.setTerminalKey("tochka-profile-placeholder");
        tochka.setTestMode(false);
        when(paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc())
                .thenReturn(List.of(tochka));
        when(managerRepository.findAllForPaymentProfileAssignments()).thenReturn(List.of());
        when(tochkaPaymentProfileResolver.resolveForExistingPayment(tochka))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "mismatch"));
        PaymentProfileService service = new PaymentProfileService(
                paymentProfileRepository,
                paymentLinkRepository,
                managerRepository,
                new TbankPaymentProperties(),
                runtimeSettingsService,
                tochkaPaymentProfileResolver
        );

        var response = service.managementState().profiles().getFirst();

        assertEquals("Точка · tochka-primary", response.terminalKey());
        assertTrue(response.testMode());
        assertFalse(response.hasPassword());
        assertFalse(response.operational());
        verify(runtimeSettingsService, never()).runtimeMode();
    }

    @Test
    void managementStateDoesNotMarkConfiguredButDisabledTochkaProfileOperational() {
        PaymentProfile tochka = profile();
        tochka.setCode("tochka-primary");
        tochka.setProvider(PaymentProfile.PROVIDER_TOCHKA);
        TochkaPaymentProfile runtime = tochkaRuntime("merchant-provider-9876", "jwt-live-secret", false);
        when(paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc())
                .thenReturn(List.of(tochka));
        when(managerRepository.findAllForPaymentProfileAssignments()).thenReturn(List.of());
        when(tochkaPaymentProfileResolver.resolveForExistingPayment(tochka)).thenReturn(runtime);
        when(tochkaPaymentProfileResolver.resolve(tochka))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "disabled"));
        PaymentProfileService service = new PaymentProfileService(
                paymentProfileRepository,
                paymentLinkRepository,
                managerRepository,
                new TbankPaymentProperties(),
                runtimeSettingsService,
                tochkaPaymentProfileResolver
        );

        var response = service.managementState().profiles().getFirst();

        assertTrue(response.hasPassword());
        assertFalse(response.operational());
    }

    @Test
    void managerCanBeAssignedToTochkaProfile() {
        PaymentProfile tochka = profile();
        tochka.setCode("tochka-primary");
        tochka.setProvider(PaymentProfile.PROVIDER_TOCHKA);
        Manager manager = manager(null);
        TochkaPaymentProfile runtime = tochkaRuntime("merchant-provider-9876", "jwt-live-secret", false);
        when(paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc())
                .thenReturn(List.of(tochka));
        when(managerRepository.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(managerRepository.findAllForPaymentProfileAssignments()).thenReturn(List.of(manager));
        when(tochkaPaymentProfileResolver.resolve(tochka)).thenReturn(runtime);
        PaymentProfileService service = new PaymentProfileService(
                paymentProfileRepository,
                paymentLinkRepository,
                managerRepository,
                new TbankPaymentProperties(),
                runtimeSettingsService,
                tochkaPaymentProfileResolver
        );

        var response = service.updateManagerAssignments(new UpdateManagerPaymentProfilesRequest(List.of(
                new ManagerPaymentProfileAssignmentRequest(manager.getId(), tochka.getId())
        )));

        assertEquals(tochka, manager.getPaymentProfile());
        assertEquals(tochka.getId(), response.managers().getFirst().paymentProfileId());
        verify(managerRepository).save(manager);
    }

    @Test
    void managerCannotBeSwitchedToProfileThatIsNotOperational() {
        PaymentProfile tochka = profile();
        tochka.setCode("tochka-primary");
        tochka.setName("Точка Банк");
        tochka.setProvider(PaymentProfile.PROVIDER_TOCHKA);
        tochka.setEnabled(false);
        Manager manager = manager(null);
        when(paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc())
                .thenReturn(List.of(tochka));
        when(managerRepository.findById(manager.getId())).thenReturn(Optional.of(manager));
        PaymentProfileService service = new PaymentProfileService(
                paymentProfileRepository,
                paymentLinkRepository,
                managerRepository,
                new TbankPaymentProperties(),
                runtimeSettingsService,
                tochkaPaymentProfileResolver
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.updateManagerAssignments(new UpdateManagerPaymentProfilesRequest(List.of(
                        new ManagerPaymentProfileAssignmentRequest(manager.getId(), tochka.getId())
                )))
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("не готов к новым платежам"));
        verify(managerRepository, never()).save(manager);
    }

    @Test
    void paymentProfileCannotBeAssignedToHistoricalNonManagerIdentity() {
        PaymentProfile tochka = profile();
        tochka.setCode("tochka-primary");
        tochka.setProvider(PaymentProfile.PROVIDER_TOCHKA);
        Manager historical = manager(null);
        historical.getUser().getRoles().clear();
        Role client = new Role();
        client.setName("ROLE_CLIENT");
        historical.getUser().getRoles().add(client);
        when(paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc())
                .thenReturn(List.of(tochka));
        when(managerRepository.findById(historical.getId())).thenReturn(Optional.of(historical));
        PaymentProfileService service = new PaymentProfileService(
                paymentProfileRepository,
                paymentLinkRepository,
                managerRepository,
                new TbankPaymentProperties(),
                runtimeSettingsService,
                tochkaPaymentProfileResolver
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.updateManagerAssignments(new UpdateManagerPaymentProfilesRequest(List.of(
                        new ManagerPaymentProfileAssignmentRequest(historical.getId(), tochka.getId())
                )))
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(managerRepository, never()).save(historical);
    }

    @Test
    void tbankTerminalLookupDoesNotReturnTochkaProfile() {
        PaymentProfile tochka = profile();
        tochka.setProvider(PaymentProfile.PROVIDER_TOCHKA);
        tochka.setTerminalKey("merchant-id");
        when(paymentProfileRepository.findByTerminalKey("merchant-id")).thenReturn(Optional.of(tochka));
        PaymentProfileService service = new PaymentProfileService(
                paymentProfileRepository,
                paymentLinkRepository,
                managerRepository,
                new TbankPaymentProperties(),
                runtimeSettingsService,
                tochkaPaymentProfileResolver
        );

        assertTrue(service.findByTerminalKey("merchant-id").isEmpty());
    }

    private Manager manager(PaymentProfile profile) {
        User user = new User();
        user.setId(10L);
        user.setUsername("manager");
        user.setActive(true);
        Role managerRole = new Role();
        managerRole.setName("ROLE_MANAGER");
        user.setRoles(new java.util.ArrayList<>(List.of(managerRole)));

        Manager manager = new Manager();
        manager.setId(3L);
        manager.setUser(user);
        manager.setPaymentProfile(profile);
        return manager;
    }

    private TochkaPaymentProfile tochkaRuntime(String merchantId, String jwtToken, boolean testMode) {
        return new TochkaPaymentProfile(
                2L,
                "tochka-primary",
                "Точка Банк",
                true,
                "customer-code",
                merchantId,
                jwtToken,
                "",
                testMode,
                null,
                null,
                null,
                null,
                "",
                "",
                List.of(),
                Duration.ofDays(7)
        );
    }

    private PaymentProfile profile() {
        PaymentProfile profile = new PaymentProfile();
        profile.setId(2L);
        profile.setCode(TbankPaymentProfile.SECONDARY_CODE);
        profile.setProvider(PaymentProfile.PROVIDER_TBANK);
        profile.setName("Второй магазин");
        profile.setTerminalKey("terminal");
        profile.setEnabled(true);
        profile.setPaymentPolicy(PaymentPolicy.MANUAL_UNTIL_LIMIT_THEN_TBANK);
        profile.setManualMonthlyHardLimitKopecks(19100000L);
        return profile;
    }
}

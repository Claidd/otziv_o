package com.hunt.otziv.payments;

import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.PaymentProfilePolicyRequest;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.dto.UpdateManagerManualPaymentSettingsRequest;
import com.hunt.otziv.payments.dto.UpdatePaymentProfilePoliciesRequest;
import com.hunt.otziv.payments.model.PaymentPolicy;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.model.TbankRuntimeMode;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.repository.PaymentProfileRepository;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
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

    @Test
    void managerCannotUpdateSharedManualPaymentRequisites() {
        TbankPaymentProperties properties = new TbankPaymentProperties();
        PaymentProfileService service = new PaymentProfileService(
                paymentProfileRepository,
                paymentLinkRepository,
                managerRepository,
                properties,
                runtimeSettingsService
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
                runtimeSettingsService
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
                runtimeSettingsService
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
                runtimeSettingsService
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
                runtimeSettingsService
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
                runtimeSettingsService
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
                runtimeSettingsService
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
    void managementStateDoesNotTreatTochkaProfileAsTbankCredentials() {
        PaymentProfile tochka = profile();
        tochka.setProvider(PaymentProfile.PROVIDER_TOCHKA);
        tochka.setTerminalKey("tochka-profile-key");
        when(paymentProfileRepository.findAllByOrderByDefaultProfileDescNameAsc())
                .thenReturn(List.of(tochka));
        when(managerRepository.findAllForPaymentProfileAssignments()).thenReturn(List.of());
        PaymentProfileService service = new PaymentProfileService(
                paymentProfileRepository,
                paymentLinkRepository,
                managerRepository,
                new TbankPaymentProperties(),
                runtimeSettingsService
        );

        var response = service.managementState().profiles().getFirst();

        assertEquals(PaymentProfile.PROVIDER_TOCHKA, response.provider());
        assertEquals("tochka-profile-key", response.terminalKey());
        assertFalse(response.hasPassword());
        verify(runtimeSettingsService, never()).runtimeMode();
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
                runtimeSettingsService
        );

        assertTrue(service.findByTerminalKey("merchant-id").isEmpty());
    }

    private Manager manager(PaymentProfile profile) {
        User user = new User();
        user.setId(10L);
        user.setUsername("manager");

        Manager manager = new Manager();
        manager.setId(3L);
        manager.setUser(user);
        manager.setPaymentProfile(profile);
        return manager;
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

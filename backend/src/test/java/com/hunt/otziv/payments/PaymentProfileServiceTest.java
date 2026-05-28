package com.hunt.otziv.payments;

import com.hunt.otziv.payments.dto.UpdateManagerManualPaymentSettingsRequest;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

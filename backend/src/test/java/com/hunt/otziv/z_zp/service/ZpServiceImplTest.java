package com.hunt.otziv.z_zp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRuntimeSwitch;
import com.hunt.otziv.contractor_payments.service.ContractorRewardAttributionService;
import com.hunt.otziv.contractor_payments.service.ContractorRewardLedgerService;
import com.hunt.otziv.contractor_payments.service.ContractorRewardSourceCodes;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ZpServiceImplTest {

    @Mock private ZpRepository zpRepository;
    @Mock private UserService userService;
    @Mock private ContractorRewardAttributionService attributionService;
    @Mock private ContractorPaymentRuntimeSwitch runtimeSwitch;
    @Mock private ContractorRewardLedgerService ledgerService;

    private ZpServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ZpServiceImpl(
                zpRepository,
                userService,
                attributionService,
                runtimeSwitch,
                ledgerService
        );
        when(zpRepository.save(any(Zp.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void disabledLiveAttributionPreservesExactLegacyRowsWhenSnapshotFails() {
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(false);
        Order order = order();
        when(attributionService.attributeRecordedWork(order))
                .thenThrow(new IllegalStateException("test snapshot failure"));
        BigDecimal payableSum = new BigDecimal("1234.56");

        assertThat(service.save(order, payableSum, 7)).isTrue();

        ArgumentCaptor<Zp> rewards = ArgumentCaptor.forClass(Zp.class);
        verify(zpRepository, times(2)).save(rewards.capture());
        List<Zp> rows = rewards.getAllValues();
        Zp managerReward = rows.get(0);
        Zp specialistReward = rows.get(1);

        assertThat(managerReward.getFio()).isEqualTo("Текущий менеджер");
        assertThat(managerReward.getUserId()).isEqualTo(201L);
        assertThat(managerReward.getProfessionId()).isEqualTo(21L);
        assertThat(managerReward.getAmount()).isEqualTo(7);
        assertThat(managerReward.getSum()).isEqualByComparingTo("123.456");
        assertThat(managerReward.getSource()).isEqualTo("ORDER_MANAGER_REWARD");
        assertThat(managerReward.getContractorRole()).isEqualTo(ContractorRole.MANAGER);

        assertThat(specialistReward.getFio()).isEqualTo("Текущий специалист");
        assertThat(specialistReward.getUserId()).isEqualTo(101L);
        assertThat(specialistReward.getProfessionId()).isEqualTo(11L);
        assertThat(specialistReward.getAmount()).isEqualTo(7);
        assertThat(specialistReward.getSum()).isEqualByComparingTo("493.824");
        assertThat(specialistReward.getRewardBasis()).isEqualByComparingTo(payableSum);
        assertThat(specialistReward.getSource()).isEqualTo("ORDER_SPECIALIST_REWARD");
        assertThat(specialistReward.getContractorRole()).isEqualTo(ContractorRole.SPECIALIST);
        assertThat(specialistReward.isAttributionFinal()).isFalse();
        assertThat(specialistReward.getAttributionSnapshot())
                .isEqualTo("v1|11,101,1234.56,7,0.4");

        verify(attributionService, never()).attribute(any(Order.class), any(BigDecimal.class));
        verify(ledgerService).synchronizeSourcesSafely(List.of(managerReward));
        verify(ledgerService).synchronizeSourcesSafely(List.of(specialistReward));
    }

    @Test
    void enabledLiveAttributionWritesCompletionSourcesEvenThroughLegacyWriter() {
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(true);
        Order order = order();
        User splitSpecialist = order.getWorker().getUser();
        when(attributionService.attribute(order, new BigDecimal("1234.56")))
                .thenReturn(List.of(new ContractorRewardAttributionService.SpecialistShare(
                        splitSpecialist,
                        order.getWorker().getId(),
                        new BigDecimal("1234.56"),
                        7
                )));

        assertThat(service.save(order, new BigDecimal("1234.56"), 7)).isTrue();

        ArgumentCaptor<Zp> rewards = ArgumentCaptor.forClass(Zp.class);
        verify(zpRepository, times(2)).save(rewards.capture());
        List<Zp> rows = rewards.getAllValues();

        assertThat(rows.get(0).getSource())
                .isEqualTo(ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER);
        assertThat(rows.get(0).getContractorRole()).isEqualTo(ContractorRole.MANAGER);
        assertThat(rows.get(1).getSource())
                .isEqualTo(ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST);
        assertThat(rows.get(1).getContractorRole()).isEqualTo(ContractorRole.SPECIALIST);
        assertThat(rows.get(1).isAttributionFinal()).isTrue();
        verify(ledgerService).synchronizeSourcesSafely(List.of(rows.get(0)));
        verify(ledgerService).synchronizeSourcesSafely(List.of(rows.get(1)));
    }

    private Order order() {
        User specialistUser = new User();
        specialistUser.setId(101L);
        specialistUser.setFio("Текущий специалист");
        specialistUser.setCoefficient(new BigDecimal("0.40"));
        Worker worker = new Worker();
        worker.setId(11L);
        worker.setUser(specialistUser);

        User managerUser = new User();
        managerUser.setId(201L);
        managerUser.setFio("Текущий менеджер");
        managerUser.setCoefficient(new BigDecimal("0.10"));
        Manager manager = new Manager();
        manager.setId(21L);
        manager.setUser(managerUser);

        Order order = new Order();
        order.setId(31L);
        order.setWorker(worker);
        order.setManager(manager);
        return order;
    }
}

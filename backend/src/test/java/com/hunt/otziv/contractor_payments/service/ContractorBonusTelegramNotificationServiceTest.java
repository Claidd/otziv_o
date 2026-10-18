package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.model.ContractorAccrualAdjustment;
import com.hunt.otziv.contractor_payments.model.ContractorAccrualAdjustmentKind;
import com.hunt.otziv.contractor_payments.repository.ContractorBonusTelegramNotificationOutboxRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorBonusTelegramNotificationOutboxRepository.Bonus;
import com.hunt.otziv.contractor_payments.repository.ContractorBonusTelegramNotificationOutboxRepository.Delivery;
import com.hunt.otziv.t_telegrambot.api.TelegramNotifications;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractorBonusTelegramNotificationServiceTest {

    @Mock private ContractorBonusTelegramNotificationOutboxRepository repository;
    @Mock private ContractorBonusTelegramNotificationClaimService claimService;
    @Mock private TelegramNotifications telegramNotifications;

    private ContractorBonusTelegramNotificationService service;

    @BeforeEach
    void setUp() {
        service = new ContractorBonusTelegramNotificationService(repository, claimService, telegramNotifications);
    }

    @Test
    void enqueuesOnlyNewBonusAdjustment() {
        ContractorAccrualAdjustment bonus = new ContractorAccrualAdjustment();
        bonus.setId(42L);
        bonus.setKind(ContractorAccrualAdjustmentKind.BONUS);
        when(repository.enqueue(42L)).thenReturn(true);
        service.enqueue(bonus);
        verify(repository).enqueue(42L);

        ContractorAccrualAdjustment deduction = new ContractorAccrualAdjustment();
        deduction.setId(43L);
        deduction.setKind(ContractorAccrualAdjustmentKind.DEDUCTION);
        service.enqueue(deduction);
        verify(repository, never()).enqueue(43L);
    }

    @Test
    void missingBonusQueueIntentRejectsTheAccrualTransaction() {
        ContractorAccrualAdjustment bonus = new ContractorAccrualAdjustment();
        bonus.setId(42L);
        bonus.setKind(ContractorAccrualAdjustmentKind.BONUS);
        when(repository.enqueue(42L)).thenReturn(false);

        assertThatThrownBy(() -> service.enqueue(bonus))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("intent was not stored");
    }

    @Test
    void sendsBonusOnlyToCurrentPrivateEmployeeChatAndMarksSent() {
        Delivery delivery = due();
        when(repository.findBonus(42L)).thenReturn(Optional.of(bonus(true, 987L, false)));
        when(telegramNotifications.sendTextOnce(eq(987L), anyString())).thenReturn(true);
        when(claimService.markSent(delivery)).thenReturn(true);

        assertThat(service.retryBatch()).isEqualTo(1);

        verify(telegramNotifications).sendTextOnce(eq(987L), anyString());
        verify(claimService).markSent(delivery);
        verify(claimService, never()).markFailed(eq(delivery), anyString());
    }

    @Test
    void doesNotSendWhenEmployeeHasNoPrivateChat() {
        Delivery delivery = due();
        when(repository.findBonus(42L)).thenReturn(Optional.of(bonus(true, null, false)));

        assertThat(service.retryBatch()).isZero();

        verify(claimService).markSkipped(delivery, "employee_private_telegram_chat_missing");
        verifyNoInteractions(telegramNotifications);
    }

    @Test
    void doesNotSendReversedBonus() {
        Delivery delivery = due();
        when(repository.findBonus(42L)).thenReturn(Optional.of(bonus(true, 987L, true)));

        assertThat(service.retryBatch()).isZero();

        verify(claimService).markSkipped(delivery, "bonus_adjustment_reversed");
        verifyNoInteractions(telegramNotifications);
    }

    @Test
    void doesNotSendToInactiveEmployee() {
        Delivery delivery = due();
        when(repository.findBonus(42L)).thenReturn(Optional.of(bonus(false, 987L, false)));

        assertThat(service.retryBatch()).isZero();

        verify(claimService).markSkipped(delivery, "employee_inactive");
        verifyNoInteractions(telegramNotifications);
    }

    @Test
    void failedTelegramAttemptIsRetriedWithoutTouchingAccrual() {
        Delivery delivery = due();
        when(repository.findBonus(42L)).thenReturn(Optional.of(bonus(true, 987L, false)));
        when(telegramNotifications.sendTextOnce(eq(987L), anyString())).thenReturn(false);

        assertThat(service.retryBatch()).isZero();

        verify(claimService).markFailed(delivery, "telegram_send_not_confirmed");
        verify(claimService, never()).markSent(delivery);
    }

    @Test
    void textSaysAccruedRatherThanPaidAndIncludesReasonAndMonth() {
        String message = ContractorBonusTelegramNotificationService.message(bonus(true, 987L, false));

        assertThat(message).contains("Начислена премия", "1", "400,00 ₽", "октябрь 2026", "За отличную работу");
        assertThat(message).contains("Выплата учитывается отдельно");
        assertThat(message).doesNotContain("выплачена");
    }

    private Delivery due() {
        Delivery delivery = new Delivery(42L, 1, "123e4567-e89b-12d3-a456-426614174000");
        when(repository.findDueAdjustmentIds(50)).thenReturn(List.of(42L));
        when(claimService.tryClaim(42L)).thenReturn(Optional.of(delivery));
        return delivery;
    }

    private Bonus bonus(boolean active, Long chatId, boolean reversed) {
        return new Bonus(
                42L, "BONUS", 140_000L,
                LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 1),
                "За отличную работу", 5L, active, chatId, reversed
        );
    }
}

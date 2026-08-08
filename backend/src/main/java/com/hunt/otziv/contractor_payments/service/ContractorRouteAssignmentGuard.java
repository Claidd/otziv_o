package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardMarkerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ContractorRouteAssignmentGuard {

    private final ContractorPaymentShadowService paymentShadowService;
    private final ContractorCompletionRewardMarkerRepository completionRewardMarkerRepository;

    public void requireWorkerReassignmentAllowed(Long orderId) {
        requireRecipientMutationAllowed(
                orderId,
                "Специалиста нельзя изменить: для заказа уже зафиксирован получатель платежа"
        );
    }

    public void requireManagerReassignmentAllowed(Long orderId) {
        requireRecipientMutationAllowed(
                orderId,
                "Менеджера нельзя изменить: для заказа уже зафиксирован получатель платежа"
        );
    }

    public void requirePaymentCancellationAllowed(Long orderId) {
        requireRecipientMutationAllowed(
                orderId,
                "Оплату нельзя отменить: клиенту уже выданы реквизиты получателя. "
                        + "Используйте явный статус \"Не оплачено\" или ручную сверку."
        );
    }

    public void requirePayableMutationAllowed(Long orderId) {
        if (orderId == null) {
            return;
        }
        if (completionRewardMarkerRepository.existsByOrderId(orderId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Выполненная работа уже зафиксирована для расчетов. Изменение возможно только отдельной корректировкой."
            );
        }
        requireRecipientMutationAllowed(
                orderId,
                "Сумму или состав заказа нельзя изменить: клиенту уже выданы реквизиты получателя"
        );
    }

    private void requireRecipientMutationAllowed(Long orderId, String message) {
        if (paymentShadowService.hasFrozenLiveRoute(orderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, message);
        }
    }
}

package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.p_products.api.OrderDeliveryControlContext;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Reuses durable manual commands; no operation allocation, dispatch or queue rearming. */
@Service
@RequiredArgsConstructor
public class ManagerControlManualDeliveryRepair {
    private final OrderDeliveryControlContext orders;
    private final ManagerControlOrderDeliveryState delivery;
    private final ManagerClientMessageWorker worker;
    private final ManagerControlTransactionRunner transactions;
    private final ManagerDailyControlConcreteItemRepository cards;
    private final ManagerControlConcretePresenter presenter;
    private final ManagerControlCardLifecycle lifecycle;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<ManagerControlConcreteItemResponse> repair(long cardId, long orderId, Long managerId) {
        var source = orders.read(orderId).filter(order -> Objects.equals(order.managerId(), managerId)).orElse(null);
        if (source == null) throw conflict("Менеджер или заказ изменился. Обновите карточку.");
        var pending = delivery.history(source).stream().filter(row -> ManagerControlOrderDeliveryState.blocks(row.delivery()))
                .limit(5).toList();
        if (pending.isEmpty()) return Optional.empty();
        for (var row : pending) worker.reconcileExisting(row.delivery().operationId());
        return Optional.of(transactions.contactReservation(() -> {
            var card = cards.findByIdForUpdate(cardId).orElseThrow(() -> conflict("Карточка контроля больше не доступна"));
            var current = orders.lock(orderId).orElseThrow(() -> conflict("Заказ больше не доступен"));
            if (!Objects.equals(source, current) || card.getControl() == null
                    || !Objects.equals(card.getControl().managerId(), managerId) || !Objects.equals(card.getEntityId(), orderId)
                    || !java.util.List.of("ORDER", "WORKER_ORDER_NEW").contains(card.getEntityType()))
                throw conflict("Источник изменился во время сверки; результат операции сохранён отдельно.");
            if (delivery.history(current).stream().anyMatch(row -> ManagerControlOrderDeliveryState.blocks(row.delivery())))
                throw conflict("Существующая ручная отправка проверена, но её результат пока не подтверждён. Повторного сообщения не было. Нужна однозначная квитанция исходной операции.");
            if (delivery.restoreConfirmedContact(card)) {
                cards.save(card);
                lifecycle.updateParentItemFromConcreteItems(card.getParentItem());
            }
            return presenter.concreteItemResponse(card);
        }));
    }

    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}

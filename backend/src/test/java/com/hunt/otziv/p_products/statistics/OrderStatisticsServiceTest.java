package com.hunt.otziv.p_products.statistics;

import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.util.Pair;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderStatisticsServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Test
    void countOrdersByStatusConvertsRowsSafely() {
        OrderStatisticsService service = service();
        when(orderRepository.countGroupedByStatus()).thenReturn(Arrays.asList(
                new Object[]{"Новый", 2L},
                new Object[]{null, 3},
                new Object[]{"Много", (long) Integer.MAX_VALUE + 100L},
                new Object[]{"broken"},
                null
        ));

        Map<String, Integer> result = service.countOrdersByStatus();

        assertEquals(3, result.size());
        assertEquals(2, result.get("Новый"));
        assertEquals(3, result.get(""));
        assertEquals(Integer.MAX_VALUE, result.get("Много"));
    }

    @Test
    void countActionableOrdersByStatusUsesWaitingFilteredRepository() {
        OrderStatisticsService service = service();
        when(orderRepository.countGroupedByActionableStatus()).thenReturn(List.of(
                new Object[]{"Новый", 2L},
                new Object[]{"Коррекция", 1L}
        ));

        Map<String, Integer> result = service.countActionableOrdersByStatus();

        assertEquals(2, result.get("Новый"));
        assertEquals(1, result.get("Коррекция"));
    }

    @Test
    void scopedCountersReturnEmptyValuesWithoutRepositoryForMissingScope() {
        OrderStatisticsService service = service();

        assertTrue(service.countOrdersByWorkerIdsAndStatus((List<Long>) null, "Новый").isEmpty());
        assertTrue(service.countOrdersByWorkerIdsAndStatus(List.of(), "Новый").isEmpty());
        assertTrue(service.countOrdersByWorkerIdsAndStatus((Collection<Long>) null, "Новый").isEmpty());
        assertTrue(service.countOrdersByStatusToManager(null).isEmpty());
        assertTrue(service.countOrdersByStatusToOwner(Set.of()).isEmpty());
        assertTrue(service.countOrdersByStatusToWorker(null).isEmpty());
        assertTrue(service.countActionableOrdersByStatusToManager(null).isEmpty());
        assertTrue(service.countActionableOrdersByStatusToOwner(Set.of()).isEmpty());
        assertTrue(service.countActionableOrdersByStatusToWorker(null).isEmpty());
        assertEquals(0, service.countAllOrdersToManager(null));
        assertEquals(0, service.countAllOrdersToOwner(Set.of()));
        assertEquals(0, service.countOrdersByWorker(null));

        verifyNoInteractions(orderRepository);
    }

    @Test
    void countOrdersByWorkerIdsAndStatusMapsRepositoryRows() {
        OrderStatisticsService service = service();
        List<Long> workerIds = List.of(10L, 20L);

        when(orderRepository.countByWorkerIdsAndStatus(workerIds, "Публикация"))
                .thenReturn(List.of(new Object[]{10L, 4L}, new Object[]{20L, 7L}));

        Map<Long, Integer> result = service.countOrdersByWorkerIdsAndStatus(workerIds, "Публикация");

        assertEquals(Map.of(10L, 4, 20L, 7), result);
    }

    @Test
    void getNewOrderAllCombinesWorkerAndManagerStats() {
        OrderStatisticsService service = service();
        when(orderRepository.findAllIdByNewOrderAllStatus("Новый", "Коррекция"))
                .thenReturn(List.of(
                        new Object[]{"operator", "Оператор", 3L, 1L},
                        new Object[]{"manager", "Менеджер", 5L, 2L}
                ));

        Map<String, Pair<Long, Long>> result = service.getNewOrderAll("Новый", "Коррекция");

        assertEquals(Pair.of(3L, 1L), result.get("Оператор"));
        assertEquals(Pair.of(5L, 2L), result.get("Менеджер"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getAllOrdersToMonthByStatusUsesTwoMonthLookbackAndGroupsManagersFirst() {
        OrderStatisticsService service = service();
        LocalDate firstDay = LocalDate.of(2026, 5, 1);
        LocalDate lastDay = LocalDate.of(2026, 5, 31);
        ArgumentCaptor<List<String>> statusesCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);

        when(orderRepository.getOrdersByStatusForUsers(
                statusesCaptor.capture(),
                fromCaptor.capture(),
                eq(lastDay)
        )).thenReturn(List.of(
                new Object[]{"Работник", "Новый", 2L, "worker"},
                new Object[]{"Менеджер", "Коррекция", 4L, "manager"}
        ));

        Map<String, Map<String, Long>> result = service.getAllOrdersToMonthByStatus(
                firstDay,
                lastDay,
                "Новый",
                "В проверку",
                "На проверке",
                "Коррекция",
                "Опубликовано",
                "Выставлен счет",
                "Напоминание",
                "Не оплачено"
        );

        assertEquals(firstDay.minusMonths(2), fromCaptor.getValue());
        assertEquals("Новый", statusesCaptor.getValue().get(0));
        assertEquals(4L, result.get("Менеджер").get("Коррекция"));
        assertEquals(2L, result.get("Работник").get("Новый"));
        assertEquals(List.of("Менеджер", "Работник"), List.copyOf(result.keySet()));
    }

    private OrderStatisticsService service() {
        return new OrderStatisticsService(orderRepository);
    }
}

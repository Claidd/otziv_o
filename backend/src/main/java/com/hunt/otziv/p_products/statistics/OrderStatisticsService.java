package com.hunt.otziv.p_products.statistics;

import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import lombok.RequiredArgsConstructor;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderStatisticsService {

    private final OrderRepository orderRepository;

    public Map<Long, Integer> countOrdersByWorkerIdsAndStatus(List<Long> workerIds, String status) {
        if (workerIds == null || workerIds.isEmpty()) {
            return Map.of();
        }

        return orderRepository.countByWorkerIdsAndStatus(workerIds, status)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> ((Long) row[1]).intValue()
                ));
    }

    public Map<Long, Integer> countOrdersByWorkerIdsAndStatus(Collection<Long> workerIds, String status) {
        if (workerIds == null || workerIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Integer> result = new HashMap<>();
        for (Object[] row : orderRepository.countByWorkerIdsAndStatus(workerIds, status)) {
            Long workerId = (Long) row[0];
            Long count = (Long) row[1];
            result.put(workerId, count.intValue());
        }
        return result;
    }

    public int getAllOrderDTOByStatus(String status) {
        return orderRepository.countByStatusTitle(status);
    }

    public Map<String, Integer> countOrdersByStatus() {
        return toStatusCountMap(orderRepository.countGroupedByStatus());
    }

    public Map<String, Integer> countActionableOrdersByStatus() {
        return toStatusCountMap(orderRepository.countGroupedByActionableStatus());
    }

    public Map<String, Integer> countOrdersByStatusToManager(Manager manager) {
        if (manager == null) {
            return Map.of();
        }
        return toStatusCountMap(orderRepository.countGroupedByStatusAndManager(manager));
    }

    public Map<String, Integer> countActionableOrdersByStatusToManager(Manager manager) {
        if (manager == null) {
            return Map.of();
        }
        return toStatusCountMap(orderRepository.countGroupedByActionableStatusAndManager(manager));
    }

    public Map<String, Integer> countOrdersByStatusToOwner(Set<Manager> managerList) {
        if (managerList == null || managerList.isEmpty()) {
            return Map.of();
        }
        return toStatusCountMap(orderRepository.countGroupedByStatusAndManagers(managerList));
    }

    public Map<String, Integer> countActionableOrdersByStatusToOwner(Set<Manager> managerList) {
        if (managerList == null || managerList.isEmpty()) {
            return Map.of();
        }
        return toStatusCountMap(orderRepository.countGroupedByActionableStatusAndManagers(managerList));
    }

    public Map<String, Integer> countOrdersByStatusToWorker(Worker worker) {
        if (worker == null) {
            return Map.of();
        }
        return toStatusCountMap(orderRepository.countGroupedByStatusAndWorker(worker));
    }

    public Map<String, Integer> countActionableOrdersByStatusToWorker(Worker worker) {
        if (worker == null) {
            return Map.of();
        }
        return toStatusCountMap(orderRepository.countGroupedByActionableStatusAndWorker(worker));
    }

    public int countAllOrders() {
        return orderRepository.countAllOrders();
    }

    public int countAllOrdersToManager(Manager manager) {
        if (manager == null) {
            return 0;
        }
        return orderRepository.countByManager(manager);
    }

    public int countAllOrdersToOwner(Set<Manager> managerList) {
        if (managerList == null || managerList.isEmpty()) {
            return 0;
        }
        return orderRepository.countByManagers(managerList);
    }

    public int countOrdersByWorker(Worker worker) {
        if (worker == null) {
            return 0;
        }
        return orderRepository.countByWorker(worker);
    }

    public int getAllOrderDTOByStatusToManager(Manager manager, String status) {
        return orderRepository.countByManagerAndStatusTitle(manager, status);
    }

    public int getAllOrderDTOByStatusToOwner(Set<Manager> managerList, String status) {
        return orderRepository.countByManagersAndStatusTitle(managerList, status);
    }

    public int countOrdersByWorkerAndStatus(Worker worker, String status) {
        return orderRepository.countByWorkerAndStatus(worker, status);
    }

    public Map<String, Pair<Long, Long>> getNewOrderAll(String statusNew, String statusCorrect) {
        List<Object[]> results = orderRepository.findAllIdByNewOrderAllStatus(statusNew, statusCorrect);

        Map<String, Pair<Long, Long>> workerStats = new HashMap<>();
        Map<String, Pair<Long, Long>> managerStats = new HashMap<>();

        for (Object[] row : results) {
            String type = (String) row[0];
            String fio = (String) row[1];
            long newOrders = ((Number) row[2]).longValue();
            long correctOrders = ((Number) row[3]).longValue();

            if ("operator".equals(type)) {
                workerStats.put(fio, Pair.of(newOrders, correctOrders));
            } else {
                managerStats.put(fio, Pair.of(newOrders, correctOrders));
            }
        }

        Map<String, Pair<Long, Long>> combinedStats = new HashMap<>(workerStats);
        combinedStats.putAll(managerStats);

        return combinedStats;
    }

    public Map<String, Long> getAllOrdersToMonth(String status, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        List<Object[]> results = orderRepository.getAllOrdersToMonth(status, firstDayOfMonth, lastDayOfMonth);

        Map<String, Long> workerOrders = new HashMap<>();
        Map<String, Long> managerOrders = new HashMap<>();

        for (Object[] row : results) {
            String workerFio = (String) row[0];
            Long workerOrderCount = (Long) row[1];

            String managerFio = (String) row[2];
            Long managerOrderCount = (Long) row[3];

            if (workerFio != null) {
                workerOrders.merge(workerFio, workerOrderCount != null ? workerOrderCount : 0L, Long::sum);
            }
            if (managerFio != null) {
                managerOrders.merge(managerFio, managerOrderCount != null ? managerOrderCount : 0L, Long::sum);
            }
        }

        Map<String, Long> allOrders = new HashMap<>();
        allOrders.putAll(workerOrders);
        allOrders.putAll(managerOrders);
        return allOrders;
    }

    public Map<String, Map<String, Long>> getAllOrdersToMonthByStatus(
            LocalDate firstDayOfMonth,
            LocalDate lastDayOfMonth,
            String orderInNew,
            String orderToCheck,
            String orderInCheck,
            String orderInCorrect,
            String orderInPublished,
            String orderInWaitingPay1,
            String orderInWaitingPay2,
            String orderNoPay
    ) {
        List<String> statuses = List.of(
                orderInNew,
                orderToCheck,
                orderInCheck,
                orderInCorrect,
                orderInPublished,
                orderInWaitingPay1,
                orderInWaitingPay2,
                orderNoPay
        );

        List<Object[]> results = orderRepository.getOrdersByStatusForUsers(
                statuses,
                firstDayOfMonth.minusMonths(2),
                lastDayOfMonth
        );

        Map<String, Map<String, Long>> ordersMap = new LinkedHashMap<>();
        Map<String, Map<String, Long>> managerOrders = new LinkedHashMap<>();
        Map<String, Map<String, Long>> workerOrders = new LinkedHashMap<>();

        for (Object[] row : results) {
            if (row.length < 4) {
                continue;
            }

            String fio = (String) row[0];
            String status = (String) row[1];
            Long count = row[2] != null ? (Long) row[2] : 0L;
            String role = (String) row[3];

            if ("manager".equals(role)) {
                managerOrders.computeIfAbsent(fio, k -> new LinkedHashMap<>()).put(status, count);
            } else {
                workerOrders.computeIfAbsent(fio, k -> new LinkedHashMap<>()).put(status, count);
            }
        }

        ordersMap.putAll(managerOrders);
        ordersMap.putAll(workerOrders);

        return ordersMap;
    }

    private Map<String, Integer> toStatusCountMap(List<Object[]> rows) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (rows == null) {
            return result;
        }

        for (Object[] row : rows) {
            if (row == null || row.length < 2) {
                continue;
            }
            String status = row[0] == null ? "" : row[0].toString();
            long count = row[1] instanceof Number number ? number.longValue() : 0L;
            result.put(status, count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count);
        }
        return result;
    }
}

package com.hunt.otziv.mobile_push.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class MobilePushBusinessNotificationService {

    private static final String ROLE_OWNER = "ROLE_OWNER";

    private final MobilePushSenderService mobilePushSenderService;
    private final UserRepository userRepository;

    public void notifyWorkerNewOrder(Order order) {
        User workerUser = workerUser(order);
        if (workerUser == null) {
            return;
        }

        sendSafely(
                workerUser,
                "Новый заказ",
                orderTitle(order) + ": назначен новый заказ.",
                orderRoute(order)
        );
    }

    public void notifyWorkerCorrection(Order order) {
        User workerUser = workerUser(order);
        if (workerUser == null) {
            return;
        }

        sendSafely(
                workerUser,
                "Заказ на коррекции",
                orderTitle(order) + ": внесите правки по заказу.",
                orderRoute(order)
        );
    }

    public void notifyManagerOrderReadyForReview(Order order) {
        User managerUser = managerUser(order);
        if (managerUser == null) {
            return;
        }

        sendSafely(
                managerUser,
                "Заказ готов к проверке",
                orderTitle(order) + ": специалист завершил работу.",
                "/tabs/orders"
        );
    }

    public void notifyManagerOrderPublished(Order order) {
        User managerUser = managerUser(order);
        if (managerUser == null) {
            return;
        }

        sendSafely(
                managerUser,
                "Заказ опубликован",
                orderTitle(order) + ": заказ перешел в публикацию.",
                "/tabs/orders"
        );
    }

    public void notifyOwnersOrderPaid(Order order) {
        List<User> owners = activeOwners(order);
        if (owners.isEmpty()) {
            return;
        }

        String sum = money(order == null ? null : order.getSum());
        String body = orderTitle(order) + ": заказ оплачен" + (sum.isBlank() ? "." : " на " + sum + " руб.");
        for (User owner : owners) {
            sendSafely(owner, "Заказ оплачен", body, "/tabs/tbank");
        }
    }

    private void sendSafely(User user, String title, String body, String route) {
        try {
            mobilePushSenderService.sendToUser(user, title, body, route);
        } catch (RuntimeException e) {
            log.warn("Mobile push notification skipped: userId={}, title={}", user == null ? null : user.getId(), title, e);
        }
    }

    private List<User> activeOwners(Order order) {
        Map<Long, User> uniqueOwners = new LinkedHashMap<>();
        for (User owner : userRepository.findAllOwners(ROLE_OWNER)) {
            if (owner != null && owner.getId() != null) {
                uniqueOwners.put(owner.getId(), owner);
            }
        }
        List<User> owners = List.copyOf(uniqueOwners.values());
        Long managerId = order != null && order.getManager() != null ? order.getManager().getId() : null;
        if (managerId == null) {
            return owners;
        }

        List<User> scopedOwners = owners.stream()
                .filter(owner -> owner.getManagers() != null)
                .filter(owner -> owner.getManagers().stream()
                        .anyMatch(manager -> manager != null && managerId.equals(manager.getId())))
                .toList();
        return scopedOwners.isEmpty() ? owners : scopedOwners;
    }

    private User workerUser(Order order) {
        Worker worker = order == null ? null : order.getWorker();
        return worker == null ? null : worker.getUser();
    }

    private User managerUser(Order order) {
        Manager manager = order == null ? null : order.getManager();
        return manager == null ? null : manager.getUser();
    }

    private String orderRoute(Order order) {
        Long companyId = order != null && order.getCompany() != null ? order.getCompany().getId() : null;
        Long orderId = order == null ? null : order.getId();
        if (companyId == null || orderId == null) {
            return "/tabs/orders";
        }
        return "/tabs/orders/" + companyId + "/" + orderId;
    }

    private String orderTitle(Order order) {
        String company = companyTitle(order);
        String filial = filialTitle(order);
        if (company.isBlank() && filial.isBlank()) {
            return "Заказ";
        }
        if (company.isBlank()) {
            return filial;
        }
        if (filial.isBlank()) {
            return company;
        }
        return company + " - " + filial;
    }

    private String companyTitle(Order order) {
        Company company = order == null ? null : order.getCompany();
        return company == null ? "" : normalize(company.getTitle());
    }

    private String filialTitle(Order order) {
        Filial filial = order == null ? null : order.getFilial();
        return filial == null ? "" : normalize(filial.getTitle());
    }

    private String money(BigDecimal value) {
        if (value == null || BigDecimal.ZERO.compareTo(value) == 0) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/** Client contact templates shared by actual delivery and control-card previews. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManagerControlClientMessageText {
    private static final Set<String> MANUAL_CONTACT_ORDER_STATUSES = Set.of(
            "Новый",
            "На проверке",
            "Опубликовано",
            "Выставлен счет",
            "Напоминание",
            "Не оплачено"
    );
    private final ScheduledClientMessageService scheduledClientMessageService;
    private final OrderRepository orderRepository;

    String clientControlMessage(ManagerDailyControlConcreteItem concreteItem, Order order) {
        String status = orderStatusTitle(order);
        if (!MANUAL_CONTACT_ORDER_STATUSES.contains(status)) {
            return "";
        }
        if ("Новый".equals(status) && order != null && order.isWaitingForClient()) {
            return clientTextContactText(order);
        }
        if ("На проверке".equals(status)) {
            String detailsId = orderDetailsId(concreteItem, order);
            if (detailsId.isBlank()) {
                return "";
            }
            return List.of(
                    orderHeading(order),
                    "Здравствуйте, напоминаем, пожалуйста, проверьте шаблоны отзывов и внесите правки, если они нужны.",
                    "Ссылка на проверку отзывов: " + absoluteAppUrl("/" + detailsId)
            ).stream().filter(value -> !safe(value).isBlank()).collect(Collectors.joining("\n\n"));
        }
        return paymentContactText(order, status);
    }

    boolean isPaymentControlOrder(Order order) {
        return MANUAL_CONTACT_ORDER_STATUSES.contains(orderStatusTitle(order))
                && !"Новый".equals(orderStatusTitle(order))
                && !"На проверке".equals(orderStatusTitle(order));
    }

    String paymentContactText(Order order, String status) {
        String payText = safe(order == null || order.getManager() == null ? null : order.getManager().getPayText());
        if (payText.isBlank()) {
            payText = switch (status) {
                case "Опубликовано" -> "Здравствуйте, ваш заказ выполнен, просьба оплатить.";
                case "Не оплачено" -> "Здравствуйте, напоминаем, пожалуйста, по оплате заказа. Пришлите чек, пожалуйста, как оплатите.";
                default -> "Здравствуйте, напоминаем, пожалуйста, об оплате заказа. Пришлите чек, пожалуйста, как оплатите.";
            };
        }
        String amount = money(order == null ? null : order.getSum());
        String body = amount.isBlank() ? payText : payText + " К оплате: " + amount + " руб.";
        return List.of(orderHeading(order), body).stream()
                .filter(value -> !safe(value).isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    String clientTextContactText(Order order) {
        if (scheduledClientMessageService != null) {
            try {
                return scheduledClientMessageService.clientTextReminderText(order);
            } catch (Exception e) {
                log.warn("Не удалось собрать текст автонапоминания клиенту для заказа {}", order == null ? null : order.getId(), e);
            }
        }
        return List.of(
                orderHeading(order),
                "Здравствуйте! Напоминаем, пожалуйста, пришлите текст или пожелания для отзывов по заказу №"
                        + (order == null || order.getId() == null ? "" : order.getId())
                        + ", чтобы мы могли продолжить работу."
        ).stream().filter(value -> !safe(value).isBlank()).collect(Collectors.joining("\n\n"));
    }

    String orderDetailsId(ManagerDailyControlConcreteItem concreteItem, Order order) {
        String detailsId = safe(concreteItem == null ? null : concreteItem.getOrderDetailsId());
        if (!detailsId.isBlank()) {
            return detailsId;
        }
        if (order == null || order.getDetails() == null || order.getDetails().isEmpty() || order.getDetails().getFirst().getId() == null) {
            return "";
        }
        return order.getDetails().getFirst().getId().toString();
    }

    String orderHeading(Order order) {
        if (order == null) {
            return "";
        }
        String company = order.getCompany() == null ? "" : safe(order.getCompany().getTitle());
        String filial = order.getFilial() == null ? "" : safe(order.getFilial().getTitle());
        return List.of(company, filial).stream()
                .filter(value -> !safe(value).isBlank())
                .collect(Collectors.joining(" - "));
    }

    String orderStatusTitle(Order order) {
        return order == null || order.getStatus() == null ? "" : safe(order.getStatus().getTitle());
    }

    String orderContactText(OrderDTOList order) {
        String status = safe(order.getStatus());
        if (!MANUAL_CONTACT_ORDER_STATUSES.contains(status)) {
            return null;
        }
        if ("Новый".equals(status) && order.isWaitingForClient() && order.getId() != null) {
            return orderRepository.findById(order.getId())
                    .map(this::clientTextContactText)
                    .filter(text -> !safe(text).isBlank())
                    .orElse(null);
        }
        if ("На проверке".equals(status)) {
            if (order.getOrderDetailsId() == null) {
                return null;
            }
            return List.of(
                    orderHeading(order),
                    "Здравствуйте, напоминаем, пожалуйста, проверьте шаблоны отзывов и внесите правки, если они нужны.",
                    "Ссылка на проверку отзывов: " + absoluteAppUrl("/" + order.getOrderDetailsId())
            ).stream().filter(value -> !safe(value).isBlank()).collect(Collectors.joining("\n\n"));
        }
        return paymentContactText(order, status);
    }

    String paymentContactText(OrderDTOList order, String status) {
        String payText = safe(order.getManagerPayText());
        if (payText.isBlank()) {
            payText = switch (status) {
                case "Опубликовано" -> "Здравствуйте, ваш заказ выполнен, просьба оплатить.";
                case "Не оплачено" -> "Здравствуйте, напоминаем, пожалуйста, по оплате заказа. Пришлите чек, пожалуйста, как оплатите.";
                default -> "Здравствуйте, напоминаем, пожалуйста, об оплате заказа. Пришлите чек, пожалуйста, как оплатите.";
            };
        }
        String amount = money(orderPayableSum(order));
        String body = amount.isBlank() ? payText : payText + " К оплате: " + amount + " руб.";
        return List.of(orderHeading(order), body).stream()
                .filter(value -> !safe(value).isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    BigDecimal orderPayableSum(OrderDTOList order) {
        if (order == null) {
            return BigDecimal.ZERO;
        }
        if (order.getTotalSumWithBadReviews() != null) {
            return order.getTotalSumWithBadReviews();
        }
        return order.getSum() == null ? BigDecimal.ZERO : order.getSum();
    }

    String orderHeading(OrderDTOList order) {
        if (order == null) {
            return "";
        }
        return List.of(safe(order.getCompanyTitle()), safe(order.getFilialTitle())).stream()
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(" - "));
    }

    String absoluteAppUrl(String path) {
        return "https://o-ogo.ru" + (path == null || path.startsWith("/") ? safe(path) : "/" + path);
    }

    String money(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return "";
        }
        BigDecimal value = amount.stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

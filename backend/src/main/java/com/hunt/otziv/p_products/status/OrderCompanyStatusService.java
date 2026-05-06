package com.hunt.otziv.p_products.status;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.next_order.NextOrderRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeStatusTitle;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderCompanyStatusService {

    private static final String STATUS_NEW = "Новый";
    private static final String STATUS_TO_CHECK = "В проверку";
    private static final String STATUS_IN_CHECK = "На проверке";
    private static final String STATUS_CORRECTION = "Коррекция";
    private static final String STATUS_TO_PUBLISH = "Публикация";
    private static final String STATUS_PAYMENT = "Оплачено";
    private static final String STATUS_PUBLIC = "Опубликовано";
    private static final String STATUS_TO_PAY = "Выставлен счет";
    private static final String STATUS_ARCHIVE = "Архив";

    private static final String STATUS_COMPANY_IN_WORK = "В работе";
    private static final String STATUS_COMPANY_IN_STOP = "На стопе";
    private static final String STATUS_COMPANY_IN_NEW_ORDER = "Новый заказ";

    private final CompanyService companyService;
    private final CompanyStatusService companyStatusService;
    private final NextOrderRequestService nextOrderRequestService;

    public void autoManageCompanyStatus(Order changedOrder, String newOrderStatus) {
        try {
            log.info("🚀 === НАЧАЛО АВТОМАТИЧЕСКОГО УПРАВЛЕНИЯ СТАТУСОМ КОМПАНИИ ===");
            log.info("📦 Заказ ID: {} меняет статус на: {}", changedOrder.getId(), newOrderStatus);

            Company company = changedOrder.getCompany();
            if (company == null) {
                log.error("❌ Компания не найдена для заказа ID: {}", changedOrder.getId());
                return;
            }

            String currentCompanyStatus = company.getStatus() != null ? company.getStatus().getTitle() : "";
            log.info("🏢 Компания ID: {}, текущий статус: {}", company.getId(), currentCompanyStatus);

            boolean hasOtherActiveOrders = hasOtherActiveUnpaidOrders(company, changedOrder);
            log.info("🔍 Есть другие активные заказы: {}", hasOtherActiveOrders);

            if (STATUS_ARCHIVE.equals(newOrderStatus)) {
                if (!hasOtherActiveOrders) {
                    if (nextOrderRequestService.hasOpenRequests(company.getId())) {
                        log.info("📌 ПРАВИЛО 1: Архивация заказа. Нет активных заказов, но есть заявка на следующий -> компания в 'Новый заказ'");
                        company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_NEW_ORDER));
                    } else {
                        log.info("📌 ПРАВИЛО 1: Архивация заказа. Нет других активных заказов -> компания в 'Стоп'");
                        company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_STOP));
                    }
                    companyService.save(company);
                    log.info("✅ Статус компании изменен на: {}", company.getStatus().getTitle());
                } else {
                    log.info("📌 ПРАВИЛО 1: Архивация заказа. Есть другие активные заказы -> статус компании не меняем");
                }
            } else if (isActiveOrderStatus(newOrderStatus)) {
                if (STATUS_COMPANY_IN_STOP.equals(currentCompanyStatus) && !hasOtherActiveOrders) {
                    log.info("📌 ПРАВИЛО 2: Активация заказа. Компания в 'Стопе' и нет других активных заказов -> 'В работе'");
                    company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_WORK));
                    companyService.save(company);
                    log.info("✅ Статус компании изменен на: {}", company.getStatus().getTitle());
                } else if (STATUS_COMPANY_IN_STOP.equals(currentCompanyStatus) && hasOtherActiveOrders) {
                    log.info("📌 ПРАВИЛО 2: Активация заказа. Компания в 'Стопе', но есть другие активные заказы -> оставляем 'Стоп'");
                } else {
                    log.info("📌 ПРАВИЛО 2: Активация заказа. Статус компании не требует изменений");
                }
            } else {
                log.info("📌 Статус заказа '{}' не влияет на статус компании", newOrderStatus);
            }

        } catch (Exception e) {
            log.error("🔥 ОШИБКА в autoManageCompanyStatus: {}", e.getMessage(), e);
        }
    }

    private boolean hasOtherActiveUnpaidOrders(Company company, Order currentOrder) {
        try {
            Collection<Order> companyOrders = company.getOrderList();
            if (companyOrders == null || companyOrders.isEmpty()) {
                log.info("У компании ID {} нет других заказов", company.getId());
                return false;
            }

            long otherActiveOrdersCount = companyOrders.stream()
                    .filter(order -> order != null && !Objects.equals(order.getId(), currentOrder.getId()))
                    .filter(order -> {
                        String orderStatus = safeStatusTitle(order);
                        boolean isActive = !STATUS_PAYMENT.equalsIgnoreCase(orderStatus)
                                && !STATUS_ARCHIVE.equalsIgnoreCase(orderStatus);
                        if (isActive) {
                            log.debug("Найден активный неоплаченный заказ ID: {}, статус: {}", order.getId(), orderStatus);
                        }
                        return isActive;
                    })
                    .count();

            log.info("У компании ID {} найдено {} других активных неоплаченных заказов (кроме заказа ID {})",
                    company.getId(), otherActiveOrdersCount, currentOrder.getId());

            return otherActiveOrdersCount > 0;

        } catch (Exception e) {
            log.error("Ошибка при проверке других активных заказов: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean isActiveOrderStatus(String status) {
        Set<String> activeStatuses = Set.of(
                STATUS_TO_PUBLISH,
                STATUS_PUBLIC,
                STATUS_TO_PAY,
                STATUS_TO_CHECK,
                STATUS_CORRECTION,
                STATUS_IN_CHECK,
                STATUS_NEW
        );
        return activeStatuses.contains(status);
    }
}

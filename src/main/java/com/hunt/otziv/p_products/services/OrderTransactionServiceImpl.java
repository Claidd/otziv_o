package com.hunt.otziv.p_products.services;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderCreationService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.services.service.OrderTransactionService;
import com.hunt.otziv.z_zp.services.PaymentCheckService;
import com.hunt.otziv.z_zp.services.ZpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;

@Service
@Slf4j
public class OrderTransactionServiceImpl implements OrderTransactionService {

    private final OrderCreationService creationService;
    private final CompanyService companyService;
    private final ZpService zpService;
    private final PaymentCheckService paymentCheckService;
    private final OrderRepository orderRepository;
    private final CompanyStatusService companyStatusService;
    private final OrderStatusService orderStatusService;

    public static final String STATUS_PAYMENT = "Оплачено";
    public static final String STATUS_COMPANY_IN_NEW_ORDER = "Новый заказ";

    public OrderTransactionServiceImpl(
            OrderCreationService creationService,
            CompanyService companyService,
            ZpService zpService,
            PaymentCheckService paymentCheckService,
            OrderRepository orderRepository,
            CompanyStatusService companyStatusService,
            OrderStatusService orderStatusService
    ) {
        this.creationService = creationService;
        this.companyService = companyService;
        this.zpService = zpService;
        this.paymentCheckService = paymentCheckService;
        this.orderRepository = orderRepository;
        this.companyStatusService = companyStatusService;
        this.orderStatusService = orderStatusService;
    }

    @Override
    @Transactional
    public boolean handlePaymentStatus(Order order) throws Exception {
        if (!order.isComplete() && Objects.equals(order.getAmount(), order.getCounter())) {
            log.info("Заказ не выполнен и счетчики совпадают");

            if (zpService.save(order)) {
                log.info("Сохранили ЗП");
                paymentCheckService.save(order);
                log.info("Сохранили чек");

                Company company = companyService.getCompaniesById(order.getCompany().getId());
                company.setCounterPay(company.getCounterPay() + order.getAmount());
                company.setSumTotal(company.getSumTotal().add(order.getSum()));

                order.setComplete(true);
                order.setPayDay(LocalDate.now());

                orderRepository.save(order);
                companyService.save(checkStatusToCompany(company));

                if (!creationService.createNewOrderWithReviews(
                        company.getId(),
                        order.getDetails().getFirst().getProduct().getId(),
                        creationService.convertToOrderDTOToRepeat(order))) {
                    throw new Exception("Новый заказ не создан автоматически");
                }
            } else {
                log.error("Проблемы при сохранении ЗП");
            }
        }

        order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_PAYMENT));
        orderRepository.save(order);
        return true;
    }

    @Transactional
    public Company checkStatusToCompany(Company company){
        int result = 0;
        for (Order order1 : company.getOrderList()) {
            if (!order1.isComplete()) {
                result = 1;
                break;
            }
        }
        if (result == 0){
            company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_NEW_ORDER));
        }
        return company;
    }
}

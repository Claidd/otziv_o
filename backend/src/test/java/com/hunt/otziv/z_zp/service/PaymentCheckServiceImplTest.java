package com.hunt.otziv.z_zp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.repository.PaymentCheckRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentCheckServiceImplTest {

    @Mock private PaymentCheckRepository paymentCheckRepository;
    @Mock private CompanyService companyService;
    @InjectMocks private PaymentCheckServiceImpl service;

    @Test
    void activeCheckCarriesPaidStatusGuard() {
        Order order = paidOrder();
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(41L)).thenReturn(List.of());

        assertTrue(service.save(order, new BigDecimal("1250.00")));

        ArgumentCaptor<PaymentCheck> captor = ArgumentCaptor.forClass(PaymentCheck.class);
        verify(paymentCheckRepository).save(captor.capture());
        assertEquals(41L, captor.getValue().getOrderId());
        assertEquals(7L, captor.getValue().getPaymentStatusGuard());
        assertTrue(captor.getValue().isActive());
    }

    @Test
    void unpaidOrderCannotCreateActiveCheck() {
        Order order = paidOrder();
        order.setStatus(OrderStatus.builder().id(2L).title("Не оплачено").build());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.save(order, BigDecimal.TEN)
        );

        assertTrue(error.getMessage().contains("Не удалось сохранить чек"));
    }

    private Order paidOrder() {
        User user = new User();
        user.setId(13L);
        Manager manager = new Manager();
        manager.setUser(user);
        return Order.builder()
                .id(41L)
                .company(Company.builder().id(5L).title("Компания").build())
                .manager(manager)
                .status(OrderStatus.builder().id(7L).title("Оплачено").build())
                .build();
    }
}

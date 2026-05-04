package com.hunt.otziv.p_products.status;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCompanyStatusServiceTest {

    @Mock
    private CompanyService companyService;

    @Mock
    private CompanyStatusService companyStatusService;

    @Test
    void archiveMovesCompanyToStopWhenNoOtherActiveOrders() {
        OrderCompanyStatusService service = service();
        Company company = company("В работе");
        Order current = order(1L, "Архив", company);
        Order paid = order(2L, "Оплачено", company);
        company.setOrderList(new LinkedHashSet<>(java.util.List.of(current, paid)));
        CompanyStatus stop = status("На стопе");

        when(companyStatusService.getStatusByTitle("На стопе")).thenReturn(stop);

        service.autoManageCompanyStatus(current, "Архив");

        assertEquals("На стопе", company.getStatus().getTitle());
        verify(companyService).save(company);
    }

    @Test
    void archiveDoesNotMoveCompanyWhenAnotherActiveOrderExists() {
        OrderCompanyStatusService service = service();
        Company company = company("В работе");
        Order current = order(1L, "Архив", company);
        Order active = order(2L, "Публикация", company);
        company.setOrderList(new LinkedHashSet<>(java.util.List.of(current, active)));

        service.autoManageCompanyStatus(current, "Архив");

        assertEquals("В работе", company.getStatus().getTitle());
        verify(companyStatusService, never()).getStatusByTitle("На стопе");
        verify(companyService, never()).save(company);
    }

    @Test
    void activeStatusMovesStoppedCompanyToWorkWhenNoOtherActiveOrders() {
        OrderCompanyStatusService service = service();
        Company company = company("На стопе");
        Order current = order(1L, "Публикация", company);
        Order archived = order(2L, "Архив", company);
        company.setOrderList(new LinkedHashSet<>(java.util.List.of(current, archived)));
        CompanyStatus work = status("В работе");

        when(companyStatusService.getStatusByTitle("В работе")).thenReturn(work);

        service.autoManageCompanyStatus(current, "Публикация");

        assertEquals("В работе", company.getStatus().getTitle());
        verify(companyService).save(company);
    }

    @Test
    void activeStatusKeepsStoppedCompanyWhenAnotherActiveOrderExists() {
        OrderCompanyStatusService service = service();
        Company company = company("На стопе");
        Order current = order(1L, "Публикация", company);
        Order active = order(2L, "Коррекция", company);
        company.setOrderList(new LinkedHashSet<>(java.util.List.of(current, active)));

        service.autoManageCompanyStatus(current, "Публикация");

        assertEquals("На стопе", company.getStatus().getTitle());
        verify(companyStatusService, never()).getStatusByTitle("В работе");
        verify(companyService, never()).save(company);
    }

    @Test
    void inactiveOrderStatusDoesNotTouchCompany() {
        OrderCompanyStatusService service = service();
        Company company = company("В работе");
        Order current = order(1L, "Оплачено", company);
        company.setOrderList(new LinkedHashSet<>(java.util.List.of(current)));

        service.autoManageCompanyStatus(current, "Оплачено");

        assertEquals("В работе", company.getStatus().getTitle());
        verify(companyStatusService, never()).getStatusByTitle("В работе");
        verify(companyStatusService, never()).getStatusByTitle("На стопе");
        verify(companyService, never()).save(company);
    }

    private OrderCompanyStatusService service() {
        return new OrderCompanyStatusService(companyService, companyStatusService);
    }

    private Company company(String statusTitle) {
        Company company = new Company();
        company.setId(100L);
        company.setStatus(status(statusTitle));
        return company;
    }

    private Order order(Long id, String statusTitle, Company company) {
        OrderStatus orderStatus = new OrderStatus();
        orderStatus.setTitle(statusTitle);

        Order order = new Order();
        order.setId(id);
        order.setStatus(orderStatus);
        order.setCompany(company);
        return order;
    }

    private CompanyStatus status(String title) {
        CompanyStatus status = new CompanyStatus();
        status.setTitle(title);
        return status;
    }
}

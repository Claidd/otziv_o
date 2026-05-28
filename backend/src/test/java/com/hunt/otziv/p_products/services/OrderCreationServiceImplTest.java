package com.hunt.otziv.p_products.services;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderCreationServiceImplTest {

    @Test
    void repeatOrderKeepsClientTextWaitingModeFromSourceHistory() {
        OrderCreationServiceImpl service = new OrderCreationServiceImpl(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null
        );
        Order sourceOrder = sourceOrder();
        sourceOrder.setWaitingForClient(false);
        sourceOrder.setClientTextExpected(true);

        OrderDTO repeatOrder = service.convertToOrderDTOToRepeat(sourceOrder);

        assertTrue(repeatOrder.isWaitingForClient());
        assertTrue(repeatOrder.isClientTextExpected());
    }

    private Order sourceOrder() {
        User user = new User();
        Worker worker = new Worker();
        worker.setId(7L);
        worker.setUser(user);

        Manager manager = new Manager();
        manager.setId(8L);
        manager.setUser(user);
        manager.setPayText("pay");
        manager.setClientId("client");

        Filial filial = new Filial();
        filial.setId(9L);
        filial.setTitle("Филиал");

        Company company = new Company();
        company.setId(10L);
        company.setTitle("Компания");
        company.setCommentsCompany("comment");
        company.setManager(manager);
        company.setWorkers(Set.of(worker));
        company.setFilial(Set.of(filial));

        Order order = new Order();
        order.setId(11L);
        order.setAmount(3);
        order.setWorker(worker);
        order.setManager(manager);
        order.setCompany(company);
        order.setFilial(filial);
        return order;
    }
}

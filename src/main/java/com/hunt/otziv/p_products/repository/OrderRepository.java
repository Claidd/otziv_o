package com.hunt.otziv.p_products.repository;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.repository.CrudRepository;
import org.springframework.lang.NonNullApi;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends CrudRepository<Order, Long> {
    @Override
    List<Order> findAll();
    List<Order> findAllByCompanyTitleContainingIgnoreCaseOrCompanyTelephoneContainingIgnoreCase(String keyword1, String keyword2); //взять все заказы по названию и телефону компании

    List<Order> findAllByManager(Manager manager);
    List<Order> findAllByWorker(Worker worker);
    List<Order> findAllByManagerAndCompanyTitleContainingIgnoreCaseOrManagerAndCompanyTelephoneContainingIgnoreCase(
            Manager manager, String keyword1, Manager manager2, String keyword2); //взять все заказы по названию и телефону компании определенного менеджера
    List<Order> findAllByWorkerAndCompanyTitleContainingIgnoreCaseOrWorkerAndCompanyTelephoneContainingIgnoreCase(
            Worker worker, String keyword1, Worker worker2, String keyword2); //взять все заказы по названию и телефону компании определенного менеджера

//    List<Order> findAllByCompanyWhereTitleContainingIgnoreCaseOrTelephoneContainingIgnoreCase(String keyword, String keyword2);
    //взять все заказы по названию и телефону компании
}

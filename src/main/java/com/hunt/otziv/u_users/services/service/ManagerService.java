package com.hunt.otziv.u_users.services.service;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;

import java.util.List;
import java.util.Set;

public interface ManagerService {

    // Взять менеджера по id его юзера
    Manager getManagerById (Long id);

    // взять из БД всех менеджеров
    List<Manager> getAllManagers();

    // Удалить определенного менеджера по юзеру
    void deleteManager(User user);

    // взять менеджера по id го юзера
    Manager getManagerByUserId(Long id);

    // сохранить нового менеджера
    void saveNewManager(User user);


}

package com.hunt.otziv.u_users.services.service;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;

import java.util.List;

public interface ManagerService {
    Manager getManagerById (Long id);
    List<Manager> getAllManagers();
    void deleteManager(User user);
    Manager getManagerByUserId(Long id);
    void saveNewManager(User user);
}

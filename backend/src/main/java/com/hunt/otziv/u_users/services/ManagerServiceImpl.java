package com.hunt.otziv.u_users.services;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.services.service.ManagerService;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class ManagerServiceImpl implements ManagerService {

    private final ManagerRepository managerRepository;

    public ManagerServiceImpl(ManagerRepository managerRepository) {
        this.managerRepository = managerRepository;
    }

    @Override
    public Manager getManagerById(Long id) { // Взять менеджера по Id
        return managerRepository.findByIdWithUser(id).orElse(null);
    } // Взять менеджера по Id

    @Override
    public Manager getManagerByUserId(Long userId) { // Взять менеджера по Id юзера
        return managerRepository.findByUserId(userId).orElse(null);
    } // Взять менеджера по Id юзера

    @Override
    public List<Manager> getManagersByUserIdsForAdminList(Set<Long> userIds) {
        return userIds == null || userIds.isEmpty()
                ? List.of()
                : managerRepository.findAllByUserIdsForAdminList(userIds);
    }
    
    @Override
    public List<Manager> getAllManagers() {
        return managerRepository.findAllWithUserAndImage();
    } // Взять всех менеджеров

    @Override
    public void deleteManager(User user) { // Удалить менеджера
        // Manager is a durable identity referenced by companies, orders and
        // accounting history. Eligibility is removed by role/profile state;
        // the identity row must not be physically deleted.
        log.info("Сохранили запись менеджера при смене роли пользователя {}",
                user == null ? null : user.getId());
    } // Удалить менеджера

    @Override
    public void saveNewManager(User user) { // Сохранить менеджера
        if (managerRepository.findByUserId(user.getId()).isPresent()){
            log.info("Не добавили менеджера так как уже в списке");
        }
        else {
            log.info("Начали добавлять менеджера так как уже в списке");
            Manager manager = new Manager();
            manager.setUser(user);
            managerRepository.save(manager);
            log.info("Добавили менеджера так как уже в списке");
        }
    } // Сохранить менеджера

    @Override
    public Manager save(Manager manager) {
        return managerRepository.save(manager);
    }

    @Override
    public List<Manager> getAllManagersToOwner(List<Manager> managers) {
        return managerRepository.findAllManagersToOwner(managers);
    }

    @Override
    public List<Manager> findAllManagersWorkers(List<Manager> managers) {
        return managerRepository.findAllManagersWorkers(managers);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findUserIdsByManagerIds(Set<Long> managerIds) {
        if (managerIds == null || managerIds.isEmpty()) {
            return Collections.emptyList();
        }
        return managerRepository.findUserIdsByManagerIds(managerIds);
    }


}

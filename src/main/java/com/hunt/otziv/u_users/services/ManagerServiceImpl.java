package com.hunt.otziv.u_users.services;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.services.service.ManagerService;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    public Manager getManagerById(Long id) {
        return managerRepository.findById(id).orElse(null);
//                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + id));
    }

    @Override
    public Manager getManagerByUserId(Long id) {
        return managerRepository.findByUserId(id).orElse(null);
    }

//    @Override
//    public Manager getManagerById(Long id) {
//        return managerRepository.findById(id).orElse(null);
//    }

    @Override
    public Set<Manager> getAllManagers() {
        return managerRepository.findAll();
    }

    @Override
    public void deleteManager(User user) {
        log.info("Вошли в проверку есть ли такой менеджер при смене роли");
        Manager manager = getManagerByUserId(user.getId());
        log.info("Достали менеджера");
        if (manager != null){
            managerRepository.delete(manager);
            log.info("Удалили менеджера");
        }
        else {
            log.info("Не удалили менеджера так как такого нет в списке");
        }
    }

    @Override
    public void saveNewManager(User user) {
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
    }
}

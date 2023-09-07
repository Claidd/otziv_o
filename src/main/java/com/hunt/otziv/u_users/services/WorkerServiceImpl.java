package com.hunt.otziv.u_users.services;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.WorkerRepository;
import com.hunt.otziv.u_users.services.service.WorkerService;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class WorkerServiceImpl implements WorkerService {

    private final WorkerRepository workerRepository;

    public WorkerServiceImpl(WorkerRepository workerRepository) {
        this.workerRepository = workerRepository;
    }

    @Override
    public Worker getWorkerById(Long id) {
        return workerRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + id));
    }

    @Override
    public Worker getWorkerByUserId(Long id) {
        return workerRepository.findByUserId(id).orElse(null);
    }

    @Override
    public Set<Worker> getAllWorkers() {
        return workerRepository.findAll();
    }

    @Override
    public Worker getWorkerByUserIdToDelete(Long id) {
        return workerRepository.findById(id).orElse(null);
    }

    @Override
    public void deleteWorker(User user) {
        log.info("Вошли в проверку есть ли такой работник при смене роли");
        Worker worker = getWorkerByUserIdToDelete(user.getId());
        log.info("Достали работника");
        if (worker != null){
            workerRepository.delete(worker);
            log.info("Удалили работника");
        }
        else {
            log.info("Не удалили работника так как такого нет в списке");
        }
    }

    @Override
    public void saveNewWorker(User user) {
        if (workerRepository.findByUserId(user.getId()).isPresent()){
            log.info("Не добавили работника так как уже в списке");
        }
        else {
            log.info("Начали добавлять работника так как нет в списке");
            Worker worker = new Worker();
            worker.setUser(user);
            workerRepository.save(worker);
            log.info("Добавили работника так как нет в списке");
        }
    }
}

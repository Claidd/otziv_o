package com.hunt.otziv.u_users.services;

import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.WorkerRepository;
import com.hunt.otziv.u_users.services.service.WorkerService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkerServiceImpl implements WorkerService {

    private final WorkerRepository workerRepository;

    @Override
    public Worker getWorkerById(Long workerId) {
        return workerRepository.findById(workerId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + workerId));
    }

    @Override
    public Worker getWorkerByUserId(Long id) {
        return workerRepository.findByUserIdWithUserAndImage(id).orElse(null);
    }

    @Override
    public List<Worker> getAllWorkers() {
        return workerRepository.findAllWithUserAndImage();
    }

    public List<Worker> getAllWorkersToManager(Manager manager) {
        return workerRepository.findAllToManagerWorkers(manager.getUser().getWorkers());
    }

    @Override
    public Set<Worker> getAllWorkersToManagerList(List<Manager> managerList) {
        return workerRepository.findAllToManagerList(managerList);
    }

    @Override
    public void save(Worker worker) {
        workerRepository.save(worker);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findUserIdsByManagerIds(Set<Long> managerIds) {
        if (managerIds == null || managerIds.isEmpty()) {
            return Collections.emptyList();
        }
        return workerRepository.findUserIdsByManagerIds(managerIds);
    }

    public Set<WorkerDTO> getAllWorkersByManagerId(Set<Worker> workers) {
        return workers.stream().map(this::toDTO).collect(Collectors.toSet());
    }

    @Override
    public Worker getWorkerByUsername(String login) {
        return workerRepository.findByUsername(login);
    }

    private WorkerDTO toDTO(Worker worker) {
        WorkerDTO workerDTO = new WorkerDTO();
        workerDTO.setWorkerId(worker.getId());
        workerDTO.setUser(worker.getUser());
        return workerDTO;
    }

    @Override
    public Worker getWorkerByUserIdToDelete(Long userId) {
        return workerRepository.findById(userId).orElse(null);
    }

    @Override
    public void deleteWorker(User user) {
        log.info("Вошли в проверку есть ли такой работник при смене роли");
        Worker worker = workerRepository.findByUserId(user.getId()).orElse(null);
        log.info("Достали работника");

        if (worker != null) {
            workerRepository.delete(worker);
            log.info("Удалили работника");
        } else {
            log.info("Не удалили работника так как такого нет в списке");
        }
    }

    @Override
    public void saveNewWorker(User user) {
        if (workerRepository.findByUserId(user.getId()).isPresent()) {
            log.info("Не добавили работника так как уже в списке");
        } else {
            log.info("Начали добавлять работника так как нет в списке");
            Worker worker = new Worker();
            worker.setUser(user);
            workerRepository.save(worker);
            log.info("Добавили работника так как нет в списке");
        }
    }
}

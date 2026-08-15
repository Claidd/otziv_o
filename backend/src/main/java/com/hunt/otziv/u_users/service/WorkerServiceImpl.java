package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.WorkerRepository;
import com.hunt.otziv.u_users.service.WorkerService;
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
        if (manager == null || manager.getId() == null) {
            return Collections.emptyList();
        }
        return workerRepository.findAllToManager(manager);
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
    @Transactional(readOnly = true)
    public Set<WorkerDTO> getAllWorkersByManagerId(Long managerId) {
        if (managerId == null) {
            return Collections.emptySet();
        }
        return workerRepository.findAllByManagerIdWithUser(managerId).stream()
                .map(this::toDTO)
                .collect(Collectors.toSet());
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
        // Worker is a durable professional identity referenced by historical
        // orders. Physical deletion can cascade into order history. A role
        // change therefore only removes ROLE_WORKER and disables the routing
        // profile; active worker queries already filter by that role.
        log.info("Сохранили запись специалиста при смене роли пользователя {}",
                user == null ? null : user.getId());
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

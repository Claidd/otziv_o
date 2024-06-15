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

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkerServiceImpl implements WorkerService {

    private final WorkerRepository workerRepository;




    @Override
    public Worker getWorkerById(Long workerId) {  // Взять работника по Id
        return workerRepository.findById(workerId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + workerId));
    } // Взять работника по Id

    @Override
    public Worker getWorkerByUserId(Long id) { // Взять работника по Id юзера
        return workerRepository.findByUserId(id).orElse(null);
    } // Взять работника по Id юзера

    @Override
    public List<Worker> getAllWorkers() {
        return workerRepository.findAllWorkers();
    } // Взять всех работников

    public List<Worker> getAllWorkersToManager(Manager manager) {
        return workerRepository.findAllToManagerWorkers(manager.getUser().getWorkers());
//        return workerRepository.findAllToManagerWorkers(manager.getUser().getWorkers().stream().map(Worker::getId).collect(Collectors.toList()));
    } // Взять всех работников

    @Override
    public Set<Worker> getAllWorkersToManagerList(List<Manager> managerList) {
        return workerRepository.findAllToManagerList(managerList);
    }

    public Set<WorkerDTO> getAllWorkersByManagerId(Set<Worker> workers){ // Взять всех работников по Id менеджера
        return workers.stream().map(this::toDTO).collect(Collectors.toSet());
    } // Взять всех работников по Id менеджера

    @Override
    public Worker getWorkerByUsername(String login) {
        return workerRepository.findByUsername(login);
    }

    private WorkerDTO toDTO(Worker worker){ // Перевод работника в дто
        WorkerDTO workerDTO = new WorkerDTO();
        workerDTO.setWorkerId(worker.getId());
        workerDTO.setUser(worker.getUser());
        return workerDTO;
    } // Перевод работника в дто

    @Override
    public Worker getWorkerByUserIdToDelete(Long userId) { // Взять работника по Id  удалению
        return workerRepository.findById(userId).orElse(null);
    } // Взять работника по Id  удалению

    @Override
    public void deleteWorker(User user) { // Удаление работника
        log.info("Вошли в проверку есть ли такой работник при смене роли");
        Worker worker = workerRepository.findByUserId(user.getId()).orElse(null);
        log.info("Достали работника");
        if (worker != null){
            workerRepository.delete(worker);
            log.info("Удалили работника");
        }
        else {
            log.info("Не удалили работника так как такого нет в списке");
        }
    } // Удаление работника

    @Override
    public void saveNewWorker(User user) { // Сохранение нового работника
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
    } // Сохранение нового работника
}

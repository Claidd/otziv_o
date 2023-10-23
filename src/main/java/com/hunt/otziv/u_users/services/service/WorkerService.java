package com.hunt.otziv.u_users.services.service;

import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;

import java.security.Principal;
import java.util.List;
import java.util.Set;

public interface WorkerService {
    Worker getWorkerById (Long id);
    Set<Worker> getAllWorkers();

    Worker getWorkerByUserIdToDelete(Long id);

    void deleteWorker(User user);

    void saveNewWorker(User user);

    Worker getWorkerByUserId(Long id);

//    List<WorkerDTO> getListAllWorkersByManagerId(Manager manager);

    Set<WorkerDTO> getAllWorkersByManagerId(Set<Worker> workers);

    Worker getWorkerByUsername(String login);

//    List<WorkerDTO> getAllWorkersIsActiveByUser(User user);
}

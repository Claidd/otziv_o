package com.hunt.otziv.u_users.services.service;

import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;

import java.util.List;

public interface WorkerService {
    Worker getWorkerById (Long id);
    List<Worker> getAllWorkers();

    Worker getWorkerByUserIdToDelete(Long id);

    void deleteWorker(User user);

    void saveNewWorker(User user);
}

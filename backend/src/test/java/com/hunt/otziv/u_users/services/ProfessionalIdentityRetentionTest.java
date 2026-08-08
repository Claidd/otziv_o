package com.hunt.otziv.u_users.services;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.WorkerRepository;
import org.junit.jupiter.api.Test;

class ProfessionalIdentityRetentionTest {

    @Test
    void roleChangeNeverPhysicallyDeletesWorkerOrManagerHistoryKeys() {
        WorkerRepository workerRepository = mock(WorkerRepository.class);
        ManagerRepository managerRepository = mock(ManagerRepository.class);
        WorkerServiceImpl workerService = new WorkerServiceImpl(workerRepository);
        ManagerServiceImpl managerService = new ManagerServiceImpl(managerRepository);
        User user = new User();
        user.setId(77L);

        workerService.deleteWorker(user);
        managerService.deleteManager(user);

        verify(workerRepository, never()).delete(org.mockito.ArgumentMatchers.any(Worker.class));
        verify(managerRepository, never()).delete(org.mockito.ArgumentMatchers.any(Manager.class));
    }
}

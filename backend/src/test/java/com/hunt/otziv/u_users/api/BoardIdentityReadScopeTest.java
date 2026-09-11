package com.hunt.otziv.u_users.api;

import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.WorkerRepository;
import com.hunt.otziv.u_users.service.UserServiceImpl;
import com.hunt.otziv.u_users.service.ManagerServiceImpl;
import com.hunt.otziv.u_users.service.WorkerServiceImpl;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BoardIdentityReadScopeTest {
    @Mock UserRepository users;
    @Mock ManagerRepository managers;
    @Mock WorkerRepository workers;
    @InjectMocks UserServiceImpl userService;

    @Test void deactivationAndDifferentUsersAreVisibleOnTheNextAssembly() {
        User active = new User(); active.setActive(true);
        User revoked = new User(); revoked.setActive(false);
        User another = new User(); another.setId(2L);
        when(users.findByUsername("first")).thenReturn(Optional.of(active), Optional.of(revoked));
        when(users.findByUsername("second")).thenReturn(Optional.of(another));
        try (var scope = BoardIdentityReadScope.open()) {
            assertThat(userService.findByUserName("first")).contains(active);
            assertThat(userService.findByUserName("first")).contains(active);
            assertThat(userService.findByUserName("second")).contains(another);
        }
        try (var scope = BoardIdentityReadScope.open()) {
            assertThat(userService.findByUserName("first").orElseThrow().isActive()).isFalse();
        }
        verify(users, times(2)).findByUsername("first");
    }

    @Test void writeTransactionsReadFreshAndDiscardEarlierValues() {
        User first = new User(); first.setActive(true);
        User revoked = new User(); revoked.setActive(false);
        when(users.findByUsername("first")).thenReturn(Optional.of(first), Optional.of(revoked));
        try (var scope = BoardIdentityReadScope.open()) {
            assertThat(userService.findByUserName("first")).contains(first);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            try {
                assertThat(userService.findByUserName("first")).contains(revoked);
                assertThat(userService.findByUserName("first")).contains(revoked);
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            }
            assertThat(userService.findByUserName("first")).contains(revoked);
        }
        verify(users, times(4)).findByUsername("first");
    }

    @Test void exceptionalExitAndAnotherThreadDoNotReuseAnEarlierIdentity() throws Exception {
        User old = new User(); old.setActive(true);
        User current = new User(); current.setActive(false);
        AtomicReference<User> source = new AtomicReference<>(old);
        when(users.findByUsername("first")).thenAnswer(ignored -> Optional.of(source.get()));
        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> {
                try (var scope = BoardIdentityReadScope.open()) {
                    assertThat(userService.findByUserName("first")).contains(old);
                    source.set(current);
                    assertThat(executor.submit(() -> userService.findByUserName("first")).get()).contains(current);
                    throw new IllegalStateException("assembly failure");
                }
            }).isInstanceOf(IllegalStateException.class);
        }
        assertThat(userService.findByUserName("first")).contains(current);
    }

    @Test void managerAndWorkerHaveDistinctKeysAndOnlyLiveWithinOneAssembly() {
        var managerService = new ManagerServiceImpl(managers);
        var workerService = new WorkerServiceImpl(workers);
        Manager manager = new Manager(); manager.setId(10L);
        Worker worker = new Worker(); worker.setId(20L);
        when(managers.findByUserId(1L)).thenReturn(Optional.of(manager), Optional.empty());
        when(workers.findByUserIdWithUserAndImage(1L)).thenReturn(Optional.of(worker), Optional.empty());
        try (var scope = BoardIdentityReadScope.open()) {
            assertThat(managerService.getManagerByUserId(1L)).isSameAs(manager);
            assertThat(workerService.getWorkerByUserId(1L)).isSameAs(worker);
            assertThat(managerService.getManagerByUserId(1L)).isSameAs(manager);
            assertThat(workerService.getWorkerByUserId(1L)).isSameAs(worker);
        }
        assertThat(managerService.getManagerByUserId(1L)).isNull();
        assertThat(workerService.getWorkerByUserId(1L)).isNull();
    }
}

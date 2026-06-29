package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.dto.WorkerCredentialPreparationResponse;
import com.hunt.otziv.worker_activity.model.WorkerCredentialPreparation;
import com.hunt.otziv.worker_activity.model.WorkerCredentialPreparationScope;
import com.hunt.otziv.worker_activity.repository.WorkerCredentialPreparationRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerCredentialPreparationServiceTest {

    @Mock
    private WorkerCredentialPreparationRepository repository;

    @Mock
    private UserService userService;

    @Mock
    private WorkerActivityService workerActivityService;

    private WorkerCredentialPreparationService service;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        service = new WorkerCredentialPreparationService(repository, userService, workerActivityService);
        authentication = new UsernamePasswordAuthenticationToken("worker", "password");
    }

    @Test
    void blockUntilReadyBlocksPlainWorkerWhenPreparationIsMissing() {
        User user = new User();
        user.setId(77L);
        when(workerActivityService.isPlainWorker(authentication)).thenReturn(true);
        when(userService.findByUserNameWithAssignments("worker")).thenReturn(Optional.of(user));
        when(repository.findByWorkerUserIdAndScope(77L, WorkerCredentialPreparationScope.PUBLISH))
                .thenReturn(Optional.empty());

        Optional<WorkerCredentialPreparationService.CredentialPreparationBlock> block = service.blockUntilReady(
                authentication,
                WorkerCredentialPreparationScope.PUBLISH,
                15L,
                100L,
                150
        );

        assertTrue(block.isPresent());
        assertEquals("Сначала скопируйте логин и пароль аккаунта.", block.get().message());
        assertEquals(150, block.get().remainingSeconds());
    }

    @Test
    void activeReturnsReadyPreparationWhenWaitPassed() {
        User user = new User();
        user.setId(77L);
        WorkerCredentialPreparation preparation = preparation(LocalDateTime.now().minusSeconds(180));
        when(workerActivityService.isPlainWorker(authentication)).thenReturn(true);
        when(userService.findByUserNameWithAssignments("worker")).thenReturn(Optional.of(user));
        when(repository.findByWorkerUserIdAndScope(77L, WorkerCredentialPreparationScope.PUBLISH))
                .thenReturn(Optional.of(preparation));

        WorkerCredentialPreparationResponse response = service.active(authentication, WorkerCredentialPreparationScope.PUBLISH);

        assertEquals(15L, response.reviewId());
        assertTrue(response.loginCopied());
        assertTrue(response.passwordCopied());
        assertTrue(response.ready());
        assertEquals(0, response.remainingSeconds());
        assertEquals(150, response.waitSeconds());
    }

    @Test
    void activeReturnsRemainingSecondsWhenWaitIsNotPassed() {
        User user = new User();
        user.setId(77L);
        WorkerCredentialPreparation preparation = preparation(LocalDateTime.now().minusSeconds(30));
        when(workerActivityService.isPlainWorker(authentication)).thenReturn(true);
        when(userService.findByUserNameWithAssignments("worker")).thenReturn(Optional.of(user));
        when(repository.findByWorkerUserIdAndScope(77L, WorkerCredentialPreparationScope.PUBLISH))
                .thenReturn(Optional.of(preparation));

        WorkerCredentialPreparationResponse response = service.active(authentication, WorkerCredentialPreparationScope.PUBLISH);

        assertTrue(response.loginCopied());
        assertTrue(response.passwordCopied());
        assertEquals(false, response.ready());
        assertTrue(response.remainingSeconds() > 0);
        assertTrue(response.remainingSeconds() <= 121);
    }

    private WorkerCredentialPreparation preparation(LocalDateTime copiedAt) {
        WorkerCredentialPreparation preparation = new WorkerCredentialPreparation();
        preparation.setWorkerUserId(77L);
        preparation.setScope(WorkerCredentialPreparationScope.PUBLISH);
        preparation.setReviewId(15L);
        preparation.setBotId(100L);
        preparation.setLoginCopiedAt(copiedAt);
        preparation.setPasswordCopiedAt(copiedAt);
        preparation.setUpdatedAt(copiedAt);
        return preparation;
    }
}

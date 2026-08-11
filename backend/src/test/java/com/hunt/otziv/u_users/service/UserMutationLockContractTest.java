package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.dto.ChangeKeycloakPasswordRequest;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.MarketologDTO;
import com.hunt.otziv.u_users.dto.OperatorDTO;
import com.hunt.otziv.u_users.dto.RegistrationUserDTO;
import com.hunt.otziv.u_users.dto.UpdateKeycloakUserRequest;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.repository.UserRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UserMutationLockContractTest {

    @Test
    void repositoryExposesWriteLocksForBothMutationKeys() throws Exception {
        assertPessimisticWriteLock(UserRepository.class.getMethod("lockById", Long.class));
        assertPessimisticWriteLock(UserRepository.class.getMethod("lockByUsername", String.class));
    }

    @Test
    void authEpochMutationEntryPointsKeepWriteTransactions() throws Exception {
        assertWriteTransaction(KeycloakUserProvisioningService.class.getMethod(
                "updateUser",
                Long.class,
                UpdateKeycloakUserRequest.class
        ));
        assertWriteTransaction(KeycloakUserProvisioningService.class.getMethod("deleteUser", Long.class));
        assertWriteTransaction(KeycloakUserProvisioningService.class.getMethod(
                "changePassword",
                Long.class,
                ChangeKeycloakPasswordRequest.class
        ));
        assertWriteTransaction(UserServiceImpl.class.getMethod(
                "updateProfile",
                RegistrationUserDTO.class,
                String.class,
                OperatorDTO.class,
                ManagerDTO.class,
                WorkerDTO.class,
                MarketologDTO.class,
                MultipartFile.class
        ));
    }

    private void assertPessimisticWriteLock(Method method) {
        Lock lock = method.getAnnotation(Lock.class);
        assertNotNull(lock);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
    }

    private void assertWriteTransaction(Method method) {
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional);
        assertFalse(transactional.readOnly());
    }
}

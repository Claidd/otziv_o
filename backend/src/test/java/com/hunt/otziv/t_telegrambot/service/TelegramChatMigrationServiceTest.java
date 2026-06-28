package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramChatMigrationServiceTest {

    @Test
    void migrateChatIdUpdatesCompaniesAndWorkerGroups() {
        CompanyRepository companyRepository = mock(CompanyRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        TelegramChatMigrationService service = new TelegramChatMigrationService(companyRepository, userRepository);
        when(companyRepository.updateTelegramGroupChatId(-10L, -10010L)).thenReturn(2);
        when(userRepository.updateWorkerTelegramGroupChatId(-10L, -10010L)).thenReturn(1);

        TelegramChatMigrationResult result = service.migrateChatId(-10L, -10010L);

        assertEquals(2, result.companiesUpdated());
        assertEquals(1, result.workerGroupsUpdated());
        assertEquals(3, result.totalUpdated());
        assertTrue(result.updated());
        verify(companyRepository).updateTelegramGroupChatId(-10L, -10010L);
        verify(userRepository).updateWorkerTelegramGroupChatId(-10L, -10010L);
    }
}

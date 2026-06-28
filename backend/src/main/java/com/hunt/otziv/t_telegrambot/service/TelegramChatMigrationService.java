package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class TelegramChatMigrationService {

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;

    @Transactional
    public TelegramChatMigrationResult migrateChatId(Long oldChatId, Long newChatId) {
        if (oldChatId == null || newChatId == null || oldChatId.equals(newChatId)) {
            return new TelegramChatMigrationResult(oldChatId, newChatId, 0, 0);
        }

        int companiesUpdated = companyRepository.updateTelegramGroupChatId(oldChatId, newChatId);
        int workerGroupsUpdated = userRepository.updateWorkerTelegramGroupChatId(oldChatId, newChatId);
        TelegramChatMigrationResult result = new TelegramChatMigrationResult(
                oldChatId,
                newChatId,
                companiesUpdated,
                workerGroupsUpdated
        );

        if (result.updated()) {
            log.info(
                    "Telegram chat migration applied oldChatId={} newChatId={} companiesUpdated={} workerGroupsUpdated={}",
                    oldChatId,
                    newChatId,
                    companiesUpdated,
                    workerGroupsUpdated
            );
        } else {
            log.warn(
                    "Telegram chat migration returned no DB changes oldChatId={} newChatId={}",
                    oldChatId,
                    newChatId
            );
        }

        return result;
    }
}

package com.hunt.otziv.performers.service;

import com.hunt.otziv.performers.repository.PerformerProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class PerformerRegistrationExpiryService {

    private final PerformerProfileRepository performerProfileRepository;

    @Scheduled(fixedDelayString = "${performer.registration.expiry-scan-ms:900000}")
    @Transactional
    public void expirePendingRegistrations() {
        int expired = performerProfileRepository.expirePendingRegistrations(LocalDateTime.now());
        if (expired > 0) {
            log.info("Expired {} unverified performer registration(s)", expired);
        }
    }
}

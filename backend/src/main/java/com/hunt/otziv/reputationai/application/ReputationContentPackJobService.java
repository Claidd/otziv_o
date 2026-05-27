package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.reputationai.api.dto.ReputationContentPackRequest;
import com.hunt.otziv.reputationai.config.ContentPackProfile;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ReputationContentPackJobStatus;
import com.hunt.otziv.reputationai.infrastructure.ai.AiProviderRouter;
import com.hunt.otziv.reputationai.persistence.ContentPackJobStatus;
import com.hunt.otziv.reputationai.persistence.ReputationContentPackJobEntity;
import com.hunt.otziv.reputationai.persistence.ReputationContentPackJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReputationContentPackJobService {

    private final ReputationContentPackJobRepository jobRepository;
    private final CompanyRepository companyRepository;
    private final ReputationContentPackService contentPackService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final TaskExecutor reputationDeepReportExecutor;
    private final AiProviderRouter aiProviderRouter;
    private final ReputationAiProperties properties;

    public ReputationContentPackJobStatus start(Long companyId, ReputationContentPackRequest request) {
        ReputationContentPackRequest safeRequest = safeRequest(request);
        Long jobId = transactionTemplate.execute(status -> {
            Company company = companyRepository.findByIdForReputationAi(companyId)
                    .orElseThrow(() -> new UsernameNotFoundException(
                            String.format("Компания '%d' не найдена", companyId)
                    ));
            ReputationContentPackJobEntity entity = jobRepository.findByCompanyId(companyId)
                    .orElseGet(ReputationContentPackJobEntity::new);

            if (entity.getId() != null && isActive(entity)) {
                return entity.getId();
            }

            entity.setCompanyId(company.getId());
            entity.setCompanyTitle(company.getTitle());
            entity.setStatus(ContentPackJobStatus.QUEUED);
            entity.setProvider(activeAiProvider());
            entity.setModel(contentPackModel(safeRequest));
            entity.setRequestJson(writeJson(safeRequest));
            entity.setErrorMessage(null);
            entity.setStartedAt(null);
            entity.setCompletedAt(null);
            ReputationContentPackJobEntity saved = jobRepository.save(entity);
            return saved.getId();
        });

        reputationDeepReportExecutor.execute(() -> run(jobId));
        return findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Не удалось создать задачу AI-пакета"));
    }

    public Optional<ReputationContentPackJobStatus> findLatest(Long companyId) {
        return jobRepository.findByCompanyId(companyId).map(this::toStatus);
    }

    private Optional<ReputationContentPackJobStatus> findById(Long jobId) {
        return jobRepository.findById(jobId).map(this::toStatus);
    }

    private void run(Long jobId) {
        JobRunInput input = markRunning(jobId);
        if (input == null) {
            return;
        }

        try {
            ReputationContentPack pack = contentPackService.createContentPack(input.companyId(), input.request());
            saveDone(jobId, pack);
        } catch (Exception exception) {
            saveFailed(jobId, exception);
        }
    }

    private JobRunInput markRunning(Long jobId) {
        return transactionTemplate.execute(status -> {
            ReputationContentPackJobEntity entity = jobRepository.findById(jobId).orElse(null);
            if (entity == null || entity.getStatus() != ContentPackJobStatus.QUEUED) {
                return null;
            }

            entity.setStatus(ContentPackJobStatus.RUNNING);
            entity.setStartedAt(LocalDateTime.now());
            entity.setCompletedAt(null);
            entity.setErrorMessage(null);
            jobRepository.save(entity);
            return new JobRunInput(entity.getCompanyId(), readRequest(entity.getRequestJson()));
        });
    }

    private void saveDone(Long jobId, ReputationContentPack pack) {
        transactionTemplate.executeWithoutResult(status -> {
            ReputationContentPackJobEntity entity = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Задача AI-пакета не найдена"));
            entity.setStatus(ContentPackJobStatus.DONE);
            entity.setProvider(activeAiProvider());
            entity.setModel(contentPackModel(readRequest(entity.getRequestJson())));
            entity.setPackJson(writeJson(pack));
            entity.setErrorMessage(null);
            entity.setCompletedAt(LocalDateTime.now());
            jobRepository.save(entity);
        });
    }

    private void saveFailed(Long jobId, Exception exception) {
        transactionTemplate.executeWithoutResult(status -> {
            ReputationContentPackJobEntity entity = jobRepository.findById(jobId).orElse(null);
            if (entity == null) {
                return;
            }

            entity.setStatus(ContentPackJobStatus.FAILED);
            entity.setErrorMessage(cleanError(exception));
            entity.setCompletedAt(LocalDateTime.now());
            jobRepository.save(entity);
        });
    }

    private ReputationContentPackJobStatus toStatus(ReputationContentPackJobEntity entity) {
        return new ReputationContentPackJobStatus(
                entity.getId(),
                entity.getCompanyId(),
                entity.getCompanyTitle(),
                entity.getStatus() == null ? "" : entity.getStatus().name(),
                entity.getProvider(),
                entity.getModel(),
                entity.getErrorMessage(),
                readPack(entity.getPackJson()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getStartedAt(),
                entity.getCompletedAt()
        );
    }

    private ReputationContentPackRequest safeRequest(ReputationContentPackRequest request) {
        return request == null
                ? new ReputationContentPackRequest(null, null, List.of(), List.of(), true, null, null, null, null, null, null)
                : request;
    }

    private boolean isActive(ReputationContentPackJobEntity entity) {
        if (entity.getStatus() == ContentPackJobStatus.QUEUED) {
            return true;
        }
        if (entity.getStatus() != ContentPackJobStatus.RUNNING) {
            return false;
        }

        LocalDateTime startedAt = entity.getStartedAt();
        return startedAt != null && startedAt.isAfter(LocalDateTime.now().minusMinutes(15));
    }

    private String contentPackModel(ReputationContentPackRequest request) {
        if (isYandexActive()) {
            return properties.getYandex().getModel();
        }
        ContentPackProfile profile = ContentPackProfile.fromKey(request.contentPackProfile());
        return profile == null ? "" : profile.model();
    }

    private String activeAiProvider() {
        return aiProviderRouter.activeProviderName();
    }

    private boolean isYandexActive() {
        String provider = activeAiProvider();
        return "yandex".equalsIgnoreCase(provider) || "yandexgpt".equalsIgnoreCase(provider);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось сериализовать AI-пакет", exception);
        }
    }

    private ReputationContentPackRequest readRequest(String json) {
        if (json == null || json.isBlank()) {
            return safeRequest(null);
        }
        try {
            return objectMapper.readValue(json, ReputationContentPackRequest.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось прочитать параметры AI-пакета", exception);
        }
    }

    private ReputationContentPack readPack(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ReputationContentPack.class);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private String cleanError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private record JobRunInput(Long companyId, ReputationContentPackRequest request) {
    }
}

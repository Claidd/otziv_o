package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.reputationai.api.dto.ReputationResearchRequest;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchJobStatus;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.persistence.DeepReportJobStatus;
import com.hunt.otziv.reputationai.persistence.ReputationDeepReportJobEntity;
import com.hunt.otziv.reputationai.persistence.ReputationDeepReportJobRepository;
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
public class DeepCompanyResearchJobService {

    private final ReputationDeepReportJobRepository jobRepository;
    private final CompanyRepository companyRepository;
    private final DeepCompanyResearchService deepCompanyResearchService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final TaskExecutor reputationDeepReportExecutor;

    public DeepCompanyResearchJobStatus start(Long companyId, ReputationResearchRequest request) {
        ReputationResearchRequest safeRequest = safeRequest(request);
        Long jobId = transactionTemplate.execute(status -> {
            Company company = companyRepository.findByIdForReputationAi(companyId)
                    .orElseThrow(() -> new UsernameNotFoundException(
                            String.format("Компания '%d' не найдена", companyId)
                    ));
            ReputationDeepReportJobEntity entity = jobRepository.findByCompanyId(companyId)
                    .orElseGet(ReputationDeepReportJobEntity::new);

            if (entity.getId() != null && isActive(entity)) {
                return entity.getId();
            }

            entity.setCompanyId(company.getId());
            entity.setCompanyTitle(company.getTitle());
            entity.setStatus(DeepReportJobStatus.QUEUED);
            entity.setProvider("openai");
            entity.setModel("");
            entity.setResponseId("");
            entity.setRequestJson(writeJson(safeRequest));
            entity.setErrorMessage(null);
            entity.setStartedAt(null);
            entity.setCompletedAt(null);
            ReputationDeepReportJobEntity saved = jobRepository.save(entity);
            return saved.getId();
        });

        reputationDeepReportExecutor.execute(() -> run(jobId));
        return findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Не удалось создать задачу глубокого отчета"));
    }

    public Optional<DeepCompanyResearchJobStatus> findLatest(Long companyId) {
        return jobRepository.findByCompanyId(companyId).map(this::toStatus);
    }

    private Optional<DeepCompanyResearchJobStatus> findById(Long jobId) {
        return jobRepository.findById(jobId).map(this::toStatus);
    }

    private void run(Long jobId) {
        JobRunInput input = markRunning(jobId);
        if (input == null) {
            return;
        }

        try {
            DeepCompanyResearchReport report = deepCompanyResearchService.createReport(input.companyId(), input.request());
            saveDone(jobId, report);
        } catch (Exception exception) {
            saveFailed(jobId, exception);
        }
    }

    private JobRunInput markRunning(Long jobId) {
        return transactionTemplate.execute(status -> {
            ReputationDeepReportJobEntity entity = jobRepository.findById(jobId).orElse(null);
            if (entity == null || entity.getStatus() != DeepReportJobStatus.QUEUED) {
                return null;
            }

            entity.setStatus(DeepReportJobStatus.RUNNING);
            entity.setStartedAt(LocalDateTime.now());
            entity.setCompletedAt(null);
            entity.setErrorMessage(null);
            jobRepository.save(entity);
            return new JobRunInput(entity.getCompanyId(), readRequest(entity.getRequestJson()));
        });
    }

    private void saveDone(Long jobId, DeepCompanyResearchReport report) {
        transactionTemplate.executeWithoutResult(status -> {
            ReputationDeepReportJobEntity entity = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Задача глубокого отчета не найдена"));
            entity.setStatus(DeepReportJobStatus.DONE);
            entity.setProvider(report.provider());
            entity.setModel(report.model());
            entity.setResponseId(report.responseId());
            entity.setReportJson(writeJson(report));
            entity.setReportMarkdown(report.reportMarkdown());
            entity.setErrorMessage(null);
            entity.setCompletedAt(LocalDateTime.now());
            jobRepository.save(entity);
        });
    }

    private void saveFailed(Long jobId, Exception exception) {
        transactionTemplate.executeWithoutResult(status -> {
            ReputationDeepReportJobEntity entity = jobRepository.findById(jobId).orElse(null);
            if (entity == null) {
                return;
            }

            entity.setStatus(DeepReportJobStatus.FAILED);
            entity.setErrorMessage(cleanError(exception));
            entity.setCompletedAt(LocalDateTime.now());
            jobRepository.save(entity);
        });
    }

    private DeepCompanyResearchJobStatus toStatus(ReputationDeepReportJobEntity entity) {
        return new DeepCompanyResearchJobStatus(
                entity.getId(),
                entity.getCompanyId(),
                entity.getCompanyTitle(),
                entity.getStatus() == null ? "" : entity.getStatus().name(),
                entity.getProvider(),
                entity.getModel(),
                entity.getResponseId(),
                entity.getErrorMessage(),
                readReport(entity.getReportJson()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getStartedAt(),
                entity.getCompletedAt()
        );
    }

    private ReputationResearchRequest safeRequest(ReputationResearchRequest request) {
        return request == null
                ? new ReputationResearchRequest(null, null, List.of(), List.of(), true, null)
                : request;
    }

    private boolean isActive(ReputationDeepReportJobEntity entity) {
        if (entity.getStatus() == DeepReportJobStatus.QUEUED) {
            return true;
        }
        if (entity.getStatus() != DeepReportJobStatus.RUNNING) {
            return false;
        }

        LocalDateTime startedAt = entity.getStartedAt();
        return startedAt != null && startedAt.isAfter(LocalDateTime.now().minusMinutes(20));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось сериализовать глубокий отчет", exception);
        }
    }

    private ReputationResearchRequest readRequest(String json) {
        if (json == null || json.isBlank()) {
            return safeRequest(null);
        }
        try {
            return objectMapper.readValue(json, ReputationResearchRequest.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось прочитать параметры глубокого отчета", exception);
        }
    }

    private DeepCompanyResearchReport readReport(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, DeepCompanyResearchReport.class);
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

    private record JobRunInput(Long companyId, ReputationResearchRequest request) {
    }
}

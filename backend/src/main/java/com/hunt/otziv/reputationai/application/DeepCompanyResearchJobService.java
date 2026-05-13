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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DeepCompanyResearchJobService {
    public static final String OPERATION_FULL_REPORT = "full_report";
    public static final String OPERATION_REFRESH_SOURCES = "refresh_sources";
    public static final String OPERATION_REBUILD_TEXT = "rebuild_text";
    public static final String OPERATION_REBUILD_SECTION = "rebuild_section";

    private final ReputationDeepReportJobRepository jobRepository;
    private final CompanyRepository companyRepository;
    private final DeepCompanyResearchService deepCompanyResearchService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final TaskExecutor reputationDeepReportExecutor;

    public DeepCompanyResearchJobStatus start(Long companyId, ReputationResearchRequest request) {
        return startWithOperation(companyId, request, OPERATION_FULL_REPORT);
    }

    public DeepCompanyResearchJobStatus refreshSources(Long companyId, ReputationResearchRequest request) {
        return startWithOperation(companyId, request, OPERATION_REFRESH_SOURCES);
    }

    public DeepCompanyResearchJobStatus rebuildText(Long companyId, ReputationResearchRequest request) {
        return startWithOperation(companyId, request, OPERATION_REBUILD_TEXT);
    }

    public DeepCompanyResearchJobStatus rebuildSection(Long companyId, ReputationResearchRequest request) {
        return startWithOperation(companyId, request, OPERATION_REBUILD_SECTION);
    }

    private DeepCompanyResearchJobStatus startWithOperation(
            Long companyId,
            ReputationResearchRequest request,
            String operation
    ) {
        ReputationResearchRequest safeRequest = withOperation(safeRequest(request), operation);
        Long jobId = transactionTemplate.execute(status -> {
            Company company = companyRepository.findByIdForReputationAi(companyId)
                    .orElseThrow(() -> new UsernameNotFoundException(
                            String.format("Компания '%d' не найдена", companyId)
                    ));
            Optional<ReputationDeepReportJobEntity> activeJob = findActiveJob(companyId);
            if (activeJob.isPresent()) {
                return activeJob.get().getId();
            }

            ReputationDeepReportJobEntity entity = new ReputationDeepReportJobEntity();
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
        return jobRepository.findFirstByCompanyIdOrderByCreatedAtDesc(companyId).map(this::toStatus);
    }

    public Optional<DeepCompanyResearchJobStatus> findLatestReady(Long companyId) {
        return jobRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .map(this::toStatus)
                .filter(status -> status.report() != null)
                .findFirst();
    }

    public Optional<DeepCompanyResearchJobStatus> findByIdForCompany(Long companyId, Long jobId) {
        if (companyId == null || jobId == null) {
            return Optional.empty();
        }

        return jobRepository.findById(jobId)
                .filter(entity -> companyId.equals(entity.getCompanyId()))
                .map(this::toStatus);
    }

    public List<DeepCompanyResearchJobStatus> history(Long companyId, int limit) {
        int safeLimit = Math.max(1, Math.min(20, limit));
        return jobRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .limit(safeLimit)
                .map(this::toStatus)
                .toList();
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
            DeepCompanyResearchReport report = runReport(jobId, input);
            saveDone(jobId, report);
        } catch (Exception exception) {
            saveFailed(jobId, exception);
        }
    }

    private DeepCompanyResearchReport runReport(Long jobId, JobRunInput input) {
        String operation = operation(input.request());
        if (OPERATION_REFRESH_SOURCES.equals(operation)) {
            return deepCompanyResearchService.refreshSources(
                    input.companyId(),
                    input.request(),
                    baseReport(input.companyId(), jobId, input.request(), "обновления источников")
            );
        }
        if (OPERATION_REBUILD_TEXT.equals(operation)) {
            return deepCompanyResearchService.rebuildText(
                    input.companyId(),
                    input.request(),
                    baseReport(input.companyId(), jobId, input.request(), "пересборки текста")
            );
        }
        if (OPERATION_REBUILD_SECTION.equals(operation)) {
            return deepCompanyResearchService.rebuildSection(
                    input.companyId(),
                    input.request(),
                    baseReport(input.companyId(), jobId, input.request(), "пересборки раздела")
            );
        }
        return deepCompanyResearchService.createReport(input.companyId(), input.request());
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
                readOperation(entity.getRequestJson()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getStartedAt(),
                entity.getCompletedAt()
        );
    }

    private ReputationResearchRequest safeRequest(ReputationResearchRequest request) {
        return request == null
                ? new ReputationResearchRequest(null, null, List.of(), List.of(), true, null, null, null, null, null)
                : request;
    }

    private ReputationResearchRequest withOperation(ReputationResearchRequest request, String operation) {
        return new ReputationResearchRequest(
                request.websiteOverride(),
                request.manualDescription(),
                request.productsOrServices(),
                request.publicUrls(),
                request.includeCompanyWebsite(),
                request.deepResearchProfile(),
                operation,
                request.baseReportJobId(),
                request.sectionTitle(),
                request.sectionIndex()
        );
    }

    private String operation(ReputationResearchRequest request) {
        String operation = request == null ? null : request.deepResearchMode();
        return operation == null || operation.isBlank() ? OPERATION_FULL_REPORT : operation.trim().toLowerCase();
    }

    private String readOperation(String requestJson) {
        try {
            return operation(readRequest(requestJson));
        } catch (Exception exception) {
            return OPERATION_FULL_REPORT;
        }
    }

    private DeepCompanyResearchReport baseReport(
            Long companyId,
            Long currentJobId,
            ReputationResearchRequest request,
            String actionLabel
    ) {
        Long requestedJobId = request == null ? null : request.baseReportJobId();
        if (requestedJobId != null) {
            ReputationDeepReportJobEntity entity = jobRepository.findById(requestedJobId)
                    .orElseThrow(() -> new IllegalStateException("Базовый отчёт для " + actionLabel + " не найден."));
            if (!companyId.equals(entity.getCompanyId())) {
                throw new IllegalStateException("Базовый отчёт принадлежит другой компании.");
            }
            DeepCompanyResearchReport report = readReport(entity.getReportJson());
            if (report == null) {
                throw new IllegalStateException("В выбранной задаче нет готового отчёта для " + actionLabel + ".");
            }
            return report;
        }

        return jobRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .filter(entity -> !entity.getId().equals(currentJobId))
                .filter(entity -> entity.getStatus() == DeepReportJobStatus.DONE)
                .map(entity -> readReport(entity.getReportJson()))
                .filter(report -> report != null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Нет готового базового отчёта для " + actionLabel + "."));
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

    private Optional<ReputationDeepReportJobEntity> findActiveJob(Long companyId) {
        return jobRepository.findByCompanyIdAndStatusInOrderByCreatedAtDesc(
                        companyId,
                        List.of(DeepReportJobStatus.QUEUED, DeepReportJobStatus.RUNNING)
                )
                .stream()
                .filter(this::isActive)
                .max(Comparator.comparing(ReputationDeepReportJobEntity::getCreatedAt));
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

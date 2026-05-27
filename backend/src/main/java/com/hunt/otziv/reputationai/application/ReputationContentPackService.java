package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.api.dto.ReputationContentPackRequest;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import com.hunt.otziv.reputationai.persistence.ReputationDeepReportJobEntity;
import com.hunt.otziv.reputationai.persistence.ReputationDeepReportJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReputationContentPackService {

    private final CompanyResearchService researchService;
    private final AiReputationContentFactory aiContentFactory;
    private final ReputationDeepReportJobRepository deepReportJobRepository;
    private final ObjectMapper objectMapper;

    public ReputationContentPack createContentPack(Long companyId, ReputationContentPackRequest request) {
        ReputationContentPackRequest safeRequest = request == null
                ? new ReputationContentPackRequest(null, null, null, null, true, null, null, null, null, null, null)
                : request;
        DeepCompanyResearchReport deepReport = deepReport(companyId, safeRequest.deepReportJobId())
                .orElseThrow(() -> new IllegalStateException(
                        "Сначала соберите глубокий отчет компании: AI-пакет теперь строится по последнему успешному deep report."
                ));
        ResearchSnapshot snapshot = researchService.createSnapshot(companyId, safeRequest.toResearchRequest());
        return aiContentFactory.create(snapshot, deepReport, safeRequest)
                .orElseThrow(() -> new IllegalStateException(
                        "AI-провайдер не подготовил AI-пакет. Проверьте ключ, модель и лимиты YandexGPT."
                ));
    }

    private Optional<DeepCompanyResearchReport> deepReport(Long companyId, Long reportJobId) {
        if (reportJobId != null && reportJobId > 0) {
            return deepReportJobRepository.findById(reportJobId)
                    .filter(entity -> entity.getCompanyId().equals(companyId))
                    .map(ReputationDeepReportJobEntity::getReportJson)
                    .filter(json -> json != null && !json.isBlank())
                    .map(this::readDeepReport);
        }

        return deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .map(ReputationDeepReportJobEntity::getReportJson)
                .filter(json -> json != null && !json.isBlank())
                .findFirst()
                .map(this::readDeepReport);
    }

    private DeepCompanyResearchReport readDeepReport(String json) {
        try {
            return objectMapper.readValue(json, DeepCompanyResearchReport.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось прочитать сохраненный глубокий отчет компании", exception);
        }
    }
}

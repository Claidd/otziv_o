package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.worker_activity.model.WorkerRiskExplanationQuality;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerRiskDailyReportSectionServiceTest {

    @Mock
    private WorkerRiskIncidentRepository repository;
    @Mock
    private ManagerAuditSourceOwners sourceOwners;

    @Test
    void reportShowsShortOrIncompleteAnswersAsConcreteCases() {
        LocalDate date = LocalDate.of(2026, 7, 25);
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setId(77L);
        incident.setAssignedManagerId(10L);
        incident.setStatus(WorkerRiskIncidentStatus.OPEN);
        incident.setCreatedAt(LocalDateTime.of(2026, 7, 25, 12, 0));
        incident.setTitle("Почему карточка закрыта без выполнения?");
        incident.setWorkerExplanation("Проверим");
        incident.setExplanationQuality(WorkerRiskExplanationQuality.PARTIAL);
        incident.setExplanationQualityReason("Ответ слишком общий и не объясняет выполненные действия");
        incident.setResponseDueAt(LocalDateTime.of(2026, 7, 25, 15, 0));

        when(repository.findPerformanceIncidentsByAssignedManagerId(
                eq(List.of(10L)),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(WorkerRiskIncidentStatus.OPEN)
        )).thenReturn(List.of(incident));

        String report = new ManagerRiskDailyReportSectionService(repository, sourceOwners).format(10L, date);

        assertTrue(report.contains("Пояснения: по существу 0 · неполные 1"));
        assertTrue(report.contains("«Проверим»"));
        assertTrue(report.contains("Ответ слишком общий"));
        assertTrue(report.contains("Примеры для разбора"));
        assertTrue(report.contains("Не принимайте «Хорошо» и «Проверим»"));
        assertTrue(report.contains("пояснение специалиста «Проверим»"));
        assertTrue(report.contains("Решение менеджера ещё не принято."));
    }

    @Test
    void caseDecidedByTheOwnerIsNotTheManagersRemark() {
        // 10.09: incident 194 — specialist Виктория Ц. explained, the owner (user 9) closed it.
        LocalDate date = LocalDate.of(2026, 9, 10);
        WorkerRiskIncident ownerDecided = incident(194L, 26699L, "Виктория Ц.", "Увод в блок. Буду внимательнее");
        ownerDecided.setResolvedAt(LocalDateTime.of(2026, 9, 10, 19, 4));
        ownerDecided.setResolvedByUserId(9L);
        ownerDecided.setManagerResolutionComment("Специалист ответил");
        WorkerRiskIncident managerDecided = incident(195L, 26699L, "Виктория Ц.", "Не правильные действия, моя ошибка");
        managerDecided.setResolvedAt(LocalDateTime.of(2026, 9, 10, 20, 51));
        managerDecided.setResolvedByUserId(13L);
        managerDecided.setManagerResolutionComment("Уже с самого утра не могу сделать выгул");
        when(repository.findPerformanceIncidentsByAssignedManagerId(eq(List.of(2L)), any(LocalDateTime.class),
                any(LocalDateTime.class), eq(WorkerRiskIncidentStatus.OPEN)))
                .thenReturn(List.of(ownerDecided, managerDecided));
        when(sourceOwners.currentOrderManagers(any())).thenReturn(Map.of(26699L, 2L));
        when(sourceOwners.managerUserId(2L)).thenReturn(13L);

        String report = new ManagerRiskDailyReportSectionService(repository, sourceOwners).format(2L, date);

        assertFalse(report.contains("Увод в блок"));
        assertTrue(report.contains("Специалист Виктория Ц.: «Блок аккаунта без попытки войти в него» "
                + "→ пояснение специалиста «Не правильные действия, моя ошибка»"));
        assertTrue(report.contains("Решение менеджера: «Уже с самого утра не могу сделать выгул»."));
        assertTrue(report.contains("Пояснения: по существу 0 · неполные 2"));
    }

    @Test
    void caseWhoseOrderMovedToAnotherManagerOrClosedByMaintenanceIsNotAsked() {
        LocalDate date = LocalDate.of(2026, 9, 9);
        WorkerRiskIncident moved = incident(183L, 21549L, "Виктория М.", "Опубликовано");
        WorkerRiskIncident maintenance = incident(184L, 21550L, "Юля К.", "Скопировала логин");
        maintenance.setResolvedAt(LocalDateTime.of(2026, 9, 9, 20, 4));
        maintenance.setResolvedByUsername("maintenance:user-request");
        when(repository.findPerformanceIncidentsByAssignedManagerId(eq(List.of(2L)), any(LocalDateTime.class),
                any(LocalDateTime.class), eq(WorkerRiskIncidentStatus.OPEN)))
                .thenReturn(List.of(moved, maintenance));
        when(sourceOwners.currentOrderManagers(any())).thenReturn(Map.of(21549L, 3L, 21550L, 2L));
        when(sourceOwners.managerUserId(2L)).thenReturn(13L);

        String report = new ManagerRiskDailyReportSectionService(repository, sourceOwners).format(2L, date);

        assertFalse(report.contains("Примеры для разбора"));
        assertFalse(report.contains("Часть пояснений не доказывает"));
    }

    private WorkerRiskIncident incident(Long id, Long orderId, String worker, String explanation) {
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setId(id);
        incident.setAssignedManagerId(2L);
        incident.setOrderId(orderId);
        incident.setWorkerName(worker);
        incident.setStatus(WorkerRiskIncidentStatus.RESOLVED);
        incident.setCreatedAt(LocalDateTime.of(2026, 9, id == 183L || id == 184L ? 9 : 10, 12, 6));
        incident.setTitle("Блок аккаунта без попытки войти в него");
        incident.setWorkerExplanation(explanation);
        incident.setExplanationQuality(WorkerRiskExplanationQuality.PARTIAL);
        incident.setExplanationQualityReason("Пояснение признаёт ошибку, но не объясняет причину");
        return incident;
    }
}

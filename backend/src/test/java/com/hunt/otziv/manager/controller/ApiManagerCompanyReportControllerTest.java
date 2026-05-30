package com.hunt.otziv.manager.controller;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.manager.dto.api.CompanyDeepReportStateResponse;
import com.hunt.otziv.manager.services.ManagerPermissionService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.reputationai.application.DeepCompanyResearchJobService;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchJobStatus;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiManagerCompanyReportControllerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private DeepCompanyResearchJobService deepCompanyResearchJobService;

    private final ManagerPermissionService managerPermissionService = new ManagerPermissionService();

    @Test
    void stateShowsLatestReadyReportWhenNewestJobFailed() {
        ApiManagerCompanyReportController controller = new ApiManagerCompanyReportController(
                orderService,
                deepCompanyResearchJobService,
                managerPermissionService
        );
        when(orderService.getOrderDTO(55L)).thenReturn(order());
        when(deepCompanyResearchJobService.findActive(1085L)).thenReturn(Optional.empty());
        when(deepCompanyResearchJobService.findLatestReady(1085L)).thenReturn(Optional.of(readyJob()));
        lenient().when(deepCompanyResearchJobService.findLatest(1085L)).thenReturn(Optional.of(failedJob()));

        CompanyDeepReportStateResponse state = controller.state(55L, null);

        assertThat(state.latestJob()).isNotNull();
        assertThat(state.latestJob().jobId()).isEqualTo(28L);
        assertThat(state.latestJob().report()).isNotNull();
        assertThat(state.activeJob()).isNull();
        assertThat(state.canStart()).isFalse();
    }

    private OrderDTO order() {
        return OrderDTO.builder()
                .company(CompanyDTO.builder()
                        .id(1085L)
                        .title("IQuest")
                        .build())
                .build();
    }

    private DeepCompanyResearchJobStatus readyJob() {
        DeepCompanyResearchReport report = new DeepCompanyResearchReport(
                1085L,
                "IQuest",
                "Иркутск",
                "yandexgpt",
                "yandexgpt/rc",
                "resp_ready",
                "## Краткая сводка\n\nГотовый отчет.",
                List.of(new DeepCompanyResearchReport.Section("Краткая сводка", "Готовый отчет.")),
                List.of(),
                List.of(),
                List.of(),
                DeepCompanyResearchReport.FactSnapshot.empty(),
                LocalDateTime.now().minusHours(2)
        );
        return new DeepCompanyResearchJobStatus(
                28L,
                1085L,
                "IQuest",
                "DONE",
                "yandexgpt",
                "yandexgpt/rc",
                "resp_ready",
                "",
                report,
                DeepCompanyResearchJobService.OPERATION_FULL_REPORT,
                LocalDateTime.now().minusHours(2),
                LocalDateTime.now().minusHours(2),
                LocalDateTime.now().minusHours(2),
                LocalDateTime.now().minusHours(1)
        );
    }

    private DeepCompanyResearchJobStatus failedJob() {
        return new DeepCompanyResearchJobStatus(
                29L,
                1085L,
                "IQuest",
                "FAILED",
                "yandexgpt",
                "yandexgpt/rc",
                "",
                "Ошибка модели",
                null,
                DeepCompanyResearchJobService.OPERATION_FULL_REPORT,
                LocalDateTime.now().minusMinutes(10),
                LocalDateTime.now().minusMinutes(9),
                LocalDateTime.now().minusMinutes(10),
                LocalDateTime.now().minusMinutes(9)
        );
    }
}

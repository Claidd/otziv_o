package com.hunt.otziv.manager.controller;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.manager.dto.api.CompanyDeepReportStateResponse;
import com.hunt.otziv.manager.services.ManagerAccessService;
import com.hunt.otziv.manager.services.ManagerPermissionService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.reputationai.api.dto.ReputationResearchRequest;
import com.hunt.otziv.reputationai.application.DeepCompanyResearchJobService;
import com.hunt.otziv.reputationai.config.DeepResearchProfile;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchJobStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/manager/orders/{orderId}/company-report")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
public class ApiManagerCompanyReportController {

    private final OrderService orderService;
    private final DeepCompanyResearchJobService deepCompanyResearchJobService;
    private final ManagerPermissionService managerPermissionService;
    private final ManagerAccessService managerAccessService;

    @GetMapping
    public CompanyDeepReportStateResponse state(
            @PathVariable Long orderId,
            Authentication authentication
    ) {
        managerAccessService.requireOrderAccess(orderId, authentication);
        CompanyContext company = resolveCompany(orderId);
        return buildState(company, authentication);
    }

    @PostMapping
    public CompanyDeepReportStateResponse start(
            @PathVariable Long orderId,
            Authentication authentication
    ) {
        managerAccessService.requireOrderAccess(orderId, authentication);
        CompanyContext company = resolveCompany(orderId);
        Optional<DeepCompanyResearchJobStatus> activeJob = deepCompanyResearchJobService.findActive(company.companyId());
        if (activeJob.isPresent()) {
            return buildState(company, authentication);
        }

        boolean adminOrOwner = isAdminOrOwner(authentication);
        boolean hasReadyReport = deepCompanyResearchJobService.findLatestReady(company.companyId()).isPresent();
        if (hasReadyReport && !adminOrOwner) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Отчёт о компании уже есть. Повторно обновлять его могут только администратор или владелец."
            );
        }

        startDeepReport(company.companyId());
        return buildState(company, authentication);
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public CompanyDeepReportStateResponse refresh(
            @PathVariable Long orderId,
            Authentication authentication
    ) {
        managerAccessService.requireOrderAccess(orderId, authentication);
        CompanyContext company = resolveCompany(orderId);
        startDeepReport(company.companyId());
        return buildState(company, authentication);
    }

    private void startDeepReport(Long companyId) {
        try {
            deepCompanyResearchJobService.start(companyId, maximumReportRequest());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    private CompanyDeepReportStateResponse buildState(CompanyContext company, Authentication authentication) {
        DeepCompanyResearchJobStatus activeJob = deepCompanyResearchJobService.findActive(company.companyId()).orElse(null);
        Optional<DeepCompanyResearchJobStatus> latestReadyJob = deepCompanyResearchJobService.findLatestReady(company.companyId());
        DeepCompanyResearchJobStatus latestJob = latestReadyJob
                .or(() -> deepCompanyResearchJobService.findLatest(company.companyId()))
                .orElse(null);
        boolean adminOrOwner = isAdminOrOwner(authentication);
        boolean hasReadyReport = latestReadyJob.isPresent();
        boolean canStart = activeJob == null && (adminOrOwner || !hasReadyReport);
        String unavailableReason = "";
        if (activeJob != null) {
            unavailableReason = "Отчёт уже готовится.";
        } else if (hasReadyReport && !adminOrOwner) {
            unavailableReason = "Отчёт уже готов. Повторный запуск доступен только администратору или владельцу.";
        }

        return new CompanyDeepReportStateResponse(
                company.companyId(),
                company.companyName(),
                latestJob,
                activeJob,
                canStart,
                adminOrOwner,
                unavailableReason
        );
    }

    private CompanyContext resolveCompany(Long orderId) {
        OrderDTO order = orderService.getOrderDTO(orderId);
        return resolveCompany(order);
    }

    private CompanyContext resolveCompany(OrderDTO order) {
        CompanyDTO company = order.getCompany();
        if (company == null || company.getId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "У заказа не найдена компания для отчёта.");
        }

        return new CompanyContext(company.getId(), safe(company.getTitle()));
    }

    private ReputationResearchRequest maximumReportRequest() {
        return new ReputationResearchRequest(
                null,
                null,
                List.of(),
                List.of(),
                true,
                DeepResearchProfile.MAXIMUM.key(),
                DeepCompanyResearchJobService.OPERATION_FULL_REPORT,
                null,
                null,
                null,
                true
        );
    }

    private boolean isAdminOrOwner(Authentication authentication) {
        return managerPermissionService.hasAnyRole(authentication, "ADMIN", "OWNER");
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record CompanyContext(Long companyId, String companyName) {
    }
}

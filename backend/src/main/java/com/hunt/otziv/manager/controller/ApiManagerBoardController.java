package com.hunt.otziv.manager.controller;

import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.manager.dto.api.ManagerBoardResponse;
import com.hunt.otziv.manager.services.ManagerBoardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/manager")
public class ApiManagerBoardController {

    private final ManagerBoardService managerBoardService;
    private final PerformanceMetrics performanceMetrics;

    @GetMapping("/board")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public ManagerBoardResponse getBoard(
            @RequestParam(defaultValue = "companies") String section,
            @RequestParam(defaultValue = "Все") String status,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "desc") String sortDirection,
            @RequestParam(required = false) Long companyId,
            Principal principal,
            Authentication authentication
    ) {
        return performanceMetrics.recordEndpoint("manager.board", () -> managerBoardService.getBoard(
                section,
                status,
                keyword,
                pageNumber,
                pageSize,
                sortDirection,
                companyId,
                principal,
                authentication
        ));
    }

}

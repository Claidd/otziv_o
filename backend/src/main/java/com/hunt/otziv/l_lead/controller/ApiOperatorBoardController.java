package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.dto.TelephoneIDAndTimeDTO;
import com.hunt.otziv.l_lead.dto.api.LeadPageResponse;
import com.hunt.otziv.l_lead.dto.api.LeadPersonResponse;
import com.hunt.otziv.l_lead.dto.api.LeadResponse;
import com.hunt.otziv.l_lead.dto.api.LeadStatusChangeRequest;
import com.hunt.otziv.l_lead.dto.api.OperatorBoardResponse;
import com.hunt.otziv.l_lead.dto.api.OperatorDeviceTokenRequest;
import com.hunt.otziv.l_lead.model.LeadStatus;
import com.hunt.otziv.l_lead.services.serv.DeviceTokenService;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/operator")
public class ApiOperatorBoardController {

    private final LeadService leadService;
    private final PromoTextService promoTextService;
    private final DeviceTokenService deviceTokenService;
    private final PerformanceMetrics performanceMetrics;

    @GetMapping("/board")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'OPERATOR')")
    public OperatorBoardResponse getBoard(
            @CookieValue(name = "device_token", required = false) String token,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize,
            Principal principal,
            Authentication authentication
    ) {
        return performanceMetrics.recordEndpoint("operator.board", () -> {
            int normalizedPage = Math.max(pageNumber, 0);
            int normalizedSize = Math.max(1, Math.min(pageSize, 50));
            String normalizedKeyword = keyword == null ? "" : keyword.trim();
            LocalDateTime now = LocalDateTime.now();

            TelephoneIDAndTimeDTO telephone = resolveTelephone(token);
            boolean requireDeviceId = telephone == null || telephone.getTelephoneID() == null;
            boolean timerExpired = isTimerExpired(telephone, now);
            Page<LeadDTO> leads = Page.empty(PageRequest.of(normalizedPage, normalizedSize));

            if (!requireDeviceId) {
                leads = loadLeads(
                        telephone.getTelephoneID(),
                        normalizedKeyword,
                        normalizedPage,
                        normalizedSize,
                        timerExpired,
                        principal,
                        authentication
                );
            }

            return new OperatorBoardResponse(
                    toPageResponse(leads),
                    promoTextService.getAllPromoTexts(),
                    deviceTokenService.getText(token),
                    requireDeviceId,
                    telephone != null ? telephone.getTelephoneID() : null,
                    telephone != null ? telephone.getOperatorID() : null,
                    telephone != null ? telephone.getTime() : null,
                    timerExpired
            );
        });
    }

    @PostMapping("/device-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'OPERATOR')")
    public void createDeviceToken(
            @Valid @RequestBody OperatorDeviceTokenRequest request,
            HttpServletResponse response
    ) {
        try {
            deviceTokenService.createDeviceToken(request.telephoneId(), response);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не удалось найти телефон", ex);
        }
    }

    @PostMapping("/leads/{id}/status/send")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'OPERATOR')")
    public void markSend(@PathVariable Long id) {
        leadService.changeStatusLeadOnSendAndTelephone(id);
    }

    @PostMapping("/leads/{id}/status/to-work")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'OPERATOR')")
    public void markToWork(
            @PathVariable Long id,
            @RequestBody(required = false) LeadStatusChangeRequest request
    ) {
        leadService.changeStatusLeadToWork(id, request != null ? request.commentsLead() : null);
    }

    private TelephoneIDAndTimeDTO resolveTelephone(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return deviceTokenService.getTelephoneIdByToken(token);
    }

    private boolean isTimerExpired(TelephoneIDAndTimeDTO telephone, LocalDateTime now) {
        return telephone != null && (telephone.getTime() == null || !telephone.getTime().isAfter(now));
    }

    private Page<LeadDTO> loadLeads(
            Long telephoneId,
            String keyword,
            int pageNumber,
            int pageSize,
            boolean timerExpired,
            Principal principal,
            Authentication authentication
    ) {
        boolean hasKeyword = !keyword.isBlank();

        if (hasAnyRole(authentication, "ROLE_ADMIN", "ROLE_OWNER")) {
            if (hasKeyword) {
                return leadService.getAllLeadsNoStatus(keyword, principal, pageNumber, pageSize);
            }
            if (timerExpired) {
                return leadService.getAllLeads(LeadStatus.NEW.title, keyword, principal, pageNumber, pageSize);
            }
        }

        if (hasAnyRole(authentication, "ROLE_OPERATOR")) {
            if (hasKeyword || timerExpired) {
                return leadService.getAllLeadsToOperator(
                        telephoneId,
                        LeadStatus.NEW.title,
                        hasKeyword ? keyword : "",
                        principal,
                        pageNumber,
                        pageSize
                );
            }
        }

        return Page.empty(PageRequest.of(pageNumber, pageSize));
    }

    private LeadPageResponse toPageResponse(Page<LeadDTO> page) {
        List<LeadResponse> content = page.getContent().stream()
                .map(this::toLeadResponse)
                .toList();

        return new LeadPageResponse(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }

    private LeadResponse toLeadResponse(LeadDTO lead) {
        return new LeadResponse(
                lead.getId(),
                lead.getTelephoneLead(),
                lead.getCityLead(),
                lead.getCommentsLead(),
                lead.getLidStatus(),
                lead.getCreateDate(),
                lead.getUpdateStatus(),
                lead.getDateNewTry(),
                lead.isOffer(),
                lead.getOperatorId(),
                toPerson(lead.getOperator()),
                toPerson(lead.getManager()),
                toPerson(lead.getMarketolog())
        );
    }

    private LeadPersonResponse toPerson(Operator operator) {
        return operator == null ? null : toPerson(operator.getId(), operator.getUser());
    }

    private LeadPersonResponse toPerson(Manager manager) {
        return manager == null ? null : toPerson(manager.getId(), manager.getUser());
    }

    private LeadPersonResponse toPerson(Marketolog marketolog) {
        return marketolog == null ? null : toPerson(marketolog.getId(), marketolog.getUser());
    }

    private LeadPersonResponse toPerson(Long id, User user) {
        return new LeadPersonResponse(
                id,
                user != null ? user.getId() : null,
                user != null ? user.getUsername() : null,
                user != null ? user.getFio() : null
        );
    }

    private boolean hasAnyRole(Authentication authentication, String... roles) {
        if (authentication == null) {
            return false;
        }

        List<String> allowedRoles = Arrays.asList(roles);
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(allowedRoles::contains);
    }
}

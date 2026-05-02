package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.dto.api.LeadBoardResponse;
import com.hunt.otziv.l_lead.dto.api.LeadCreateRequest;
import com.hunt.otziv.l_lead.dto.api.LeadEditOptionsResponse;
import com.hunt.otziv.l_lead.dto.api.LeadPageResponse;
import com.hunt.otziv.l_lead.dto.api.LeadPersonOptionResponse;
import com.hunt.otziv.l_lead.dto.api.LeadPersonResponse;
import com.hunt.otziv.l_lead.dto.api.LeadResponse;
import com.hunt.otziv.l_lead.dto.api.LeadStatusChangeRequest;
import com.hunt.otziv.l_lead.dto.api.LeadUpdateRequest;
import com.hunt.otziv.l_lead.model.LeadStatus;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.l_lead.utils.LeadPhoneNormalizer;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.MarketologService;
import com.hunt.otziv.u_users.services.service.OperatorService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/leads")
public class ApiLeadBoardController {

    private final LeadService leadService;
    private final PromoTextService promoTextService;
    private final LeadsRepository leadsRepository;
    private final OperatorService operatorService;
    private final ManagerService managerService;
    private final MarketologService marketologService;

    @GetMapping("/board")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG')")
    public LeadBoardResponse getLeadBoard(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "desc") String sortDirection,
            Principal principal
    ) {
        int normalizedPage = Math.max(pageNumber, 0);
        int normalizedSize = Math.max(1, Math.min(pageSize, 50));
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        String normalizedSortDirection = "asc".equalsIgnoreCase(sortDirection) ? "asc" : "desc";

        return new LeadBoardResponse(
                toPageResponse(leadService.getAllLeadsToWork(
                        LeadStatus.TO_WORK.title,
                        normalizedKeyword,
                        principal,
                        normalizedPage,
                        normalizedSize,
                        normalizedSortDirection
                )),
                toPageResponse(leadService.getAllLeads(
                        LeadStatus.NEW.title,
                        normalizedKeyword,
                        principal,
                        normalizedPage,
                        normalizedSize,
                        normalizedSortDirection
                )),
                toPageResponse(leadService.getAllLeadsToDateReSend(
                        LeadStatus.SEND.title,
                        normalizedKeyword,
                        principal,
                        normalizedPage,
                        normalizedSize,
                        normalizedSortDirection
                )),
                toPageResponse(leadService.getAllLeads(
                        LeadStatus.ARCHIVE.title,
                        normalizedKeyword,
                        principal,
                        normalizedPage,
                        normalizedSize,
                        normalizedSortDirection
                )),
                toPageResponse(leadService.getAllLeads(
                        LeadStatus.INWORK.title,
                        normalizedKeyword,
                        principal,
                        normalizedPage,
                        normalizedSize,
                        normalizedSortDirection
                )),
                toPageResponse(leadService.getAllLeadsNoStatus(
                        normalizedKeyword,
                        principal,
                        normalizedPage,
                        normalizedSize,
                        normalizedSortDirection
                )),
                Arrays.stream(LeadStatus.values()).map(status -> status.title).toList(),
                promoTextService.getAllPromoTexts()
        );
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'OPERATOR', 'MARKETOLOG')")
    public LeadResponse createLead(
            @Valid @RequestBody LeadCreateRequest request,
            Principal principal,
            Authentication authentication
    ) {
        String telephoneLead = changeNumberPhone(request.telephoneLead());
        String cityLead = request.cityLead().trim();
        String commentsLead = request.commentsLead() == null ? "" : request.commentsLead().trim();
        Manager manager = null;

        if (telephoneLead.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Номер не может быть пустым");
        }

        if (leadsRepository.existsByTelephoneLeadIn(LeadPhoneNormalizer.variants(telephoneLead))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Такой номер телефона уже есть в базе");
        }

        if (request.managerId() != null) {
            if (!hasAnyRole(authentication, "ROLE_ADMIN", "ROLE_OWNER")) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Назначать менеджера может только владелец или администратор");
            }

            manager = managerService.getManagerById(request.managerId());
            if (manager == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер не найден");
            }
        }

        LeadDTO leadDTO = LeadDTO.builder()
                .telephoneLead(telephoneLead)
                .cityLead(cityLead)
                .commentsLead(commentsLead)
                .manager(manager)
                .build();

        Long leadId = leadService.save(leadDTO, principal.getName()).getId();
        return toLeadResponse(leadService.findById(leadId));
    }

    @GetMapping("/edit-options")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG')")
    public LeadEditOptionsResponse getEditOptions() {
        return new LeadEditOptionsResponse(
                operatorService.getAllOperators().stream()
                        .map(operator -> toPersonOption(operator.getId(), operator.getUser()))
                        .toList(),
                managerService.getAllManagers().stream()
                        .map(manager -> toPersonOption(manager.getId(), manager.getUser()))
                        .toList(),
                marketologService.getAllMarketologs().stream()
                        .map(marketolog -> toPersonOption(marketolog.getId(), marketolog.getUser()))
                        .toList(),
                Arrays.stream(LeadStatus.values()).map(status -> status.title).toList()
        );
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public LeadResponse updateLead(
            @PathVariable Long id,
            @RequestBody LeadUpdateRequest request
    ) {
        String telephoneLead = changeNumberPhone(request.telephoneLead());
        if (telephoneLead.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Номер не может быть пустым");
        }

        if (leadsRepository.findFirstByTelephoneLeadInAndIdNot(LeadPhoneNormalizer.variants(telephoneLead), id).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Такой номер телефона уже есть в базе");
        }

        LeadDTO leadDTO = LeadDTO.builder()
                .telephoneLead(telephoneLead)
                .cityLead(request.cityLead())
                .commentsLead(request.commentsLead())
                .lidStatus(request.lidStatus())
                .operator(request.operatorId() == null ? null : operatorService.getOperatorById(request.operatorId()))
                .manager(request.managerId() == null ? null : managerService.getManagerById(request.managerId()))
                .marketolog(request.marketologId() == null ? null : marketologService.getMarketologById(request.marketologId()))
                .build();

        leadService.updateProfile(leadDTO, id);
        return toLeadResponse(leadService.findById(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public void deleteLead(@PathVariable Long id) {
        try {
            leadService.deleteLead(id);
        } catch (EntityNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/{id}/status/send")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG')")
    public void markSend(@PathVariable Long id) {
        leadService.changeStatusLeadOnSend(id);
    }

    @PostMapping("/{id}/status/resend")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG')")
    public void markResend(@PathVariable Long id) {
        leadService.changeStatusLeadOnReSend(id);
    }

    @PostMapping("/{id}/status/archive")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG')")
    public void markArchive(@PathVariable Long id) {
        leadService.changeStatusLeadOnArchive(id);
    }

    @PostMapping("/{id}/status/to-work")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG')")
    public void markToWork(
            @PathVariable Long id,
            @RequestBody(required = false) LeadStatusChangeRequest request
    ) {
        leadService.changeStatusLeadToWork(id, request != null ? request.commentsLead() : null);
    }

    @PostMapping("/{id}/status/new")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG')")
    public void markNew(@PathVariable Long id) {
        leadService.changeStatusLeadOnNew(id);
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

    private LeadPersonOptionResponse toPersonOption(Long id, User user) {
        return new LeadPersonOptionResponse(
                id,
                user != null ? user.getId() : null,
                user != null ? user.getUsername() : null,
                user != null ? user.getFio() : null,
                user != null ? user.getEmail() : null
        );
    }

    private String changeNumberPhone(String phone) {
        return LeadPhoneNormalizer.normalize(phone);
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

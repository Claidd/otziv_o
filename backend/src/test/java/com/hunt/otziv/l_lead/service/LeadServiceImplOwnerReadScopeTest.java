package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.l_lead.event.LeadEventPublisher;
import com.hunt.otziv.l_lead.mapper.LeadMapper;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.l_lead.service.TelephoneService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.MarketologService;
import com.hunt.otziv.u_users.service.OperatorService;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import com.hunt.otziv.z_zp.service.ZpService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeadServiceImplOwnerReadScopeTest {

    @Mock private LeadsRepository leadsRepository;
    @Mock private UserRepository userRepository;
    @Mock private ManagerService managerService;
    @Mock private OperatorService operatorService;
    @Mock private MarketologService marketologService;
    @Mock private ZpService zpService;
    @Mock private UserService userService;
    @Mock private TelephoneService telephoneService;
    @Mock private LeadMapper leadMapper;
    @Mock private LeadEventPublisher leadEventPublisher;
    @Mock private WhatsAppService whatsAppService;
    @Mock private GamificationEventService gamificationEventService;
    @Mock private AppSettingService appSettingService;
    @Mock private LeadAccessService leadAccessService;

    private LeadServiceImpl service;
    private Authentication owner;

    @BeforeEach
    void setUp() {
        service = new LeadServiceImpl(
                leadsRepository,
                userRepository,
                managerService,
                operatorService,
                marketologService,
                zpService,
                userService,
                telephoneService,
                leadMapper,
                leadEventPublisher,
                whatsAppService,
                gamificationEventService,
                appSettingService,
                leadAccessService
        );
        owner = new UsernamePasswordAuthenticationToken(
                "owner-a",
                "n/a",
                Set.of(new SimpleGrantedAuthority("ROLE_OWNER"))
        );
        SecurityContextHolder.getContext().setAuthentication(owner);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownManagersModeScopesEveryListCountAndKeywordPath() {
        Manager manager = Manager.builder().id(11L).build();
        List<Manager> managers = List.of(manager);
        when(leadAccessService.ownerReadScope(owner))
                .thenReturn(new LeadAccessService.OwnerReadScope(false, managers));
        stubRestrictedPages();

        exerciseEveryOwnerReadPath();

        verify(leadsRepository).findAllByLidStatusAndManagerToOwner(eq("Новый"), eq(managers), any(Pageable.class));
        verify(leadsRepository).countByLidStatusAndManagerIn("Новый", managers);
        verify(leadsRepository).findAllByLidStatusAndManagerToOwner(eq("В работе"), eq(managers), any(Pageable.class));
        verify(leadsRepository).countByLidStatusAndManagerIn("В работе", managers);
        verify(leadsRepository).findAllByManagerToOwner(eq(managers), any(Pageable.class));
        verify(leadsRepository).countByManagerIn(managers);
        verify(leadsRepository).findByStatusAndManagerInAndDateNewTryLessThanEqual(
                eq("Отправленный"), eq(managers), any(LocalDate.class), any(Pageable.class));
        verify(leadsRepository).countByStatusAndManagerInAndDateNewTryLessThanEqual(
                eq("Отправленный"), eq(managers), any(LocalDate.class));
        verify(leadsRepository).searchByStatusAndKeywordAndManagerIn(
                eq("Новый"), eq("%acme%"), eq(managers), any(Pageable.class));
        verify(leadsRepository).countByStatusAndKeywordAndManagerIn("Новый", "%acme%", managers);
        verify(leadsRepository).searchByKeywordAndManagerToOwner(
                eq("%acme%"), eq(managers), any(Pageable.class));
        verify(leadsRepository).countByKeywordAndManagerIn("%acme%", managers);
        verify(leadsRepository).searchByStatusAndManagerInAndDateNewTryLessThanEqualAndKeyword(
                eq("Отправленный"), eq(managers), any(LocalDate.class), eq("%acme%"), any(Pageable.class));
        verify(leadsRepository).countByStatusAndManagerInAndDateNewTryLessThanEqualAndKeyword(
                eq("Отправленный"), eq(managers), any(LocalDate.class), eq("%acme%"));

        verify(leadsRepository, never()).findAllByLidStatus(anyString(), any(Pageable.class));
        verify(leadsRepository, never()).findAll(any(Pageable.class));
        verify(leadsRepository, never()).searchByKeyword(anyString(), any(Pageable.class));
        verify(leadsRepository, never()).searchByStatusAndKeyword(anyString(), anyString(), any(Pageable.class));
        verify(leadsRepository, never()).findByLidStatusAndDateNewTryLessThanEqual(
                anyString(), any(LocalDate.class), any(Pageable.class));
    }

    @Test
    void allManagersModeUsesGlobalQueriesForEveryListCountAndKeywordPath() {
        when(leadAccessService.ownerReadScope(owner))
                .thenReturn(new LeadAccessService.OwnerReadScope(true, List.of()));
        stubGlobalPages();

        exerciseEveryOwnerReadPath();

        verify(leadsRepository).findAllByLidStatus(eq("Новый"), any(Pageable.class));
        verify(leadsRepository).countByLidStatus("Новый");
        verify(leadsRepository).findAllByLidStatus(eq("В работе"), any(Pageable.class));
        verify(leadsRepository).countByLidStatus("В работе");
        verify(leadsRepository).findAll(any(Pageable.class));
        verify(leadsRepository).count();
        verify(leadsRepository).findByLidStatusAndDateNewTryLessThanEqual(
                eq("Отправленный"), any(LocalDate.class), any(Pageable.class));
        verify(leadsRepository).countByLidStatusAndDateNewTryLessThanEqual(
                eq("Отправленный"), any(LocalDate.class));
        verify(leadsRepository).searchByStatusAndKeyword(
                eq("Новый"), eq("%acme%"), any(Pageable.class));
        verify(leadsRepository).countByStatusAndKeyword("Новый", "%acme%");
        verify(leadsRepository).searchByKeyword(eq("%acme%"), any(Pageable.class));
        verify(leadsRepository).countByKeyword("%acme%");
        verify(leadsRepository).searchByStatusAndDateNewTryLessThanEqualAndKeyword(
                eq("Отправленный"), any(LocalDate.class), eq("%acme%"), any(Pageable.class));
        verify(leadsRepository).countByStatusAndDateNewTryLessThanEqualAndKeyword(
                eq("Отправленный"), any(LocalDate.class), eq("%acme%"));

        verify(leadsRepository, never()).findAllByLidStatusAndManagerToOwner(
                anyString(), anyList(), any(Pageable.class));
        verify(leadsRepository, never()).findAllByManagerToOwner(anyList(), any(Pageable.class));
        verify(leadsRepository, never()).searchByKeywordAndManagerToOwner(
                anyString(), anyList(), any(Pageable.class));
        verify(leadsRepository, never()).searchByStatusAndKeywordAndManagerIn(
                anyString(), anyString(), anyList(), any(Pageable.class));
        verify(leadsRepository, never()).findByStatusAndManagerInAndDateNewTryLessThanEqual(
                anyString(), anyCollection(), any(LocalDate.class), any(Pageable.class));
    }

    private void exerciseEveryOwnerReadPath() {
        service.getAllLeads("Новый", "", owner, 0, 20);
        service.countLeads("Новый", "", owner);
        service.getAllLeadsToWork("В работе", "", owner, 0, 20);
        service.countLeadsToWork("В работе", "", owner);
        service.getAllLeadsNoStatus("", owner, 0, 20);
        service.countLeadsNoStatus("", owner);
        service.getAllLeadsToDateReSend("Отправленный", "", owner, 0, 20);
        service.countLeadsToDateReSend("Отправленный", "", owner);

        service.getAllLeads("Новый", "Acme", owner, 0, 20);
        service.countLeads("Новый", "Acme", owner);
        service.getAllLeadsNoStatus("Acme", owner, 0, 20);
        service.countLeadsNoStatus("Acme", owner);
        service.getAllLeadsToDateReSend("Отправленный", "Acme", owner, 0, 20);
        service.countLeadsToDateReSend("Отправленный", "Acme", owner);
    }

    private void stubRestrictedPages() {
        when(leadsRepository.findAllByLidStatusAndManagerToOwner(anyString(), anyList(), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(leadsRepository.findAllByManagerToOwner(anyList(), any(Pageable.class))).thenReturn(Page.empty());
        when(leadsRepository.findByStatusAndManagerInAndDateNewTryLessThanEqual(
                anyString(), anyCollection(), any(LocalDate.class), any(Pageable.class))).thenReturn(Page.empty());
        when(leadsRepository.searchByStatusAndKeywordAndManagerIn(
                anyString(), anyString(), anyList(), any(Pageable.class))).thenReturn(Page.empty());
        when(leadsRepository.searchByKeywordAndManagerToOwner(anyString(), anyList(), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(leadsRepository.searchByStatusAndManagerInAndDateNewTryLessThanEqualAndKeyword(
                anyString(), anyCollection(), any(LocalDate.class), anyString(), any(Pageable.class)))
                .thenReturn(Page.empty());
    }

    private void stubGlobalPages() {
        when(leadsRepository.findAllByLidStatus(anyString(), any(Pageable.class))).thenReturn(Page.empty());
        when(leadsRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());
        when(leadsRepository.findByLidStatusAndDateNewTryLessThanEqual(
                anyString(), any(LocalDate.class), any(Pageable.class))).thenReturn(Page.empty());
        when(leadsRepository.searchByStatusAndKeyword(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(leadsRepository.searchByKeyword(anyString(), any(Pageable.class))).thenReturn(Page.empty());
        when(leadsRepository.searchByStatusAndDateNewTryLessThanEqualAndKeyword(
                anyString(), any(LocalDate.class), anyString(), any(Pageable.class))).thenReturn(Page.empty());
    }
}

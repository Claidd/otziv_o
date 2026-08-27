package com.hunt.otziv.c_companies.service;

import com.hunt.otziv.c_categories.service.CategoryService;
import com.hunt.otziv.c_categories.service.SubCategoryService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.dto.CompanyListDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.c_companies.repository.CompanyInfoRepository;
import com.hunt.otziv.client_messages.service.PublicationProgressPreferenceService;
import com.hunt.otziv.l_lead.service.LeadService;
import com.hunt.otziv.maxbot.service.MaxGroupLinkService;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.t_telegrambot.service.TelegramGroupLinkService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.OperatorService;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.u_users.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import jakarta.persistence.Transient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyServiceImplTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private CompanyInfoRepository companyInfoRepository;

    @Mock
    private LeadService leadService;

    @Mock
    private UserService userService;

    @Mock
    private ManagerService managerService;

    @Mock
    private WorkerService workerService;

    @Mock
    private CompanyStatusService companyStatusService;

    @Mock
    private CategoryService categoryService;

    @Mock
    private SubCategoryService subCategoryService;

    @Mock
    private FilialService filialService;

    @Mock
    private ReviewService reviewService;

    @Mock
    private OperatorService operatorService;

    @Mock
    private TelegramService telegramService;

    @Mock
    private TelegramGroupLinkService telegramGroupLinkService;

    @Mock
    private MaxGroupLinkService maxGroupLinkService;

    @Mock
    private NextOrderRequestRepository nextOrderRequestRepository;

    @Mock
    private PublicationProgressPreferenceService publicationProgressPreferenceService;

    @Test
    void getAllCompaniesDTOListUsesStableSortForEqualUpdateDates() {
        CompanyServiceImpl service = service();
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        when(companyRepository.findPageIdToAdminLive(any(), any(), pageableCaptor.capture()))
                .thenAnswer(invocation -> new PageImpl<Long>(List.of(), invocation.getArgument(2), 0));

        Page<CompanyListDTO> result = service.getAllCompaniesDTOList("", -5, 0, "asc");

        Pageable pageable = pageableCaptor.getValue();
        assertTrue(result.isEmpty());
        assertEquals(0, pageable.getPageNumber());
        assertEquals(1, pageable.getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("updateStatus").getDirection());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("id").getDirection());
        verify(companyRepository, never()).findPageToAdminWithFetchWithKeyWord(any(), any(), any());
        verify(companyRepository, never()).findPageToAdminWithFetchWithKeyWordLive(any(), any(), any(), any(), any());
    }

    @Test
    void updateCompanyPublicationProgressPreferenceUsesSharedGroupScope() {
        CompanyServiceImpl service = service();
        Company company = Company.builder()
                .id(1293L)
                .title("Барс-оценка")
                .telephone("79000000000")
                .city("Иркутск")
                .urlChat("chat")
                .urlSite("site")
                .email("bars@example.test")
                .active(true)
                .publicationProgressReportsEnabled(true)
                .allowWorkerPublicationDateEdit(false)
                .contractorPaymentRoutingEnabled(false)
                .filial(new LinkedHashSet<>())
                .contacts(new LinkedHashSet<>())
                .build();
        when(companyRepository.findByIdForUpdate(1293L)).thenReturn(Optional.of(company));
        when(companyInfoRepository.findByCompanyId(1293L)).thenReturn(Optional.empty());

        CompanyDTO dto = CompanyDTO.builder()
                .title("Барс-оценка")
                .telephone("79000000000")
                .city("Иркутск")
                .urlChat("chat")
                .urlSite("site")
                .email("bars@example.test")
                .active(true)
                .publicationProgressReportsEnabled(false)
                .allowWorkerPublicationDateEdit(true)
                .filial(FilialDTO.builder().title("").build())
                .contacts(Set.of())
                .build();

        service.updateCompany(dto, WorkerDTO.builder().workerId(0L).build(), 1293L);

        verify(publicationProgressPreferenceService).setCompanyPreference(1293L, false);
        verify(companyRepository).save(company);
        assertTrue(company.isAllowWorkerPublicationDateEdit());
        assertFalse(company.isContractorPaymentRoutingEnabled());
    }

    @Test
    void companyInfoIsLoadedExplicitlyInsteadOfTriggeringInverseOneToOneChecks() throws Exception {
        assertTrue(Company.class.getDeclaredField("info").isAnnotationPresent(Transient.class));
    }

    @Test
    void findByGroupIdPrefersActiveNonBannedNewestCompanyWhenGroupIsShared() {
        CompanyServiceImpl service = service();
        CompanyStatus activeStatus = new CompanyStatus();
        activeStatus.setTitle("В работе");
        CompanyStatus bannedStatus = new CompanyStatus();
        bannedStatus.setTitle("бан");
        Company inactive = Company.builder()
                .id(10L)
                .title("Старая карточка")
                .active(false)
                .status(activeStatus)
                .statusChangedAt(LocalDateTime.of(2026, 8, 20, 10, 0))
                .build();
        Company banned = Company.builder()
                .id(11L)
                .title("Забаненная карточка")
                .active(true)
                .status(bannedStatus)
                .statusChangedAt(LocalDateTime.of(2026, 8, 21, 10, 0))
                .build();
        Company selected = Company.builder()
                .id(12L)
                .title("Актуальная карточка")
                .active(true)
                .status(activeStatus)
                .statusChangedAt(LocalDateTime.of(2026, 8, 22, 10, 0))
                .build();
        Company olderActive = Company.builder()
                .id(13L)
                .title("Активная, но старее")
                .active(true)
                .status(activeStatus)
                .statusChangedAt(LocalDateTime.of(2026, 8, 19, 10, 0))
                .build();
        when(companyRepository.findAllByGroupId("120363-test@g.us"))
                .thenReturn(List.of(inactive, banned, selected, olderActive));

        Optional<Company> result = service.findByGroupId("120363-test@g.us");

        assertTrue(result.isPresent());
        assertEquals(12L, result.get().getId());
    }

    private CompanyServiceImpl service() {
        return new CompanyServiceImpl(
                companyRepository,
                companyInfoRepository,
                leadService,
                userService,
                managerService,
                workerService,
                companyStatusService,
                categoryService,
                subCategoryService,
                filialService,
                reviewService,
                operatorService,
                telegramService,
                telegramGroupLinkService,
                maxGroupLinkService,
                nextOrderRequestRepository,
                publicationProgressPreferenceService
        );
    }
}

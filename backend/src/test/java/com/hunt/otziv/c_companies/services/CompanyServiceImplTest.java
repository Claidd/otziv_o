package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.dto.CompanyListDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.service.PublicationProgressPreferenceService;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.maxbot.service.MaxGroupLinkService;
import com.hunt.otziv.p_products.next_order.NextOrderRequestRepository;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.t_telegrambot.service.TelegramGroupLinkService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.OperatorService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                .filial(new LinkedHashSet<>())
                .contacts(new LinkedHashSet<>())
                .build();
        when(companyRepository.findById(1293L)).thenReturn(Optional.of(company));

        CompanyDTO dto = CompanyDTO.builder()
                .title("Барс-оценка")
                .telephone("79000000000")
                .city("Иркутск")
                .urlChat("chat")
                .urlSite("site")
                .email("bars@example.test")
                .active(true)
                .publicationProgressReportsEnabled(false)
                .filial(FilialDTO.builder().title("").build())
                .contacts(Set.of())
                .build();

        service.updateCompany(dto, WorkerDTO.builder().workerId(0L).build(), 1293L);

        verify(publicationProgressPreferenceService).setCompanyPreference(1293L, false);
        verify(companyRepository).save(company);
    }

    private CompanyServiceImpl service() {
        return new CompanyServiceImpl(
                companyRepository,
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

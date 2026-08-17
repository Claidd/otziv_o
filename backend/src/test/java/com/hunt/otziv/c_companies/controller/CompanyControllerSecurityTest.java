package com.hunt.otziv.c_companies.controller;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.c_categories.service.CategoryService;
import com.hunt.otziv.c_categories.service.SubCategoryService;
import com.hunt.otziv.c_cities.service.CityService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.l_lead.service.PromoTextService;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.u_users.service.WorkerService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

class CompanyControllerSecurityTest {

    private final CompanyService companyService = mock(CompanyService.class);
    private final CompanyController controller = new CompanyController(
            companyService,
            mock(CategoryService.class),
            mock(SubCategoryService.class),
            mock(ManagerService.class),
            mock(WorkerService.class),
            mock(PromoTextService.class),
            mock(OrderService.class),
            mock(UserService.class),
            mock(CityService.class)
    );

    @Test
    void managerCannotChangeCompanyPaymentRoutingThroughLegacyMvc() {
        CompanyDTO current = CompanyDTO.builder().contractorPaymentRoutingEnabled(false).build();
        CompanyDTO submitted = CompanyDTO.builder().contractorPaymentRoutingEnabled(true).build();
        WorkerDTO worker = WorkerDTO.builder().workerId(0L).build();
        when(companyService.getCompaniesDTOById(41L)).thenReturn(current);
        var manager = new UsernamePasswordAuthenticationToken(
                "manager",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))
        );

        assertThrows(AccessDeniedException.class, () -> controller.editCompany(
                submitted,
                worker,
                41L,
                mock(RedirectAttributes.class),
                mock(Model.class),
                manager
        ));

        verify(companyService, never()).updateCompany(submitted, worker, 41L);
    }

    @Test
    void ownerCanChangeCompanyPaymentRoutingThroughLegacyMvc() {
        CompanyDTO current = CompanyDTO.builder().contractorPaymentRoutingEnabled(false).build();
        CompanyDTO submitted = CompanyDTO.builder().contractorPaymentRoutingEnabled(true).build();
        WorkerDTO worker = WorkerDTO.builder().workerId(0L).build();
        when(companyService.getCompaniesDTOById(42L)).thenReturn(current);
        var owner = new UsernamePasswordAuthenticationToken(
                "owner",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_OWNER"))
        );

        String redirect = controller.editCompany(
                submitted,
                worker,
                42L,
                mock(RedirectAttributes.class),
                mock(Model.class),
                owner
        );

        assertEquals("redirect:/companies/editCompany/{companyId}", redirect);
        verify(companyService).updateCompany(submitted, worker, 42L);
    }
}

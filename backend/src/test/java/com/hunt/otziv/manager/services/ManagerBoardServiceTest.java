package com.hunt.otziv.manager.services;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.c_companies.dto.CompanyListDTO;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.manager.dto.api.ManagerBoardResponse;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerBoardServiceTest {

    @Mock
    private CompanyService companyService;

    @Mock
    private OrderService orderService;

    @Mock
    private PromoTextService promoTextService;

    @Mock
    private UserService userService;

    @Mock
    private ManagerService managerService;

    @Mock
    private BadReviewTaskService badReviewTaskService;

    @Spy
    private ManagerPermissionService managerPermissionService = new ManagerPermissionService();

    @InjectMocks
    private ManagerBoardService service;

    @Test
    void getBoardNormalizesOrderRequestAndKeepsCompanyPageEmpty() {
        Principal principal = () -> "admin";
        Authentication admin = authentication("ROLE_ADMIN");
        OrderDTOList order = OrderDTOList.builder()
                .id(7L)
                .companyId(3L)
                .status("Новый")
                .build();

        when(orderService.getAllOrderDTOAndKeywordAndStatus("needle", "Новый", 0, 50, "asc"))
                .thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 50), 1));
        when(companyService.countCompaniesByStatus())
                .thenReturn(Map.of("Новая", 2, "В работе", 3));
        when(orderService.countOrdersByStatus())
                .thenReturn(Map.of("Новый", 4, "Оплачено", 1));
        when(promoTextService.getAllPromoTexts())
                .thenReturn(List.of("promo"));

        ManagerBoardResponse response = service.getBoard(
                "ORDERS",
                " Новый ",
                " needle ",
                -5,
                500,
                "ASC",
                null,
                principal,
                admin
        );

        assertEquals("orders", response.section());
        assertEquals("Новый", response.status());
        assertEquals(List.of(), response.companies().content());
        assertEquals(0, response.companies().number());
        assertEquals(50, response.companies().size());
        assertEquals(List.of(order), response.orders().content());
        assertEquals(1, response.orders().totalElements());
        assertEquals(List.of("promo"), response.promoTexts());
        assertEquals(20, response.metrics().size());
        verify(badReviewTaskService).enrichOrderList(List.of(order));
    }

    @Test
    void getBoardFallsBackToCompaniesSectionForUnknownSection() {
        Principal principal = () -> "admin";
        Authentication admin = authentication("ROLE_ADMIN");
        CompanyListDTO company = CompanyListDTO.builder()
                .id(11L)
                .title("Company")
                .build();

        when(companyService.getAllCompaniesDTOList("query", 1, 10, "desc"))
                .thenReturn(new PageImpl<>(List.of(company), PageRequest.of(1, 10), 12));
        when(companyService.countCompaniesByStatus())
                .thenReturn(Map.of("Новая", 1));
        when(orderService.countOrdersByStatus())
                .thenReturn(Map.of("Новый", 2));
        when(promoTextService.getAllPromoTexts())
                .thenReturn(List.of());

        ManagerBoardResponse response = service.getBoard(
                "missing",
                null,
                " query ",
                1,
                10,
                "sideways",
                null,
                principal,
                admin
        );

        assertEquals("companies", response.section());
        assertEquals("Все", response.status());
        assertEquals(List.of(company), response.companies().content());
        assertEquals(11, response.companies().totalElements());
        assertEquals(List.of(), response.orders().content());
        verify(badReviewTaskService).enrichOrderList(List.of());
    }

    private Authentication authentication(String authority) {
        return new UsernamePasswordAuthenticationToken(
                "user",
                "password",
                List.of(new SimpleGrantedAuthority(authority))
        );
    }
}

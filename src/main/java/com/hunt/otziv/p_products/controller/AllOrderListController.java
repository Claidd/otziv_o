package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.l_lead.services.PromoTextService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.services.service.AllOrderListService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.util.Comparator;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/all_orders_list")
public class AllOrderListController {

    private final PromoTextService promoTextService;
    private final CompanyService companyService;
    private final AllOrderListService allOrderListService;
    private final OrderService orderService;
    private final UserService userService;

    //    =========================================== ORDER ALL =======================================================
    @GetMapping // Страница просмотра всех заказов компании по всем статусам
    public String AllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "все");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword).stream().sorted(Comparator.comparing(OrderDTO::getCreated).reversed()).toList());
//            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword).stream().filter(order -> "Новый".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getCreated).reversed()).toList());
            return "products/all_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "все");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword).stream().sorted(Comparator.comparing(OrderDTO::getCreated).reversed()).toList());
            return "products/all_orders_list";
        }
        else return "products/all_orders_list";
    }

    private String gerRole(Principal principal){
        // Получите текущий объект аутентификации
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Получите имя текущего пользователя (пользователя, не роль)
        String username = principal.getName();
        // Получите роль пользователя (предположим, что она хранится в поле "role" в объекте User)
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    }

//    =========================================== ORDER ALL =======================================================
}


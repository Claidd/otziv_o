package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.l_lead.services.PromoTextService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.services.service.OrderService;
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
@RequestMapping("/orders")
public class AllOrderListController {

    private final PromoTextService promoTextService;
    private final OrderService orderService;


    //    =========================================== ORDER ALL =======================================================
    @GetMapping("/all_orders") // Страница просмотра всех заказов компании по всем статусам
    public String AllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "все");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword).stream().sorted(Comparator.comparing(OrderDTO::getCreated).reversed()).toList());
//            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword).stream().filter(order -> "Новый".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getCreated).reversed()).toList());
            return "products/orders/all_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "все");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword).stream().sorted(Comparator.comparing(OrderDTO::getCreated).reversed()).toList());
            return "products/all_orders_list";
        }
        else return "/companies/allCompany";
    } // Страница просмотра всех заказов компании по всем статусам

    @GetMapping("/new_orders") // Все заказы - Новые
    public String NewAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Новые");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword).stream().filter(order -> "Новый".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getCreated).reversed()).toList());
            return "products/orders/new_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Новые");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword).stream().filter(order -> "Новый".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getCreated).reversed()).toList());
            return "products/orders/new_orders_list";
        }
        if ("ROLE_WORKER".equals(userRole)){
            log.info("Зашли список всех заказов для Работника");
            model.addAttribute("TitleName", "Новые");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByWorker(principal, keyword).stream().filter(order -> "Новые".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getCreated).reversed()).toList());
            return "products/orders/new_orders_list";
        }
        else return "/companies/allCompany";
    } // Все заказы - Новые

    @GetMapping("/to_check") // Все заказы - В проверку
    public String ToCheckAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "В проверку");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword).stream().filter(order -> "В проверку".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/to_check_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "В проверку");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword).stream().filter(order -> "В проверку".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/to_check_orders_list";
        }
        else return "redirect:/companies/allCompany";
    } // Все заказы - В проверку

    @GetMapping("/on_check") // Все заказы - На проверке
    public String OnCheckAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "На проверке");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword).stream().filter(order -> "На проверке".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/on_check_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "На проверке");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword).stream().filter(order -> "На проверке".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/on_check_orders_list";
        }
        else return "redirect:/companies/allCompany";
    } // Все заказы - На проверке

    @GetMapping("/correct") // Все заказы - Коррекция
    public String CorrectAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Коррекция");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword).stream().filter(order -> "Коррекция".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/correct_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Коррекция");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword).stream().filter(order -> "Коррекция".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/correct_orders_list";
        }
        else return "redirect:/companies/allCompany";
    } // Все заказы - Коррекция

    @GetMapping("/to_published") // Все заказы - Публикация
    public String ToPublishedAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Публикация");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword).stream().filter(order -> "Публикация".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/to_published_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Коррекция");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword).stream().filter(order -> "Публикация".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/to_published_orders_list";
        }
        else return "redirect:/companies/allCompany";
    } // Все заказы - Публикация

    @GetMapping("/published") // Все заказы - Опубликовано
    public String PublishedAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Опубликовано");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword).stream().filter(order -> "Опубликовано".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/to_published_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Коррекция");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword).stream().filter(order -> "Опубликовано".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/to_published_orders_list";
        }
        else return "redirect:/companies/allCompany";
    } // Все заказы - Опубликовано

    @GetMapping("/payment_check") // Все заказы - Выставлен счет
    public String PaymentCheckOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Выставлен счет");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword).stream().filter(order -> "Выставлен счет".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/published_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Выставлен счет");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword).stream().filter(order -> "Выставлен счет".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/published_orders_list";
        }
        else return "redirect:/companies/allCompany";
    } // Все заказы - Выставлен счет

    @GetMapping("/remember") // Все заказы - Напоминание
    public String RememberAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Напоминание");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword).stream().filter(order -> "Напоминание".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/remember_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Напоминание");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword).stream().filter(order -> "Напоминание".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/remember_orders_list";
        }
        else return "redirect:/companies/allCompany";
    } // Все заказы - Напоминание

    @GetMapping("/no_pay") // Все заказы - Не оплачено
    public String NoPayAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Не оплачено");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword).stream().filter(order -> "Не оплачено".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/no_pay_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Напоминание");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword).stream().filter(order -> "Не оплачено".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/no_pay_orders_list";
        }
        else return "redirect:/companies/allCompany";
    } // Все заказы - Не оплачено

    @GetMapping("/pay") // Все заказы - Оплачено
    public String PayAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Оплачено");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword).stream().filter(order -> "Оплачено".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged).reversed()).toList());
            return "products/orders/pay_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Оплачено");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword).stream().filter(order -> "Оплачено".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged).reversed()).toList());
            return "products/orders/pay_orders_list";
        }
        else return "redirect:/companies/allCompany";
    } // Все заказы - Оплачено

    private String gerRole(Principal principal){
        // Получите текущий объект аутентификации
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Получите имя текущего пользователя (пользователя, не роль)
        String username = principal.getName();
        // Получите роль пользователя (предположим, что она хранится в поле "role" в объекте User)
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    } // Берем роль пользователя

//    =========================================== ORDER ALL =======================================================
}


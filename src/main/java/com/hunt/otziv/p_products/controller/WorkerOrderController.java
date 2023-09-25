package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.l_lead.model.PromoText;
import com.hunt.otziv.l_lead.services.PromoTextService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.r_review.services.ReviewService;
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
@RequestMapping("/worker")
public class WorkerOrderController {

    private final PromoTextService promoTextService;
    private final OrderService orderService;
    private final ReviewService reviewService;
    private final BotService botService;

//    @GetMapping
//    public String bots(Model model){
//        model.addAttribute("all_bots", botService.getAllBots());
//        return "bots/bots_list";
//    }

    @GetMapping("/bot") // Все заказы - Новые
    public String BotAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("all_bots", botService.getAllBots());
            model.addAttribute("TitleName", "Новые");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());

            return "products/orders/bot_worker";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Новые");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            return "products/orders/bot_worker";
        }
        if ("ROLE_WORKER".equals(userRole)){
            log.info("Зашли список всех заказов для Работника");
            model.addAttribute("TitleName", "Новые");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("all_bots", botService.getAllBotsByWorker(principal));
            return "products/orders/bot_worker";
        }
        else return "redirect:/";
    } // Все заказы - Новые




    @GetMapping("/new_orders") // Все заказы - Новые
    public String NewAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Новые");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword).stream().filter(order -> "Новый".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getCreated).reversed()).toList());
            return "products/orders/new_orders_worker";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Новые");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword).stream().filter(order -> "Новый".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getCreated).reversed()).toList());
            return "products/orders/new_orders_worker";
        }
        if ("ROLE_WORKER".equals(userRole)){
            log.info("Зашли список всех заказов для Работника");
            model.addAttribute("TitleName", "Новые");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByWorker(principal, keyword).stream().filter(order -> "Новый".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getCreated).reversed()).toList());
            return "products/orders/new_orders_worker";
        }
        else return "redirect:/";
    } // Все заказы - Новые

    @GetMapping("/correct") // Все заказы - Коррекция
    public String CorrectAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Коррекция");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword).stream().filter(order -> "Коррекция".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/correct_orders_worker";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Коррекция");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword).stream().filter(order -> "Коррекция".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
            return "products/orders/correct_orders_worker";
        }
        if ("ROLE_WORKER".equals(userRole)){
            log.info("Зашли список всех заказов для Работника");
            model.addAttribute("TitleName", "Коррекция");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByWorker(principal, keyword).stream().filter(order -> "Коррекция".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getCreated).reversed()).toList());
            return "products/orders/new_orders_worker";
        }
        else return "redirect:/";
    } // Все заказы - Коррекция



    @GetMapping("/publish") // Все заказы - Публикация
    public String ToPublishedAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
//            model.addAttribute("reviews", reviewService.getReviewsAllByOrderId(1L));
            model.addAttribute("TitleName", "Публикация");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("reviews", reviewService.getAllReviewDTOAndDateToAdmin());
            return "products/orders/publish_orders_worker";
        }
//        if ("ROLE_MANAGER".equals(userRole)){
//            log.info("Зашли список всех заказов для Менеджера");
//            model.addAttribute("TitleName", "Публикация");
//            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
//            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword).stream().filter(order -> "Публикация".equals(order.getStatus().getTitle())).sorted(Comparator.comparing(OrderDTO::getChanged)).toList());
//            return "products/orders/publish_orders_worker";
//        }
        if ("ROLE_WORKER".equals(userRole)){
            log.info("Зашли список всех заказов для Работника");
            model.addAttribute("TitleName", "Публикация");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("reviews", reviewService.getAllReviewDTOByWorkerByPublish(principal));
            return "products/orders/publish_orders_worker";
        }
        else return "redirect:/";
    } // Все заказы - Публикация

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
            return "products/orders/all_orders_worker";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "все");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword).stream().sorted(Comparator.comparing(OrderDTO::getCreated).reversed()).toList());
            return "products/orders/all_orders_worker";
        }
        if ("ROLE_WORKER".equals(userRole)){
            log.info("Зашли список всех заказов для Работника");
            model.addAttribute("TitleName", "все");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByWorker(principal, keyword).stream().sorted(Comparator.comparing(OrderDTO::getCreated).reversed()).toList());
            return "products/orders/all_orders_worker";
        }
        else return "redirect:/";
    } // Страница просмотра всех заказов компании по всем статусам

    private String gerRole(Principal principal){
        // Получите текущий объект аутентификации
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Получите имя текущего пользователя (пользователя, не роль)
        String username = principal.getName();
        // Получите роль пользователя (предположим, что она хранится в поле "role" в объекте User)
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    } // Берем роль пользователя
}

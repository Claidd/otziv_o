package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.b_bots.dto.BotDTO;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.LocalDate;
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
    private final PersonalService personalService;

    int pageSize = 10; // желаемый размер страницы

    @GetMapping("/bot") // Страница с кнопками "Добавить акк" и "Список всех аккаунтов"
    public String BotAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех ботов для админа");
            model.addAttribute("TitleName", "Аккаунты");
            return "products/orders/bot_worker";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех ботов для Менеджера");
            model.addAttribute("TitleName", "Аккаунты");
            return "products/orders/bot_worker";
        }
        if ("ROLE_WORKER".equals(userRole)){
            log.info("Зашли список всех ботов для Работника" + principal.getName());
            model.addAttribute("TitleName", "Аккаунты");
            return "products/orders/bot_worker";
        }
        else return "redirect:/";
    } // Страница с кнопками "Добавить акк" и "Список всех аккаунтов"

    @GetMapping("/bot_list") // Страница "Список всех аккаунтов"
    public String BotAllList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal){
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("all_bots", botService.getAllBots().stream().sorted(Comparator.comparing(BotDTO:: getFio)));
            model.addAttribute("TitleName", "Список аккаунтов");
            return "products/orders/bot_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Список аккаунтов");
            return "products/orders/bot_list";
        }
        if ("ROLE_WORKER".equals(userRole)){
            log.info("Зашли список всех заказов для Работника" + principal.getName());
            model.addAttribute("TitleName", "Список аккаунтов");
//            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("all_bots", botService.getAllBotsByWorkerActiveIsTrue(principal).stream().sorted(Comparator.comparing(BotDTO :: getFio)));
            return "products/orders/bot_list";
        }
        else return "redirect:/";
    } // Страница "Список всех аккаунтов"


    @GetMapping("/new_orders") // Все заказы - Новые
    public String NewAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Новые");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordAndStatus(keyword, "Новый", pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/new_orders для Админа: ", startTime);
            return "products/orders/new_orders_worker";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Новые");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword,"Новый",pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/new_orders для Менеджера: ", startTime);
            return "products/orders/new_orders_worker";
        }
        if ("ROLE_WORKER".equals(userRole)){
            log.info("Зашли список всех заказов для Работника" + principal.getName());
            model.addAttribute("TitleName", "Новые");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByWorker(principal, keyword,"Новый", pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/new_orders для Работника: ", startTime);
            return "products/orders/new_orders_worker";
        }
        if ("ROLE_OWNER".equals(userRole)){
            log.info("Зашли список всех заказов для Владельца");
            model.addAttribute("TitleName", "Новые");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByOwner(principal, keyword,"Новый",pageNumber, pageSize));
            personalService.getPersonalsAndCountToMap();
            checkTimeMethod("Время выполнения WorkerOrderController/worker/new_orders для Менеджера: ", startTime);
            return "products/orders/new_orders_worker";
        }
        else return "redirect:/";
    } // Все заказы - Новые

    @GetMapping("/correct") // Все заказы - Коррекция
    public String CorrectAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        System.out.println(userRole);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Коррекция");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordAndStatus(keyword, "Коррекция", pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/correct для Админа: ", startTime);
            return "products/orders/correct_orders_worker";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Коррекция");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword,"Коррекция", pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/correct для Менеджера: ", startTime);
            return "products/orders/correct_orders_worker";
        }
        if ("ROLE_WORKER".equals(userRole)){
            log.info("Зашли список всех заказов для Работника");
            model.addAttribute("TitleName", "Коррекция" + principal.getName());
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByWorker(principal, keyword, "Коррекция", pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/correct для Работника: ", startTime);
            return "products/orders/correct_orders_worker";
        }
        if ("ROLE_OWNER".equals(userRole)){
            log.info("Зашли список всех заказов для Владельца");
            model.addAttribute("TitleName", "Коррекция");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByOwner(principal, keyword,"Коррекция",  pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/correct для Владельца: ", startTime);
            return "products/orders/correct_orders_worker";
        }
        else return "redirect:/";
    } // Все заказы - Коррекция

    @GetMapping("/publish") // Все заказы - Публикация
    public String ToPublishedAllOrdersList(@RequestParam(defaultValue = "") String keyword, RedirectAttributes rm, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        System.out.println(userRole);
        LocalDate localDate = LocalDate.now();

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех отзывов к публикации для админа");
//            model.addAttribute("reviews", reviewService.getReviewsAllByOrderId(1L));
            model.addAttribute("TitleName", "Публикация");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("reviews", reviewService.getAllReviewDTOAndDateToAdmin(localDate, pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/publish для Админа: ", startTime);
            return "products/orders/publish_orders_worker";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех отзывов к публикации для Менеджера");
            model.addAttribute("TitleName", "Публикация");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("reviews", reviewService.getAllReviewDTOByManagerByPublish(localDate,principal, pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/publish для Менеджера: ", startTime);
            return "products/orders/publish_orders_worker";
        }
        if ("ROLE_WORKER".equals(userRole)){
            log.info("Зашли список всех отзывов к публикации для Работника" + principal.getName());
            model.addAttribute("TitleName", "Публикация");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("reviews", reviewService.getAllReviewDTOByWorkerByPublish(localDate,principal, pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/publish для Работника: ", startTime);
            return "products/orders/publish_orders_worker";
        }
        if ("ROLE_OWNER".equals(userRole)){
            log.info("Зашли список всех отзывов к публикации для Владельца");
            model.addAttribute("TitleName", "Публикация");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("reviews", reviewService.getAllReviewDTOByOwnerByPublish(localDate, principal, pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/publish для Владельца: ", startTime);
            return "products/orders/publish_orders_worker";
        }
        else return "redirect:/";
    } // Все заказы - Публикация

    @GetMapping("/nagul") // Все заказы - Нагул
    public String ToNagulOrdersList(@RequestParam(defaultValue = "") String keyword, RedirectAttributes rm, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        System.out.println(userRole);
        LocalDate localDate = LocalDate.now();

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех отзывов к нагулу для админа");
//            model.addAttribute("reviews", reviewService.getReviewsAllByOrderId(1L));
            model.addAttribute("TitleName", "Выгул");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("reviews", reviewService.getAllReviewDTOAndDateToAdminToVigul(localDate.plusDays(2), pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/nagul для Админа: ", startTime);
            return "products/orders/nagul_orders_worker";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех отзывов к нагулу для Менеджера");
            model.addAttribute("TitleName", "Выгул");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("reviews", reviewService.getAllReviewDTOByManagerByPublishToVigul(localDate.plusDays(2), principal, pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/nagul для Менеджера: ", startTime);
            return "products/orders/nagul_orders_worker";
        }
        if ("ROLE_WORKER".equals(userRole)){
            log.info("Зашли список всех отзывов к нагулу для Работника" + principal.getName());
            model.addAttribute("TitleName", "Выгул");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("reviews", reviewService.getAllReviewDTOByWorkerByPublishToVigul(localDate.plusDays(2), principal, pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/nagul для Работника: ", startTime);
            return "products/orders/nagul_orders_worker";
        }
        if ("ROLE_OWNER".equals(userRole)){
            log.info("Зашли список всех отзывов к нагулу для Владельца");
            model.addAttribute("TitleName", "Выгул");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("reviews", reviewService.getAllReviewDTOByOwnerByPublishToVigul(localDate.plusDays(2), principal, pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/nagul для Владельца: ", startTime);
            return "products/orders/nagul_orders_worker";
        }
        else return "redirect:/";
    } // Все заказы - Нагул

    @GetMapping("/all_orders") // Страница просмотра всех заказов компании по всем статусам
    public String AllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "все");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword, pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/all_orders для Админа: ", startTime);
            return "products/orders/all_orders_worker";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Все");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManagerAll(principal, keyword, pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/all_orders для Менеджера: ", startTime);
            return "products/orders/all_orders_worker";
        }
        if ("ROLE_WORKER".equals(userRole)){
            log.info("Зашли список всех заказов для Работника" + principal.getName());
            model.addAttribute("TitleName", "Все");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByWorkerAll(principal, keyword, pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/all_orders для Работника: ", startTime);
            return "products/orders/all_orders_worker";
        }
        if ("ROLE_OWNER".equals(userRole)){
            log.info("Зашли список всех заказов для Владельца");
            model.addAttribute("TitleName", "Все");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByOwnerAll(principal, keyword, pageNumber, pageSize));
            checkTimeMethod("Время выполнения WorkerOrderController/worker/all_orders для Владельца: ", startTime);
            return "products/orders/all_orders_worker";
        }
        else return "redirect:/";
    } // Страница просмотра всех заказов компании по всем статусам

    private String gerRole(Principal principal){ // Берем роль пользователя
        // Получите текущий объект аутентификации
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Получите имя текущего пользователя (пользователя, не роль)
        String username = principal.getName();
        // Получите роль пользователя (предположим, что она хранится в поле "role" в объекте User)
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    } // Берем роль пользователя

    private void checkTimeMethod(String text, long startTime){
        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        System.out.printf(text + "%.4f сек%n", timeElapsed);
    }
}

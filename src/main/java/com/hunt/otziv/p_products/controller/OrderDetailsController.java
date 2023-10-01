package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.r_review.services.ReviewArchiveService;
import com.hunt.otziv.r_review.services.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.Comparator;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/ordersDetails")
public class OrderDetailsController {

    private final ReviewService reviewService;
    private final OrderService orderService;
    private final ReviewArchiveService reviewArchiveService;

    @GetMapping("/{companyId}/{orderId}") // Переход на страницу Просмотра  деталей заказа
    public String orderDetailsList(@PathVariable Long companyId, @PathVariable Long orderId, RedirectAttributes rm, Model model){
        model.addAttribute("companyId", companyId);
        model.addAttribute("orderId", orderId);
        model.addAttribute("reviews", reviewService.getReviewsAllByOrderId(orderId));
//        System.out.println(reviewService.getReviewsAllByOrderId(orderId));
        rm.addFlashAttribute("companyId", companyId);
        rm.addFlashAttribute("orderId", orderId);
        return "products/orders_detail_list";
    }

    @PostMapping("/{companyId}/{orderId}/change_bot/{reviewId}") // Замена Бота
    public String changeBot(@RequestParam(defaultValue = "") String pageName, @PathVariable Long reviewId, @PathVariable Long companyId, @PathVariable Long orderId, Model model){
        System.out.println(pageName);
        log.info("1. Заходим в Post метод замены бота");
        reviewService.changeBot(reviewId);
        log.info("5. Все прошло успешно, вернулись в контроллер");
//
        // Build the redirect URL by appending the context path

        if ("Работник_Публикация".equals(pageName)){ // возврат на страницу публикации с Рабочего
            log.info("Зашли список всех заказов для админа");
            return "redirect:/worker/publish";
        }
        if ("Заказ_Отзыв".equals(pageName)){ // возврат на страницу редактирования отзыва из отзыва
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("companyID", companyId);
            model.addAttribute("orderID", orderId);
            model.addAttribute("reviewID", orderId);
            return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
        }
        return "redirect:/worker/publish";
//        return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
    }

    @PostMapping("/{companyId}/{orderId}/deactivate_bot/{reviewId}/{botId}")
    public String deActivateBot(@PathVariable Long reviewId, @PathVariable Long companyId, @PathVariable Long orderId, @PathVariable Long botId, Model model){
        log.info("1. Заходим в Post метод замены бота");
        reviewService.deActivateAndChangeBot(reviewId, botId);
        log.info("6. Все прошло успешно, вернулись в контроллер");
        model.addAttribute("companyID", companyId);
        model.addAttribute("orderID", orderId);
        return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
    }

    @PostMapping("/{companyId}/{orderId}/published/{reviewId}") // Изменение статуса отзыва и сохранение копии в архив + проверка на выполнение заказа.
    public String publishReview(@PathVariable Long reviewId,@PathVariable Long companyId, RedirectAttributes rm, @PathVariable Long orderId, Model model){
        log.info("1. Заходим в Post метод Изменение статуса");
        if(orderService.changeStatusAndOrderCounter(reviewId)){
            log.info("8. Все прошло успешно, вернулись в контроллер");
            rm.addFlashAttribute("saveSuccess", "true");
        }
        else {
            log.info("8. Все прошло плохо, вернулись в контроллер");
//            rm.addFlashAttribute("saveSuccess", "false");
        }
        return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
    }

    @PostMapping("/{companyId}/{orderId}/published_to_worker/{reviewId}") // Изменение статуса отзыва и сохранение копии в архив + проверка на выполнение заказа.
    public String publishReviewToWorker(@PathVariable Long reviewId,@PathVariable Long companyId, RedirectAttributes rm, @PathVariable Long orderId, Model model){
        log.info("1. Заходим в Post метод Изменение статуса");
        if(orderService.changeStatusAndOrderCounter(reviewId)){
            log.info("8. Все прошло успешно, вернулись в контроллер");
            rm.addFlashAttribute("saveSuccess", "true");
        }
        else {
            log.info("8. Все прошло плохо, вернулись в контроллер");
//            rm.addFlashAttribute("saveSuccess", "false");
        }
        return "redirect:/worker/publish";
    }


    private String gerRole(Principal principal){
        // Получите текущий объект аутентификации
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Получите имя текущего пользователя (пользователя, не роль)
        String username = principal.getName();
        // Получите роль пользователя (предположим, что она хранится в поле "role" в объекте User)
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    } // Берем роль пользователя


}

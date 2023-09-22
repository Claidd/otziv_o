package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.r_review.services.ReviewArchiveService;
import com.hunt.otziv.r_review.services.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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

    @PostMapping("/{companyId}/{orderId}/change_bot/{reviewId}")
    public String changeBot(@PathVariable Long reviewId,@PathVariable Long companyId, @PathVariable Long orderId, Model model){
        log.info("1. Заходим в Post метод замены бота");
        reviewService.changeBot(reviewId);
        log.info("5. Все прошло успешно, вернулись в контроллер");
        model.addAttribute("companyID", companyId);
        model.addAttribute("orderID", orderId);
        model.addAttribute("reviewID", orderId);
        // Build the redirect URL by appending the context path
        return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
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
//            checkOrderOnPublishedAll(orderId);
        }
        else {
            log.info("8. Все прошло плохо, вернулись в контроллер");
//            rm.addFlashAttribute("saveSuccess", "false");
        }
        return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
    }


}

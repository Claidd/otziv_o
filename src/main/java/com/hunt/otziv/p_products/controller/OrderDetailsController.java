package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.r_review.services.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/ordersDetails")
public class OrderDetailsController {

    private final ReviewService reviewService;

    @GetMapping("/{companyID}/{orderID}")
    public String orderDetailsList(@PathVariable Long companyID, @PathVariable Long orderID, Model model){
        model.addAttribute("companyID", companyID);
        model.addAttribute("orderID", orderID);
        model.addAttribute("reviews", reviewService.getReviewsAllByOrderId(orderID));
        return "products/orders_detail_list";
    }

    @PostMapping("/{companyID}/{orderID}/change_bot/{reviewId}")
    public String changeBot(@PathVariable Long reviewId,@PathVariable Long companyID, @PathVariable Long orderID, Model model){
        log.info("1. Заходим в Post метод замены бота");
        reviewService.changeBot(reviewId);
        log.info("5. Все прошло успешно, вернулись в контроллер");
        model.addAttribute("companyID", companyID);
        model.addAttribute("orderID", orderID);
        model.addAttribute("reviewID", orderID);
        // Build the redirect URL by appending the context path
        return String.format("redirect:/ordersDetails/%s/%s", companyID, orderID);
    }

    @PostMapping("/{companyID}/{orderID}/deactivate_bot/{reviewId}/{botId}")
    public String deActivateBot(@PathVariable Long reviewId, @PathVariable Long companyID, @PathVariable Long orderID, @PathVariable Long botId, Model model){
        log.info("1. Заходим в Post метод замены бота");
        reviewService.deActivateAndChangeBot(reviewId, botId);
        log.info("6. Все прошло успешно, вернулись в контроллер");
        model.addAttribute("companyID", companyID);
        model.addAttribute("orderID", orderID);
        // Build the redirect URL by appending the context path
        return String.format("redirect:/ordersDetails/%s/%s", companyID, orderID);
    }
}

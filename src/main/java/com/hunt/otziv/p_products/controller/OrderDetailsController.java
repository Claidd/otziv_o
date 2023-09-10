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

    @PostMapping("/{companyID}/{orderID}/change_bot/{id}")
    public String changeBot(@PathVariable Long id,@PathVariable Long companyID, @PathVariable Long orderID, Model model){
        log.info("1. Заходим в Post метод замены бота");
        reviewService.changeBot(id);
        log.info("4. Все прошло успешно, вернулись в контроллер");
        model.addAttribute("companyID", companyID);
        model.addAttribute("orderID", id);
        // Build the redirect URL by appending the context path
        String redirectUrl = String.format("redirect:/ordersDetails/%s/%s", companyID, orderID);
        return redirectUrl;
    }
}

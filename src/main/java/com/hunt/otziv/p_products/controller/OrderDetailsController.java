package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.r_review.services.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequiredArgsConstructor
@RequestMapping("/ordersDetails")
public class OrderDetailsController {

    private final ReviewService reviewService;
    @GetMapping("/{companyID}/{id}")
    public String orderDetailsList(@PathVariable Long companyID, @PathVariable Long id, Model model){
        model.addAttribute("companyID", companyID);
        model.addAttribute("reviews", reviewService.getReviewsAllByOrderId(id));
        return "products/orders_detail_list";
    }
}

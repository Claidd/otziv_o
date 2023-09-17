package com.hunt.otziv.r_review.controller;

import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.services.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/review")
public class ReviewController {

    private final ReviewService reviewService;

    //    =========================================== ORDER EDIT =======================================================
    @GetMapping("/editReview/{reviewId}") // Страница редактирования Заказа - Get
    String ReviewEdit(@PathVariable Long reviewId, Model model){
        System.out.println(reviewService.getReviewDTOById(reviewId));
        ReviewDTO reviewDTO = reviewService.getReviewDTOById(reviewId);
        model.addAttribute("reviewDTO", reviewDTO);
        model.addAttribute("companyId", reviewDTO.getOrderDetails().getOrder().getId());
        model.addAttribute("orderId", reviewDTO.getOrderDetails().getOrder().getCompany().getId());
        return "products/review_edit";
    }

    @PostMapping("/editReview/{reviewId}") // Страница редактирования Заказа - Post
    String ReviewEditPost(@ModelAttribute("reviewDTO") ReviewDTO reviewDTO, @PathVariable Long reviewId, RedirectAttributes rm, Model model){
        log.info("1. Начинаем обновлять данные Отзыва");
        reviewService.updateReview(reviewDTO, reviewId);
        log.info("5. Обновление Отзыва прошло успешно");
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/review/editReview/{reviewId}";
    }


//    ==========================================================================================================
}

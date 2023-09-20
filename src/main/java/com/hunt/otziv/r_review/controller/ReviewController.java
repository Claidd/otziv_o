package com.hunt.otziv.r_review.controller;

import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
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
    private final OrderDetailsService orderDetailsService;
    private final OrderService orderService;

    //    =========================================== REVIEW EDIT =======================================================
    @GetMapping("/editReview/{reviewId}") // Страница редактирования Заказа - Get
    String ReviewEdit(@PathVariable Long reviewId, Model model){
        System.out.println(reviewService.getReviewDTOById(reviewId));
        ReviewDTO reviewDTO = reviewService.getReviewDTOById(reviewId);
        model.addAttribute("reviewDTO", reviewDTO);
        model.addAttribute("companyId", reviewDTO.getOrderDetails().getOrder().getCompany().getId());
        model.addAttribute("orderId", reviewDTO.getOrderDetails().getOrder().getId());
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

    @PostMapping("/addReviews/{companyId}/{orderId}") // Добавить новый отзыв - Post
    String ReviewAdd(@PathVariable Long orderId, @PathVariable Long companyId, RedirectAttributes rm, Model model){
        log.info("1. Начинаем добавлять новый Отзыв");
        if (orderService.addNewReview(orderId)){
            log.info("5. Добавили новый отзыв");
            rm.addFlashAttribute("saveSuccess", "true");
            return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
        }
        log.info("3. Неудачная попытка");
        return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
    }

    @GetMapping("/deleteReviews/{companyId}/{orderId}/{reviewId}") // Добавить новый отзыв - Post
    String ReviewDelete(@PathVariable Long orderId, @PathVariable Long companyId, @PathVariable Long reviewId, RedirectAttributes rm, Model model){
        log.info("1. Начинаем удалять новый Отзыв");
        if (reviewService.deleteReview(reviewId)){
            log.info("2. Удалили отзыв");
            rm.addFlashAttribute("saveSuccess", "true");
            return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
        }
        log.info("2. Неудачная попытка удаления");
        return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
    }


//    ==========================================================================================================

    //    =========================================== REVIEW'S EDIT =======================================================
    @GetMapping("/editReviews/{orderDetailId}") // Страница редактирования Заказа - Get
    String ReviewsEdit(@PathVariable Long orderDetailId, Model model){

        OrderDetailsDTO orderDetailsDTO = orderDetailsService.getOrderDetailDTOById(orderDetailId);
        model.addAttribute("orderDetailDTO", orderDetailsDTO);
        model.addAttribute("orderDetailId", orderDetailId);
        System.out.println(orderDetailsDTO);


        return "products/reviews_edit";
    }

    @PostMapping("/editReviews/{orderDetailId}") // Страница редактирования Заказа - Post - СОХРАНИТЬ
    String ReviewsEditPost(@ModelAttribute("orderDetailDTO") OrderDetailsDTO orderDetailDTO, RedirectAttributes rm, Model model){
        log.info("1. Начинаем обновлять данные Отзыва");
        for (ReviewDTO reviewDTO: orderDetailDTO.getReviews()) {
            reviewService.updateOrderDetailAndReview(orderDetailDTO, reviewDTO, reviewDTO.getId());
        }
        log.info("5. Обновление Отзыва прошло успешно");
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/review/editReviews/{orderDetailId}";
    }

    @PostMapping("/editReviews/{orderDetailId}/publish") // Страница редактирования Заказа - Post - ОПУБЛИКОВАТЬ
    String ReviewsEditPostToPublish(@ModelAttribute("orderDetailDTO") OrderDetailsDTO orderDetailDTO, RedirectAttributes rm, Model model){
        log.info("1. Начинаем обновлять данные Отзыва3");
        for (ReviewDTO reviewDTO: orderDetailDTO.getReviews()) {
            reviewService.updateOrderDetailAndReview(orderDetailDTO, reviewDTO, reviewDTO.getId());
        }
        log.info("Начинаем обновлять статус заказа");
        orderService.changeStatusForOrder(orderDetailDTO.getOrder().getId(), "Публикация");
        log.info("Обновили статус заказа");
        log.info("5. Обновление Отзыва прошло успешно3");
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/review/editReviews/{orderDetailId}";
    }

    @PostMapping("/editReviewses/{orderDetailId}") // Страница редактирования Заказа - Post - КОРРЕКТИРОВАТЬ
    String ReviewsEditPost2(@ModelAttribute("orderDetailDTO") OrderDetailsDTO orderDetailDTO, RedirectAttributes rm, Model model){
        log.info("1. Начинаем обновлять данные Отзыва2");
        for (ReviewDTO reviewDTO: orderDetailDTO.getReviews()) {
            reviewService.updateOrderDetailAndReview(orderDetailDTO, reviewDTO, reviewDTO.getId());
        }
        log.info("Начинаем обновлять статус заказа");
        orderService.changeStatusForOrder(orderDetailDTO.getOrder().getId(), "Коррекция");
        log.info("Обновили статус заказа");
        log.info("5. Обновление Отзыва прошло успешно2");
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/review/editReviews/{orderDetailId}";
    }


//    ==========================================================================================================



}

package com.hunt.otziv.r_review.controller;

import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.services.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/review")
public class ReviewController {

    private final ReviewService reviewService;
    private final OrderDetailsService orderDetailsService;
    private final OrderService orderService;

    //    =========================================== REVIEW EDIT =======================================================
    @GetMapping("/editReview/{reviewId}")
    String ReviewEdit(@PathVariable Long reviewId, Model model){
        System.out.println("Вошли в обновление " + reviewService.getReviewDTOById(reviewId));
        ReviewDTO reviewDTO = reviewService.getReviewDTOById(reviewId);
        System.out.println(reviewDTO.getOrderDetailsId());
        model.addAttribute("reviewDTO", reviewDTO);
        model.addAttribute("companyId", reviewDTO.getOrderDetails().getOrder().getCompany().getId());
        model.addAttribute("orderId", reviewDTO.getOrderDetails().getOrder().getId());
        return "products/review_edit";
    } // Страница редактирования Заказа - Get

    @PostMapping("/editReview/{reviewId}") // Страница редактирования Заказа - Post
    String ReviewEditPost(@ModelAttribute("reviewDTO") ReviewDTO reviewDTO, @PathVariable Long reviewId, RedirectAttributes rm, Model model){
        log.info("1. Начинаем обновлять данные отзыва");
        reviewService.updateReview(reviewDTO, reviewId);
        log.info("5. Обновление отзыва прошло успешно");
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/review/editReview/{reviewId}";
    } // Страница редактирования Заказа - Post

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
    } // Добавить новый отзыв - Post


    @PostMapping("/deleteReviews/{companyId}/{orderId}/{reviewId}") // Удалить отзыв - Post
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_OWNER', 'ROLE_MANAGER', 'ROLE_WORKER')")
    String ReviewDelete(@PathVariable Long orderId, @PathVariable Long companyId, @PathVariable Long reviewId, RedirectAttributes rm, Model model){
        log.info("1. Начинаем удалять новый Отзыв");
        if (orderService.deleteNewReview(orderId, reviewId)){
            log.info("2. Удалили отзыв");
            rm.addFlashAttribute("saveSuccess", "true");
            return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
        }
        log.info("2. Неудачная попытка удаления");
        return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
    } // Удалить отзыв - Post


//    ==========================================================================================================

    //    =========================================== REVIEW'S EDIT =======================================================
    @GetMapping("/editReviews/{orderDetailId}") // Страница редактирования Заказа - Get
    String ReviewsEdit(@PathVariable UUID orderDetailId, Model model){
        long startTime = System.nanoTime();
        OrderDetailsDTO orderDetailsDTO = orderDetailsService.getOrderDetailDTOById(orderDetailId);
        if (orderDetailsDTO.getReviews().isEmpty()) {
            model.addAttribute("orderDetailDTO", orderDetailsDTO);
            model.addAttribute("orderDetailId", orderDetailId);
//            model.addAttribute("statusCheck", orderDetailsDTO.getReviews().get(0).getPublishedDate());
            model.addAttribute("errorMessage", "Список отзывов пуст. Сообщите менеджеру об этом");
            checkTimeMethod("Время выполнения страницы проверки отзыов для клиента /review/editReviews/{orderDetailId} для всех: ", startTime);
            return "products/reviews_edit";
        } else {
            model.addAttribute("orderDetailDTO", orderDetailsDTO);
            model.addAttribute("orderDetailId", orderDetailId);
            model.addAttribute("statusCheck", orderDetailsDTO.getReviews().get(0).getPublishedDate());
//        model.addAttribute("address", orderDetailsDTO.getOrder().getFilial().getTitle());
            checkTimeMethod("Время выполнения страницы проверки отзыов для клиента /review/editReviews/{orderDetailId} для всех: ", startTime);
            return "products/reviews_edit";
        }

    } // Страница редактирования Заказа - Get

    @PostMapping("/editReviews/{orderDetailId}") // Страница редактирования Заказа - Post - СОХРАНИТЬ
    String ReviewsEditPost(@ModelAttribute("orderDetailDTO") OrderDetailsDTO orderDetailDTO, RedirectAttributes rm, Model model){
        log.info("1. Начинаем обновлять данные Отзыва");
        for (ReviewDTO reviewDTO: orderDetailDTO.getReviews()) {
            reviewService.updateOrderDetailAndReview(orderDetailDTO, reviewDTO, reviewDTO.getId());
        }
        log.info("5. Обновление Отзыва прошло успешно");
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/review/editReviews/{orderDetailId}";
    } // Страница редактирования Заказа - Post - СОХРАНИТЬ

    @PostMapping("/editReviews/{orderDetailId}/publish") // Страница редактирования Заказа - Post - ОПУБЛИКОВАТЬ
    String ReviewsEditPostToPublish(@ModelAttribute("orderDetailDTO") OrderDetailsDTO orderDetailDTO, RedirectAttributes rm, Model model){
        log.info("1. Начинаем обновлять данные Отзыва3");
        if (reviewService.updateOrderDetailAndReviewAndPublishDate(orderDetailDTO)){
            log.info("Начинаем обновлять статус заказа");
            orderService.changeStatusForOrder(orderDetailDTO.getOrder().getId(), "Публикация");
            log.info("Обновили статус заказа");
            log.info("5. Обновление Отзыва прошло успешно3");
            rm.addFlashAttribute("saveSuccess", "true");
            Long companyId = orderDetailDTO.getOrder().getCompany().getId();
            return "redirect:/review/editReviews/{orderDetailId}";
//            return "redirect:/ordersCompany/ordersDetails/" + companyId;
        }
        else {
            log.info("2. Произошла какая-то ошибка");
            return "redirect:/review/editReviews/{orderDetailId}";
        }
    } // Страница редактирования Заказа - Post - ОПУБЛИКОВАТЬ

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
    } // Страница редактирования Заказа - Post - КОРРЕКТИРОВАТЬ


//    ==========================================================================================================
private void checkTimeMethod(String text, long startTime){
    long endTime = System.nanoTime();
    double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
    System.out.printf(text + "%.4f сек%n", timeElapsed);
}


}

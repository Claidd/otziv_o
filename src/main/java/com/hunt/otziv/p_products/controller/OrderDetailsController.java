package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.exceptions.BotTemplateNameException;
import com.hunt.otziv.exceptions.NagulTooFastException;
import com.hunt.otziv.p_products.dto.NagulResult;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.services.ReviewArchiveService;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.text_generator.service.AutoTextService;
import com.hunt.otziv.text_generator.service.toGPT.ReviewGeneratorService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/ordersDetails")
public class OrderDetailsController {

    private final ReviewService reviewService;
    private final OrderService orderService;
    private final AutoTextService autoTextService;

    @GetMapping("/{companyId}/{orderId}") // Переход на страницу Просмотра  деталей заказа
    public String orderDetailsList(@PathVariable Long companyId, @PathVariable Long orderId, RedirectAttributes rm, Model model, Principal principal){
        long startTime = System.nanoTime();
        List<ReviewDTOOne> reviews = reviewService.getReviewsAllByOrderId(orderId);
        if (reviews.isEmpty()) {
            model.addAttribute("errorMessage", "Список отзывов пуст. Сообщите менеджеру об этом");
            model.addAttribute("reviews", reviews);
            checkTimeMethod("Время выполнения OrderDetailsController/ordersDetails/{companyId}/{orderId} для Всех: ", startTime, principal);
            return "products/orders_detail_list";
        } else {
            model.addAttribute("reviews", reviews);
            checkTimeMethod("Время выполнения OrderDetailsController/ordersDetails/{companyId}/{orderId} для Всех: ", startTime, principal);
            return "products/orders_detail_list";
        }
    } // Переход на страницу Просмотра  деталей заказа

    @PostMapping("/changeText/{companyId}/{orderId}/{reviewId}")
    public String changeReviewText(@PathVariable Long companyId, @PathVariable Long orderId, @PathVariable Long reviewId,RedirectAttributes rm, Model model, Principal principal){
        log.info("Смена текста отзыва для - {}", principal != null ? principal.getName() : "Гость");
        long startTime = System.nanoTime();
        if (autoTextService.changeReviewText(reviewId)){
            rm.addFlashAttribute("saveSuccess", "true");
            checkTimeMethod("Смена текста OrderDetailsController/ordersDetails/{companyId}/{orderId} для Всех: ", startTime, principal);
            return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
        }
        else {
            checkTimeMethod("Смена текста OrderDetailsController/ordersDetails/{companyId}/{orderId} для Всех: ", startTime, principal);
            rm.addFlashAttribute("saveSuccess", "false");
            return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
        }

    }


    @PostMapping("/{orderId}/change_bot/{reviewId}")
    public String changeBot(
            @RequestParam(defaultValue = "") String pageName,
            @RequestParam(defaultValue = "0") int pageNumber,
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            Principal principal,
            Model model) {
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        int pageSize = 10; // желаемый размер страницы
        LocalDate localDate = LocalDate.now();
        reviewService.changeBot(reviewId);
//        System.out.println(pageName);

        if ("Заказ_Отзыв".equals(pageName)){ // возврат на страницу редактирования отзыва из отзыва
            log.info("Зашли список всех заказов для Менеджера");
            List<ReviewDTOOne> updatedReviews = reviewService.getReviewsAllByOrderId(orderId);
            model.addAttribute("reviews", updatedReviews);
            checkTimeMethod("Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  orders_detail_list + fragments/reviews_to_order", startTime, principal);
            return "fragments/reviews_to_order :: reviews_to_order";
        }
        if ("Работник_Публикация".equals(pageName)){ // возврат на страницу публикации с Рабочего
            log.info("СМЕНА Бота со страницы Публикация - {}", principal != null ? principal.getName() : "Гость");
            if ("ROLE_ADMIN".equals(userRole)){
                Page<ReviewDTOOne> updatedReviews = reviewService.getAllReviewDTOAndDateToAdmin(localDate, pageNumber, pageSize);
                model.addAttribute("reviews", updatedReviews);
                checkTimeMethod("Админ Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  publish_orders_worker + fragments/reviews_to_worker ", startTime, principal);
                return "fragments/reviews_to_worker :: reviews_to_worker";
            }

            if ("ROLE_MANAGER".equals(userRole)){
                Page<ReviewDTOOne> updatedReviews = reviewService.getAllReviewDTOByManagerByPublish(localDate,principal, pageNumber, pageSize);
                model.addAttribute("reviews", updatedReviews);
                checkTimeMethod("Менеджер Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  publish_orders_worker + fragments/reviews_to_worker ", startTime, principal);
                return "fragments/reviews_to_worker :: reviews_to_worker";
            }

            if ("ROLE_WORKER".equals(userRole)) {
                log.info("Зашли список всех заказов для работника - {}", principal != null ? principal.getName() : "Гость");
                Page<ReviewDTOOne> updatedReviews = reviewService.getAllReviewDTOByWorkerByPublish(localDate, principal, pageNumber, pageSize);
                model.addAttribute("reviews", updatedReviews);
                checkTimeMethod("Работник Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  publish_orders_worker + fragments/reviews_to_worker ", startTime, principal);
                return "fragments/reviews_to_worker :: reviews_to_worker";
            }

            if ("ROLE_OWNER".equals(userRole)){
                Page<ReviewDTOOne> updatedReviews = reviewService.getAllReviewDTOByOwnerByPublish(localDate, principal, pageNumber, pageSize);
                model.addAttribute("reviews", updatedReviews);
                checkTimeMethod("Владелец Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  publish_orders_worker + fragments/reviews_to_worker ", startTime, principal);
                return "fragments/reviews_to_worker :: reviews_to_worker";
            }
        }

        if ("Выгул".equals(pageName)) { // возврат на страницу публикации с Рабочего
            log.info("СМЕНА Бота со страницы Выгул - {}", principal != null ? principal.getName() : "Гость");
            if ("ROLE_ADMIN".equals(userRole)){
                Page<ReviewDTOOne> updatedReviews = reviewService.getAllReviewDTOAndDateToAdminToVigul(localDate.plusDays(2), pageNumber, pageSize);
                model.addAttribute("reviews", updatedReviews);
                checkTimeMethod("Админ Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  publish_orders_worker + fragments/reviews_to_worker ", startTime, principal);
                return "fragments/nagul_to_worker :: nagul_to_worker";
            }

            if ("ROLE_MANAGER".equals(userRole)){
                Page<ReviewDTOOne> updatedReviews = reviewService.getAllReviewDTOByManagerByPublishToVigul(localDate.plusDays(2), principal, pageNumber, pageSize);
                model.addAttribute("reviews", updatedReviews);
                checkTimeMethod("Менеджер Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  publish_orders_worker + fragments/reviews_to_worker ", startTime, principal);
                return "fragments/nagul_to_worker :: nagul_to_worker";
            }

            if ("ROLE_WORKER".equals(userRole)) {
                log.info("Зашли список всех заказов для работника - {}", principal != null ? principal.getName() : "Гость");
                Page<ReviewDTOOne> updatedReviews = reviewService.getAllReviewDTOByWorkerByPublishToVigul(localDate.plusDays(2), principal, pageNumber, pageSize);
                model.addAttribute("reviews", updatedReviews);
                checkTimeMethod("Работник Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  publish_orders_worker + fragments/reviews_to_worker ", startTime, principal);
                return "fragments/nagul_to_worker :: nagul_to_worker";
            }

            if ("ROLE_OWNER".equals(userRole)){
                Page<ReviewDTOOne> updatedReviews = reviewService.getAllReviewDTOByOwnerByPublishToVigul(localDate.plusDays(2), principal, pageNumber, pageSize);
                model.addAttribute("reviews", updatedReviews);
                checkTimeMethod("Владелец Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  publish_orders_worker + fragments/reviews_to_worker ", startTime, principal);
                return "fragments/nagul_to_worker :: nagul_to_worker";
            }
        }
        return "fragments/reviews_to_worker :: reviews_to_worker";
    }

    @PostMapping("/{orderId}/deactivate_bot/{reviewId}/{botId}") // Деактивация и замена Бота
    public String deActivateBot(@RequestParam(defaultValue = "0") int pageNumber, @RequestParam(defaultValue = "") String pageName, Principal principal,@PathVariable Long reviewId, @PathVariable Long orderId, @PathVariable Long botId, Model model){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);
        int pageSize = 10; // желаемый размер страницы
        LocalDate localDate = LocalDate.now();
        reviewService.deActivateAndChangeBot(reviewId, botId);

        if ("Заказ_Отзыв".equals(pageName)){ // возврат на страницу редактирования отзыва из отзыва
            log.info("Зашли список всех заказов для Менеджера  - {}", principal != null ? principal.getName() : "Гость");
            List<ReviewDTOOne> updatedReviews = reviewService.getReviewsAllByOrderId(orderId);
            model.addAttribute("reviews", updatedReviews);
            checkTimeMethod("Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  orders_detail_list + fragments/reviews_to_order", startTime, principal);
            return "fragments/reviews_to_order :: reviews_to_order";
        }
        if ("Работник_Публикация".equals(pageName)){ // возврат на страницу публикации с Рабочего
            log.info("Деактивация Бота со страницы Публикация - {}", principal != null ? principal.getName() : "Гость");
            if ("ROLE_ADMIN".equals(userRole)){
                Page<ReviewDTOOne> updatedReviews = reviewService.getAllReviewDTOAndDateToAdmin(localDate, pageNumber, pageSize);
                model.addAttribute("reviews", updatedReviews);
                checkTimeMethod("Админ Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  publish_orders_worker + fragments/reviews_to_worker ", startTime, principal);
                return "fragments/reviews_to_worker :: reviews_to_worker";
            }

            if ("ROLE_MANAGER".equals(userRole)){
                Page<ReviewDTOOne> updatedReviews = reviewService.getAllReviewDTOByManagerByPublish(localDate,principal, pageNumber, pageSize);
                model.addAttribute("reviews", updatedReviews);
                checkTimeMethod("Менеджер Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  publish_orders_worker + fragments/reviews_to_worker ", startTime, principal);
                return "fragments/reviews_to_worker :: reviews_to_worker";
            }

            if ("ROLE_WORKER".equals(userRole)) {
                log.info("Зашли список всех заказов для работника - {}", principal != null ? principal.getName() : "Гость");
                Page<ReviewDTOOne> updatedReviews = reviewService.getAllReviewDTOByWorkerByPublish(localDate, principal, pageNumber, pageSize);
                model.addAttribute("reviews", updatedReviews);
                checkTimeMethod("Работник Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  publish_orders_worker + fragments/reviews_to_worker ", startTime, principal);
                return "fragments/reviews_to_worker :: reviews_to_worker";
            }

            if ("ROLE_OWNER".equals(userRole)){
                Page<ReviewDTOOne> updatedReviews = reviewService.getAllReviewDTOByOwnerByPublish(localDate, principal, pageNumber, pageSize);
                model.addAttribute("reviews", updatedReviews);
                checkTimeMethod("Владелец Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  publish_orders_worker + fragments/reviews_to_worker ", startTime, principal);
                return "fragments/reviews_to_worker :: reviews_to_worker";
            }
        }

        if ("Выгул".equals(pageName)) { // возврат на страницу публикации с Рабочего
            log.info("Деактивация Бота со страницы Выгул - {}", principal != null ? principal.getName() : "Гость");
            if ("ROLE_ADMIN".equals(userRole)){
                Page<ReviewDTOOne> updatedReviews = reviewService.getAllReviewDTOAndDateToAdminToVigul(localDate.plusDays(2), pageNumber, pageSize);
                model.addAttribute("reviews", updatedReviews);
                checkTimeMethod("Админ Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  publish_orders_worker + fragments/reviews_to_worker ", startTime, principal);
                return "fragments/nagul_to_worker :: nagul_to_worker";
            }

            if ("ROLE_MANAGER".equals(userRole)){
                Page<ReviewDTOOne> updatedReviews = reviewService.getAllReviewDTOByManagerByPublishToVigul(localDate.plusDays(2), principal, pageNumber, pageSize);
                model.addAttribute("reviews", updatedReviews);
                checkTimeMethod("Менеджер Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  publish_orders_worker + fragments/reviews_to_worker ", startTime, principal);
                return "fragments/nagul_to_worker :: nagul_to_worker";
            }

            if ("ROLE_WORKER".equals(userRole)) {
                log.info("Зашли список всех заказов для работника - {}", principal != null ? principal.getName() : "Гость");
                Page<ReviewDTOOne> updatedReviews = reviewService.getAllReviewDTOByWorkerByPublishToVigul(localDate.plusDays(2), principal, pageNumber, pageSize);
                model.addAttribute("reviews", updatedReviews);
                checkTimeMethod("Работник Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  publish_orders_worker + fragments/reviews_to_worker ", startTime, principal);
                return "fragments/nagul_to_worker :: nagul_to_worker";
            }

            if ("ROLE_OWNER".equals(userRole)){
                Page<ReviewDTOOne> updatedReviews = reviewService.getAllReviewDTOByOwnerByPublishToVigul(localDate.plusDays(2), principal, pageNumber, pageSize);
                model.addAttribute("reviews", updatedReviews);
                checkTimeMethod("Владелец Время выполнения OrderDetailsController/ordersDetails/{orderId}/change_bot/{reviewId} для замены бота:  publish_orders_worker + fragments/reviews_to_worker ", startTime, principal);
                return "fragments/nagul_to_worker :: nagul_to_worker";
            }
        }
        return "fragments/reviews_to_worker :: reviews_to_worker";
    } // Деактивация и замена Бота




    @PostMapping("/{companyId}/{orderId}/published/{reviewId}") // Изменение статуса отзыва и сохранение копии в архив + проверка на выполнение заказа.
    public String publishReview(@PathVariable Long reviewId,@PathVariable Long companyId, RedirectAttributes rm, @PathVariable Long orderId, Model model, Principal principal){
        log.info("1. Смена статуса отзыва на В Опубликован - {}", principal != null ? principal.getName() : "Гость");
        try {
            orderService.changeStatusAndOrderCounter(reviewId);
            rm.addFlashAttribute("saveSuccess", "true");
            rm.addFlashAttribute("errorMessage", null); // Убираем сообщение об ошибке
        } catch (Exception e) {
            log.info("8. Все прошло плохо, вернулись в контроллер");
            rm.addFlashAttribute("saveSuccess", "false");
            rm.addFlashAttribute("errorMessage", "Сообщите менеджеру. Не удалось изменить статус и сохранить изменения, отзыв НЕ ОПУБЛИКОВАН.");
        }
        return String.format("redirect:/ordersDetails/%s/%s", companyId, orderId);
    } // Изменение статуса отзыва и сохранение копии в архив + проверка на выполнение заказа.

    @PostMapping("/{companyId}/{orderId}/published_to_worker/{reviewId}") // Изменение статуса отзыва и сохранение копии в архив + проверка на выполнение заказа.
    public String publishReviewToWorker(@PathVariable Long reviewId,@PathVariable Long companyId, RedirectAttributes rm, @PathVariable Long orderId, Model model, Principal principal){
        log.info("1. Смена статуса отзыва на Опубликован - {}", principal != null ? principal.getName() : "Гость");
        try {
            orderService.changeStatusAndOrderCounter(reviewId);
            rm.addFlashAttribute("saveSuccess", "true");
            rm.addFlashAttribute("errorMessage", null); // Убираем сообщение об ошибке
        } catch (Exception e) {
            log.info("8. Все прошло плохо, вернулись в контроллер");
            rm.addFlashAttribute("saveSuccess", "false");
            rm.addFlashAttribute("errorMessage", "Сообщите менеджеру. Не удалось изменить статус и сохранить изменения, отзыв НЕ ОПУБЛИКОВАН.");
        }
        return "redirect:/worker/publish";
    } // Изменение статуса отзыва и сохранение копии в архив + проверка на выполнение заказа.

    @PostMapping("/{companyId}/{orderId}/nagul_to_worker/{reviewId}")
    public String nagulReviewToWorker(@PathVariable Long reviewId,
                                      @PathVariable Long companyId,
                                      @PathVariable Long orderId,
                                      RedirectAttributes rm,
                                      Principal principal) {

        log.info("Запрос на выгул отзыва: reviewId={}, companyId={}, orderId={}, user={}",
                reviewId, companyId, orderId, principal.getName());

        try {
            // Используем метод, который выбрасывает исключения
            reviewService.performNagulWithExceptions(reviewId, principal.getName());

            // Явно устанавливаем Boolean
            rm.addFlashAttribute("saveSuccess", Boolean.TRUE);
            rm.addFlashAttribute("message", "Отзыв успешно выгулен!");
            log.info("Выгул успешен: reviewId={}, user={}", reviewId, principal.getName());

        } catch (NagulTooFastException e) {
            // Обработка исключения "слишком быстро"
            LocalDateTime nextAvailableTime = LocalDateTime.now()
                    .plusMinutes(e.getMinutesLeft())
                    .plusSeconds(e.getSecondsLeft());

            // Явно устанавливаем Boolean
            rm.addFlashAttribute("saveSuccess", Boolean.FALSE);
            rm.addFlashAttribute("errorMessage", e.getMessage());
            rm.addFlashAttribute("nextAvailableTime",
                    nextAvailableTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")));

            log.warn("Выгул отклонен (слишком быстро): reviewId={}, user={}, reason={}",
                    reviewId, principal.getName(), e.getMessage());

        } catch (BotTemplateNameException e) {
            // Обработка исключения "шаблонное имя"
            rm.addFlashAttribute("saveSuccess", Boolean.FALSE);
            rm.addFlashAttribute("errorMessage", e.getMessage());

            log.warn("Выгул отклонен (шаблонное имя): reviewId={}, user={}, reason={}",
                    reviewId, principal.getName(), e.getMessage());

        } catch (Exception e) {
            // Обработка всех остальных исключений
            log.error("Ошибка при выгуле: reviewId={}, user={}", reviewId, principal.getName(), e);
            rm.addFlashAttribute("saveSuccess", Boolean.FALSE);
            rm.addFlashAttribute("errorMessage", "Произошла ошибка при выполнении выгула");
        }

        return "redirect:/worker/nagul";
    }
//    @PostMapping("/{companyId}/{orderId}/nagul_to_worker/{reviewId}") // Нажатие кнопки выгула.
//    public String nagulReviewToWorker(@PathVariable Long reviewId,@PathVariable Long companyId, RedirectAttributes rm, @PathVariable Long orderId, Model model, Principal principal){
//        log.info("1. Смена статуса отзыва на ВЫГУЛЯН - {}", principal != null ? principal.getName() : "Гость");
//        try {
//            reviewService.changeNagulReview(reviewId);
//            rm.addFlashAttribute("saveSuccess", "true");
//            rm.addFlashAttribute("errorMessage", null); // Убираем сообщение об ошибке
//        } catch (Exception e) {
//            log.info("8. Все прошло плохо, вернулись в контроллер");
//            rm.addFlashAttribute("saveSuccess", "false");
//            rm.addFlashAttribute("errorMessage", "Сообщите менеджеру. Не удалось изменить статус и сохранить изменения, отзыв НЕ НАГУЛЕН.");
//        }
//        return "redirect:/worker/nagul";
//    } // Нажатие кнопки выгула.


    private String gerRole(Principal principal){ // Берем роль пользователя
        // Получите текущий объект аутентификации
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Получите имя текущего пользователя (пользователя, не роль)
        String username = principal.getName();
        // Получите роль пользователя (предположим, что она хранится в поле "role" в объекте User)
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    } // Берем роль пользователя

    private void checkTimeMethod(String text, long startTime, Principal principal){
        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        log.info("{}: {} сек. - {}", text, String.format("%.4f", timeElapsed), principal != null ? principal.getName() : "Гость");
    }

}

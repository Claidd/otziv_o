package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.config.legacy.LegacyMvc;

import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.manager.services.ManagerAccessService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.services.service.OrderCreationService;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.services.AmountService;
import com.hunt.otziv.r_review.services.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Objects;

@Controller
@LegacyMvc
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/ordersCompany")
public class OrderController {

    private final ProductService productService;
    private final OrderService orderService;
    private final ReviewService reviewService;
    private final AmountService amountService;
    private final PromoTextService promoTextService;
    private final OrderDetailsService orderDetailsService;
    private final OrderCreationService creationService;
    private final ManagerAccessService managerAccessService;
    private final OrderAggregateMutationLockService orderAggregateMutationLockService;

    int pageSize = 10; // желаемый размер страницы

//    ======================================== ПРОСМОТР И СОЗДАНИЕ ЗАКАЗОВ =============================================
    @GetMapping("/{companyID}") // страница выбора продукта для заказа
    String ProductListToCompany(@PathVariable Long companyID, Model model, Authentication authentication){
        managerAccessService.requireCompanyAccess(companyID, authentication);
        model.addAttribute("companyID", companyID);
        model.addAttribute("amounts", amountService.getAmountDTOList());
        model.addAttribute("products", productService.findAll());
        model.addAttribute("newOrder", orderService.newOrderDTO(companyID));
        return "products/products_list";
    } // страница выбора продукта для заказа

    @GetMapping("/{companyID}/{orderId}") // Переход на страницу заказа продукта для нового Заказа
    String ProductListToCompany2(
            @PathVariable Long companyID,
            @PathVariable Long orderId,
            Model model,
            Authentication authentication
    ){
        managerAccessService.requireCompanyAccess(companyID, authentication);
        model.addAttribute("companyID", companyID);
        model.addAttribute("amounts", amountService.getAmountDTOList());
        model.addAttribute("products", productService.findAll());
        model.addAttribute("newOrder", orderService.newOrderDTO(companyID));
        return "products/products_list";
    } // Переход на страницу заказа продукта для нового Заказа

    @PostMapping ("/{companyID}/{id}") // Пост запрос на создание нового заказа и редирект на оформление нового заказа
    String newOrder(
            @ModelAttribute ("newOrder") OrderDTO orderDTO,
            @PathVariable Long companyID,
            RedirectAttributes rm,
            @PathVariable Long id,
            Model model,
            Authentication authentication
    ){
        managerAccessService.requireCompanyAccess(companyID, authentication);
        if (id == null || productService.findById(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Продукт не найден");
        }
        requireCanonicalNewOrderRelations(orderDTO, companyID, authentication);
        creationService.createNewOrderWithReviews(companyID, id, orderDTO);
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/companies/company";
    } // Пост запрос на создание нового заказа и редирект на оформление нового заказа

//    ======================================== ПРОСМОТР И СОЗДАНИЕ ЗАКАЗОВ =============================================



//    ===================================== ПРОСМОТР ВСЕХ ЗАКАЗОВ ПО СТАТУСУ ===========================================
    @GetMapping("/ordersDetails/{companyId}") // Страница просмотра всех заказов компании по всем статусам
    String OrderListToCompany(
            @PathVariable Long companyId,
            @RequestParam(defaultValue = "") String keyword,
            Model model,
            @RequestParam(defaultValue = "0") int pageNumber,
            Authentication authentication
    ){
//        model.addAttribute("companyID", companyId);
        long startTime = System.nanoTime();
        managerAccessService.requireCompanyAccess(companyId, authentication);
        model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
        model.addAttribute("TitleName", "Все заказы компании");
        model.addAttribute("pageNumber", pageNumber);
        model.addAttribute("orders", orderService.getAllOrderDTOCompanyIdAndKeyword(companyId, keyword, pageNumber, pageSize));
        checkTimeMethod("Время выполнения OrderController/ordersCompany/ordersDetails/{companyId} для всех: ", startTime);
        return "products/orders_list";
    } // Страница просмотра всех заказов компании по всем статусам

//    ===================================== ПРОСМОТР ВСЕХ ЗАКАЗОВ ПО СТАТУСУ ===========================================



//    ============================================= ORDER EDIT =========================================================
    @GetMapping("/ordersDetails/{companyId}/{orderId}") // Страница редактирования Заказа - Get
    String OrderEdit(
            @PathVariable Long companyId,
            @PathVariable Long orderId,
            Model model,
            Authentication authentication
    ){
        long startTime = System.nanoTime();
        managerAccessService.requireOrderAccess(orderId, authentication);
        OrderDTO current = orderService.getOrderDTO(orderId);
        requireCanonicalCompany(current, companyId);
        model.addAttribute("ordersDTO", current);
        model.addAttribute("companyId", companyId);
        model.addAttribute("orderId", orderId);
        checkTimeMethod("Время выполнения OrderController/ordersCompany/ordersDetails/{companyId}/{orderId} для всех: ", startTime);
        return "products/order_edit";
    } // Страница редактирования Заказа - Get

    @PostMapping("/ordersDetails/{companyId}/{orderId}") // Страница редактирования Заказа - Post
    @Transactional
    String OrderEditPost(
            @ModelAttribute ("ordersDTO") OrderDTO orderDTO,
            @PathVariable Long companyId,
            @PathVariable Long orderId,
            RedirectAttributes rm,
            Principal principal,
            Model model,
            Authentication authentication
    ){
        managerAccessService.requireOrderAccess(orderId, authentication);
        orderAggregateMutationLockService.lock(orderId);
        managerAccessService.requireOrderAccess(orderId, authentication);
        OrderDTO current = orderService.getOrderDTO(orderId);
        requireCanonicalCompany(current, companyId);
        requireAssignmentAccess(current, orderDTO, authentication);
        canonicalizeEditFieldsByRole(current, orderDTO, authentication);
        requireCompanyAccessForCompanyMutations(current, orderDTO, authentication);

        if (hasOnlyWorkerRole(authentication)){
            log.info("1. Начинаем обновлять данные Заказа ДЛЯ Работника - {}", principal != null ? principal.getName() : "Гость");
            orderService.updateOrderToWorker(orderDTO, companyId, orderId);
            log.info("5. Обновление Заказа прошло успешно");
            rm.addFlashAttribute("saveSuccess", "true");
            return "redirect:/ordersCompany/ordersDetails/{companyId}/{orderId}";
        }
        else {
            log.info("1. Начинаем обновлять данные Заказа");
            orderService.updateOrder(orderDTO, companyId, orderId);
            log.info("5. Обновление Заказа прошло успешно");
            rm.addFlashAttribute("saveSuccess", "true");
            return "redirect:/ordersCompany/ordersDetails/{companyId}/{orderId}";
        }
    } // Страница редактирования Заказа - Post

    @PostMapping("/ordersDetails/{companyId}/{orderId}/delete") // Страница редактирования Заказа - Post
    @Transactional
    String OrderEditPostDelete(
            @ModelAttribute ("ordersDTO") OrderDTO orderDTO,
            @PathVariable Long companyId,
            @PathVariable Long orderId,
            RedirectAttributes rm,
            Principal principal,
            Model model,
            Authentication authentication
    ){
        requireOrderMutationAccess(orderId, companyId, authentication);
        log.info("1. Начинаем удалять Заказ. - {}", principal != null ? principal.getName() : "Гость");
        if(orderService.deleteOrder(orderId, principal)) {
            rm.addFlashAttribute("saveSuccess", "true");
            log.info("5. Заказ удален");
            return "redirect:/ordersCompany/ordersDetails/{companyId}";
        } else {
            log.info("Заказ не удален");
            return "redirect:/ordersCompany/ordersDetails/{companyId}/{orderId}";
        }

    } // Страница редактирования Заказа - Post

//    ============================================= ORDER EDIT =========================================================



//    =========================================== СМЕНА СТАТУСА ========================================================
    @PostMapping ("/status_for_checking/{companyID}/{orderID}") // смена статуса на "в проверку"
    @Transactional
    String changeStatusForChecking( @PathVariable Long orderID, @PathVariable Long companyID, Model model, RedirectAttributes rm, Authentication authentication) throws Exception {
        requireWorkerSubmissionAccess(orderID, companyID, authentication);
        if(orderService.changeStatusForOrder(orderID, "В проверку")) {
            log.info("статус заказа успешно изменен на на проверке");
            rm.addFlashAttribute("saveSuccess", "true");
//            return "redirect:/ordersDetails/{companyID}/{orderID}";
            return "redirect:/worker/new_orders";
        } else {
            log.info("ошибка при изменении статуса заказа на на проверке");
            return "redirect:/ordersDetails/{companyID}/{orderID}";
        }
    } // смена статуса на "на проверке"

    @PostMapping ("/status_on_checking/{companyID}/{orderID}") // смена статуса на "на проверке"
    @Transactional
    String changeStatusOnChecking( @PathVariable Long orderID, @PathVariable Long companyID, @RequestParam(defaultValue = "В проверку") String status, @RequestParam(defaultValue = "0") int pageNumber, Authentication authentication) throws Exception {
        requireOrderMutationAccess(orderID, companyID, authentication);
        if(orderService.changeStatusForOrder(orderID, "На проверке")) {
            log.info("статус заказа успешно изменен на на проверке");
        } else {
            log.info("ошибка при изменении статуса заказа на на проверке");
        }
        String encodedStatus = UriUtils.encode(status, StandardCharsets.UTF_8);
        return "redirect:/orders/all_orders?pageNumber=" + pageNumber + "&status=" + encodedStatus;
//        return "redirect:/orders/to_check";
    } // смена статуса на "на проверке"

    @PostMapping ("/status_for_correct/{companyID}/{orderID}") // смена статуса на "Коррекция"
    @Transactional
    String changeStatusForCorrect( @PathVariable Long orderID, @PathVariable Long companyID, @RequestParam(defaultValue = "На проверке") String status, @RequestParam(defaultValue = "0") int pageNumber, Authentication authentication) throws Exception {
        requireOrderMutationAccess(orderID, companyID, authentication);
        if(orderService.changeStatusForOrder(orderID, "Коррекция")) {
            log.info("статус заказа успешно изменен на Коррекция");
        } else {
            log.info("ошибка при изменении статуса заказа на Коррекция");
        }
        String encodedStatus = UriUtils.encode(status, StandardCharsets.UTF_8);
        return "redirect:/orders/all_orders?pageNumber=" + pageNumber + "&status=" + encodedStatus;
    } // смена статуса на "Коррекция"

    @PostMapping ("/order_to_archive/{companyID}/{orderID}") // смена статуса на "Архив"
    @Transactional
    String changeStatusForArchive( @PathVariable Long orderID, @PathVariable Long companyID, @RequestParam(defaultValue = "На проверке") String status, @RequestParam(defaultValue = "0") int pageNumber, Authentication authentication) throws Exception {
        requireOrderMutationAccess(orderID, companyID, authentication);
        if(orderService.changeStatusForOrder(orderID, "Архив")) {
            log.info("статус заказа успешно изменен на Архив");
        } else {
            log.info("ошибка при изменении статуса заказа на Архив");
        }
        String encodedStatus = UriUtils.encode(status, StandardCharsets.UTF_8);
        return "redirect:/orders/all_orders?pageNumber=" + pageNumber + "&status=" + encodedStatus;
    } // смена статуса на "Архив"


    @PostMapping ("/status_for_publish/{companyID}/{orderID}") // смена статуса на "Публикация"
    @Transactional
    String changeStatusForPublish(@PathVariable Long orderID, @PathVariable Long companyID, @RequestParam(defaultValue = "На проверке") String status, @RequestParam(defaultValue = "0") int pageNumber, Authentication authentication) throws Exception {

        requireOrderMutationAccess(orderID, companyID, authentication);
        if(orderService.changeStatusForOrder(orderID, "Публикация")) {
            Order order = orderService.getOrder(orderID);
            OrderDetailsDTO orderDetailDTO = orderDetailsService.getOrderDetailDTOById(order.getDetails().iterator().next().getId());
            reviewService.updateOrderDetailAndReviewAndPublishDate(orderDetailDTO);
            log.info("статус заказа успешно изменен на Публикация");
        } else {
            log.info("ошибка при изменении статуса заказа на Публикация");
        }
        String encodedStatus = UriUtils.encode(status, StandardCharsets.UTF_8);
        return "redirect:/orders/all_orders?pageNumber=" + pageNumber + "&status=" + encodedStatus;
//         return "redirect:/orders/on_check";
    } // смена статуса на "Публикация"

    @PostMapping ("/status_for_publish_ok/{companyID}/{orderID}") // смена статуса на "Опубликовано"
    @Transactional
    String changeStatusForPublishOk(@PathVariable Long orderID, @PathVariable Long companyID, @RequestParam(defaultValue = "Публикация") String status, @RequestParam(defaultValue = "0") int pageNumber, Authentication authentication) throws Exception {
        Order order = requireOrderMutationAccess(orderID, companyID, authentication);
        if (order.getAmount() <= order.getCounter()) {
            orderService.changeStatusForOrder(orderID, "Опубликовано");
            log.info("статус заказа успешно изменен на Опубликовано");
        }
         else {
            log.info("ошибка при изменении статуса заказа на Опубликовано");
        }
        String encodedStatus = UriUtils.encode(status, StandardCharsets.UTF_8);
        return "redirect:/orders/all_orders?pageNumber=" + pageNumber + "&status=" + encodedStatus;
//        return "redirect:/orders/to_published";
    } // смена статуса на "Опубликовано"


    @PostMapping ("/status_to_pay/{companyID}/{orderID}") // смена статуса на "Выставлен счет"
    @Transactional
    String changeStatusToPay(@PathVariable Long orderID, @PathVariable Long companyID, @RequestParam(defaultValue = "Опубликовано") String status, @RequestParam(defaultValue = "0") int pageNumber, Authentication authentication) throws Exception {
        requireOrderMutationAccess(orderID, companyID, authentication);
        if(orderService.changeStatusForOrder(orderID, "Выставлен счет")) {
            log.info("статус заказа успешно изменен на Выставлен счет");
            String encodedStatus = UriUtils.encode(status, StandardCharsets.UTF_8);
            return "redirect:/orders/all_orders?pageNumber=" + pageNumber + "&status=" + encodedStatus;
//            return "redirect:/orders/published";
        } else {
            log.info("ошибка при изменении статуса заказа на Выставлен счет");
            return "products/orders_list";
        }
    } // смена статуса на "Выставлен счет"

    @PostMapping ("/remember/{companyID}/{orderID}") // смена статуса на "Напоминание"
    @Transactional
    String changeStatusRemember(@PathVariable Long orderID, @PathVariable Long companyID, @RequestParam(defaultValue = "Выставлен счет") String status, @RequestParam(defaultValue = "0") int pageNumber, Authentication authentication) throws Exception {
        requireOrderMutationAccess(orderID, companyID, authentication);
        if(orderService.changeStatusForOrder(orderID, "Напоминание")) {
            log.info("статус заказа успешно изменен на Напоминание");
        } else {
            log.info("ошибка при изменении статуса заказа на Напоминание");
        }
        String encodedStatus = UriUtils.encode(status, StandardCharsets.UTF_8);
        return "redirect:/orders/all_orders?pageNumber=" + pageNumber + "&status=" + encodedStatus;
//        return "redirect:/orders/payment_check";
    } // смена статуса на "Напоминание"

    @PostMapping ("/status_no_pay/{companyID}/{orderID}") // смена статуса на "Не оплачено"
    @Transactional
    String changeStatusNoPay(@PathVariable Long orderID, @PathVariable Long companyID, @RequestParam(defaultValue = "Напоминание") String status, @RequestParam(defaultValue = "0") int pageNumber, Authentication authentication) throws Exception {
        requireOrderMutationAccess(orderID, companyID, authentication);
        if(orderService.changeStatusForOrder(orderID, "Не оплачено")) {
            log.info("статус заказа успешно изменен на Не оплачено");
        } else {
            log.info("ошибка при изменении статуса заказа на Не оплачено");
        }
        String encodedStatus = UriUtils.encode(status, StandardCharsets.UTF_8);
        return "redirect:/orders/all_orders?pageNumber=" + pageNumber + "&status=" + encodedStatus;
//        return "redirect:/orders/remember";
    } // смена статуса на "Не оплачено"

    @PostMapping ("/status_pay/{companyID}/{orderID}") // смена статуса на "Оплачено"
    @Transactional
    String changeStatusPay(@PathVariable Long orderID, @PathVariable Long companyID, @RequestParam(defaultValue = "Выставлен счет") String status, @RequestParam(defaultValue = "0") int pageNumber, Authentication authentication) throws Exception {
        Order order = requireOrderMutationAccess(orderID, companyID, authentication);
        if (order.getAmount() <= order.getCounter()){
            orderService.changeStatusForOrder(orderID, "Оплачено");
            log.info("статус заказа успешно изменен на Оплачено");
        }
        else {
            log.info("ошибка при изменении статуса заказа на Выставлен счет");
        }
        String encodedStatus = UriUtils.encode(status, StandardCharsets.UTF_8);
        return "redirect:/orders/all_orders?pageNumber=" + pageNumber + "&status=" + encodedStatus;
//        return "redirect:/orders/payment_check";
    } // смена статуса на "Оплачено"


    //    =========================================== СМЕНА СТАТУСА ========================================================
    @PostMapping ("/status_for_checking2/{companyID}/{orderID}") // смена статуса на "в проверку"
    @Transactional
    String changeStatusForChecking2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber, Authentication authentication) throws Exception {
        requireOrderMutationAccess(orderID, companyID, authentication);
        if(orderService.changeStatusForOrder(orderID, "В проверку")) {
            log.info("статус заказа успешно изменен на на проверке");
            model.addAttribute("companyId", companyID);
        } else {
            log.info("ошибка при изменении статуса заказа на на проверке");
        }
        return "redirect:/ordersCompany/ordersDetails/" + companyID + "?pageNumber=" + pageNumber;
    } // смена статуса на "на проверке"

    @PostMapping ("/status_on_checking2/{companyID}/{orderID}") // смена статуса на "на проверке"
    @Transactional
    String changeStatusOnChecking2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber, Authentication authentication) throws Exception {
        requireOrderMutationAccess(orderID, companyID, authentication);
        if(orderService.changeStatusForOrder(orderID, "На проверке")) {
            log.info("статус заказа успешно изменен на на проверке");
        } else {
            log.info("ошибка при изменении статуса заказа на на проверке");
        }
        return "redirect:/ordersCompany/ordersDetails/" + companyID + "?pageNumber=" + pageNumber;
    } // смена статуса на "на проверке"

    @PostMapping ("/status_for_correct2/{companyID}/{orderID}") // смена статуса на "Коррекция"
    @Transactional
    String changeStatusForCorrect2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber, Authentication authentication) throws Exception {
        requireOrderMutationAccess(orderID, companyID, authentication);
        if(orderService.changeStatusForOrder(orderID, "Коррекция")) {
            log.info("статус заказа успешно изменен на Коррекция");
        } else {
            log.info("ошибка при изменении статуса заказа на Коррекция");
        }
        return "redirect:/ordersCompany/ordersDetails/{companyID}";
    } // смена статуса на "Коррекция"

    @PostMapping ("/order_to_archive2/{companyID}/{orderID}") // смена статуса на "Коррекция"
    @Transactional
    String changeStatusForArchive2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber, Authentication authentication) throws Exception {
        requireOrderMutationAccess(orderID, companyID, authentication);
        if(orderService.changeStatusForOrder(orderID, "Архив")) {
            log.info("статус заказа успешно изменен на Архив");
        } else {
            log.info("ошибка при изменении статуса заказа на Архив");
        }
        return "redirect:/ordersCompany/ordersDetails/" + companyID + "?pageNumber=" + pageNumber;
    } // смена статуса на "Коррекция"

    @PostMapping ("/status_for_publish2/{companyID}/{orderID}") // смена статуса на "Публикация"
    @Transactional
    String changeStatusForPublish2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber, Authentication authentication) throws Exception {
        requireOrderMutationAccess(orderID, companyID, authentication);
        if(orderService.changeStatusForOrder(orderID, "Публикация")) {
            Order order = orderService.getOrder(orderID);
            OrderDetailsDTO orderDetailDTO = orderDetailsService.getOrderDetailDTOById(order.getDetails().iterator().next().getId());
            reviewService.updateOrderDetailAndReviewAndPublishDate(orderDetailDTO);
            log.info("статус заказа успешно изменен на Публикация");
        } else {
            log.info("ошибка при изменении статуса заказа на Публикация");
        }
        return "redirect:/ordersCompany/ordersDetails/" + companyID + "?pageNumber=" + pageNumber;
    } // смена статуса на "Публикация"

    @PostMapping ("/status_for_publish_ok2/{companyID}/{orderID}") // смена статуса на "Опубликовано"
    @Transactional
    String changeStatusForPublishOk2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber, Authentication authentication) throws Exception {
        Order order = requireOrderMutationAccess(orderID, companyID, authentication);
        if (order.getAmount() <= order.getCounter()) {
            orderService.changeStatusForOrder(orderID, "Опубликовано");
            log.info("статус заказа успешно изменен на Опубликовано");
        }
        else {
            log.info("ошибка при изменении статуса заказа на Опубликовано");
        }
        return "redirect:/ordersCompany/ordersDetails/" + companyID + "?pageNumber=" + pageNumber;
    } // смена статуса на "Опубликовано"


    @PostMapping ("/status_to_pay2/{companyID}/{orderID}") // смена статуса на "Выставлен счет"
    @Transactional
    String changeStatusToPay2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber, Authentication authentication) throws Exception {
        requireOrderMutationAccess(orderID, companyID, authentication);
        if(orderService.changeStatusForOrder(orderID, "Выставлен счет")) {
            log.info("статус заказа успешно изменен на Выставлен счет");
            return "redirect:/ordersCompany/ordersDetails/{companyID}";
        } else {
            log.info("ошибка при изменении статуса заказа на Выставлен счет");
            return "redirect:/ordersCompany/ordersDetails/" + companyID + "?pageNumber=" + pageNumber;
        }
    } // смена статуса на "Выставлен счет"

    @PostMapping ("/remember2/{companyID}/{orderID}") // смена статуса на "Напоминание"
    @Transactional
    String changeStatusRemember2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber, Authentication authentication) throws Exception {
        requireOrderMutationAccess(orderID, companyID, authentication);
        if(orderService.changeStatusForOrder(orderID, "Напоминание")) {
            log.info("статус заказа успешно изменен на Напоминание");
        } else {
            log.info("ошибка при изменении статуса заказа на Напоминание");
        }
        return "redirect:/ordersCompany/ordersDetails/" + companyID + "?pageNumber=" + pageNumber;
    } // смена статуса на "Напоминание"

    @PostMapping ("/status_no_pay2/{companyID}/{orderID}") // смена статуса на "Не оплачено"
    @Transactional
    String changeStatusNoPay2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber, Authentication authentication) throws Exception {
        requireOrderMutationAccess(orderID, companyID, authentication);
        if(orderService.changeStatusForOrder(orderID, "Не оплачено")) {
            log.info("статус заказа успешно изменен на Не оплачено");
        } else {
            log.info("ошибка при изменении статуса заказа на Не оплачено");
        }
        return "redirect:/ordersCompany/ordersDetails/" + companyID + "?pageNumber=" + pageNumber;
    } // смена статуса на "Не оплачено"

    @PostMapping ("/status_pay2/{companyID}/{orderID}") // смена статуса на "Оплачено"
    @Transactional
    String changeStatusPay2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber, Authentication authentication) throws Exception {
        Order order = requireOrderMutationAccess(orderID, companyID, authentication);
        if (order.getAmount() <= order.getCounter()){
            orderService.changeStatusForOrder(orderID, "Оплачено");
            log.info("статус заказа успешно изменен на Оплачено");
        }
        else {
            log.info("ошибка при изменении статуса заказа на Выставлен счет");
        }
        return "redirect:/ordersCompany/ordersDetails/" + companyID + "?pageNumber=" + pageNumber;
    } // смена статуса на "Оплачено"


    private void checkTimeMethod(String text, long startTime){
        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        log.info("{}: {} сек", text, String.format("%.4f", timeElapsed));
    }
//    =========================================== СМЕНА СТАТУСА ========================================================

    private Order requireOrderMutationAccess(
            Long orderId,
            Long companyId,
            Authentication authentication
    ) {
        if (!hasRole(authentication, "ROLE_ADMIN")
                && !hasRole(authentication, "ROLE_OWNER")
                && !hasRole(authentication, "ROLE_MANAGER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав для изменения заказа");
        }
        return requireOrderObjectMutationAccess(orderId, companyId, authentication);
    }

    private Order requireWorkerSubmissionAccess(
            Long orderId,
            Long companyId,
            Authentication authentication
    ) {
        if (!hasRole(authentication, "ROLE_ADMIN")
                && !hasRole(authentication, "ROLE_OWNER")
                && !hasRole(authentication, "ROLE_MANAGER")
                && !hasRole(authentication, "ROLE_WORKER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Недостаточно прав для изменения заказа");
        }
        return requireOrderObjectMutationAccess(orderId, companyId, authentication);
    }

    private Order requireOrderObjectMutationAccess(
            Long orderId,
            Long companyId,
            Authentication authentication
    ) {
        managerAccessService.requireOrderAccess(orderId, authentication);
        Order lockedOrder = orderAggregateMutationLockService.lock(orderId);
        managerAccessService.requireOrderAccess(orderId, authentication);
        requireCanonicalCompany(lockedOrder, companyId);
        return lockedOrder;
    }

    private void requireCanonicalNewOrderRelations(
            OrderDTO requested,
            Long companyId,
            Authentication authentication
    ) {
        if (requested == null || requested.getCompany() == null
                || !Objects.equals(requested.getCompany().getId(), companyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена");
        }

        OrderDTO canonical = orderService.newOrderDTO(companyId);
        if (canonical == null || canonical.getCompany() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена");
        }

        Long canonicalManagerId = canonical.getManager() == null ? null : canonical.getManager().getManagerId();
        Long requestedManagerId = requested.getManager() == null ? null : requested.getManager().getManagerId();
        if (canonicalManagerId == null
                || !Objects.equals(canonicalManagerId, requestedManagerId)
                || !managerAccessService.canAccessManager(canonicalManagerId, authentication)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер не найден");
        }

        Long requestedWorkerId = requested.getWorker() == null ? null : requested.getWorker().getWorkerId();
        var canonicalWorker = canonical.getCompany().getWorkers() == null ? null
                : canonical.getCompany().getWorkers().stream()
                .filter(worker -> Objects.equals(worker.getWorkerId(), requestedWorkerId))
                .findFirst()
                .orElse(null);
        if (canonicalWorker == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Специалист не закреплен за компанией");
        }

        Long requestedFilialId = requested.getFilial() == null ? null : requested.getFilial().getId();
        var canonicalFilial = canonical.getCompany().getFilials() == null ? null
                : canonical.getCompany().getFilials().stream()
                .filter(filial -> Objects.equals(filial.getId(), requestedFilialId) && !filial.isArchived())
                .findFirst()
                .orElse(null);
        if (canonicalFilial == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Филиал не принадлежит компании");
        }

        String canonicalStatus = canonical.getStatus() == null ? null : canonical.getStatus().getTitle();
        String requestedStatus = requested.getStatus() == null ? null : requested.getStatus().getTitle();
        if (canonicalStatus == null || !Objects.equals(canonicalStatus, requestedStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимый начальный статус заказа");
        }

        boolean canonicalAmount = requested.getAmount() != null
                && requested.getAmount() > 0
                && amountService.getAmountDTOList().stream()
                .anyMatch(amount -> amount.getAmount() == requested.getAmount());
        if (!canonicalAmount) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимое количество отзывов");
        }

        requested.setCompany(canonical.getCompany());
        requested.setManager(canonical.getManager());
        requested.setWorker(canonicalWorker);
        requested.setFilial(canonicalFilial);
        requested.setStatus(canonical.getStatus());
        requested.setCounter(0);
        requested.setComplete(false);
        requested.setWaitingForClient(false);
        requested.setClientTextExpected(false);
        requested.setReviewFilialIds(List.of());
    }

    private void requireCanonicalCompany(Order order, Long companyId) {
        Long canonicalCompanyId = order == null || order.getCompany() == null
                ? null
                : order.getCompany().getId();
        if (canonicalCompanyId == null || !Objects.equals(canonicalCompanyId, companyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден");
        }
    }

    private void requireCanonicalCompany(OrderDTO order, Long companyId) {
        Long canonicalCompanyId = order == null || order.getCompany() == null
                ? null
                : order.getCompany().getId();
        if (canonicalCompanyId == null || !Objects.equals(canonicalCompanyId, companyId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Заказ не найден"
            );
        }
    }

    private void requireAssignmentAccess(
            OrderDTO current,
            OrderDTO requested,
            Authentication authentication
    ) {
        if (requested == null || hasOnlyWorkerRole(authentication)) {
            return;
        }
        Long currentManagerId = current.getManager() == null ? null : current.getManager().getManagerId();
        Long requestedManagerId = requested.getManager() == null ? null : requested.getManager().getManagerId();
        if (requestedManagerId != null
                && !Objects.equals(requestedManagerId, currentManagerId)
                && !managerAccessService.canAccessManager(requestedManagerId, authentication)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Менеджер не найден"
            );
        }
    }

    private void requireCompanyAccessForCompanyMutations(
            OrderDTO current,
            OrderDTO requested,
            Authentication authentication
    ) {
        if (requested == null) {
            return;
        }
        Long currentWorkerId = current.getWorker() == null ? null : current.getWorker().getWorkerId();
        Long requestedWorkerId = requested.getWorker() == null ? null : requested.getWorker().getWorkerId();
        boolean workerTransfer = !Objects.equals(requestedWorkerId, currentWorkerId);
        boolean workerMembershipMissing = requestedWorkerId != null
                && (current.getCompany().getWorkers() == null
                || current.getCompany().getWorkers().stream()
                .noneMatch(worker -> Objects.equals(worker.getWorkerId(), requestedWorkerId)));
        boolean companyCommentsChanged = !Objects.equals(
                requested.getCommentsCompany(),
                current.getCommentsCompany()
        );

        if (workerTransfer || workerMembershipMissing || companyCommentsChanged) {
            managerAccessService.requireCompanyAccess(current.getCompany().getId(), authentication);
        }
    }

    private void canonicalizeEditFieldsByRole(
            OrderDTO current,
            OrderDTO requested,
            Authentication authentication
    ) {
        if (requested == null) {
            return;
        }
        if (!hasRole(authentication, "ROLE_ADMIN") && !hasRole(authentication, "ROLE_OWNER")) {
            requested.setComplete(current.isComplete());
        }
        if (hasOnlyWorkerRole(authentication)) {
            requested.setCommentsCompany(current.getCommentsCompany());
        }
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication != null
                && authentication.getAuthorities() != null
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> role.equalsIgnoreCase(authority.getAuthority()));
    }

    private boolean hasOnlyWorkerRole(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        boolean worker = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_WORKER".equalsIgnoreCase(authority.getAuthority()));
        boolean privileged = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equalsIgnoreCase(authority.getAuthority())
                        || "ROLE_OWNER".equalsIgnoreCase(authority.getAuthority())
                        || "ROLE_MANAGER".equalsIgnoreCase(authority.getAuthority()));
        return worker && !privileged;
    }

}

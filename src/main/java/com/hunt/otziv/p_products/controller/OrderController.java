package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.l_lead.services.PromoTextService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.services.AmountService;
import com.hunt.otziv.r_review.services.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Comparator;

@Controller
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

    int pageSize = 10; // желаемый размер страницы

//    ======================================== ПРОСМОТР И СОЗДАНИЕ ЗАКАЗОВ =============================================
    @GetMapping("/{companyID}") // страница выбора продукта для заказа
    String ProductListToCompany(@PathVariable Long companyID, Model model){
        model.addAttribute("companyID", companyID);
        model.addAttribute("amounts", amountService.getAmountDTOList());
        model.addAttribute("products", productService.findAll());
        model.addAttribute("newOrder", orderService.newOrderDTO(companyID));
        return "products/products_list";
    } // страница выбора продукта для заказа

    @GetMapping("/{companyID}/{orderId}") // Переход на страницу заказа продукта для нового Заказа
    String ProductListToCompany2(@PathVariable Long companyID, @PathVariable Long orderId, Model model){
        model.addAttribute("companyID", companyID);
        model.addAttribute("amounts", amountService.getAmountDTOList());
        model.addAttribute("products", productService.findAll());
        model.addAttribute("newOrder", orderService.newOrderDTO(companyID));
        return "products/products_list";
    } // Переход на страницу заказа продукта для нового Заказа

    @PostMapping ("/{companyID}/{id}") // Пост запрос на создание нового заказа и редирект на оформление нового заказа
    String newOrder(@ModelAttribute ("newOrder") OrderDTO orderDTO, @PathVariable Long companyID, RedirectAttributes rm, @PathVariable Long id, Model model){
        orderService.createNewOrderWithReviews(companyID, id, orderDTO);
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/companies/company";
    } // Пост запрос на создание нового заказа и редирект на оформление нового заказа

//    ======================================== ПРОСМОТР И СОЗДАНИЕ ЗАКАЗОВ =============================================



//    ===================================== ПРОСМОТР ВСЕХ ЗАКАЗОВ ПО СТАТУСУ ===========================================
    @GetMapping("/ordersDetails/{companyId}") // Страница просмотра всех заказов компании по всем статусам
    String OrderListToCompany(@PathVariable Long companyId, @RequestParam(defaultValue = "") String keyword, Model model, @RequestParam(defaultValue = "0") int pageNumber){
//        model.addAttribute("companyID", companyId);
        long startTime = System.nanoTime();
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
    String OrderEdit(@PathVariable Long companyId, @PathVariable Long orderId,  Model model){
        long startTime = System.nanoTime();
        model.addAttribute("ordersDTO", orderService.getOrderDTO(orderId));
        model.addAttribute("companyId", companyId);
        model.addAttribute("orderId", orderId);
        checkTimeMethod("Время выполнения OrderController/ordersCompany/ordersDetails/{companyId}/{orderId} для всех: ", startTime);
        return "products/order_edit";
    } // Страница редактирования Заказа - Get

    @PostMapping("/ordersDetails/{companyId}/{orderId}") // Страница редактирования Заказа - Post
    String OrderEditPost(@ModelAttribute ("ordersDTO") OrderDTO orderDTO, @PathVariable Long companyId, @PathVariable Long orderId, RedirectAttributes rm, Principal principal, Model model){
        String userRole = getRole(principal);
        if ("ROLE_WORKER".equals(userRole)){
            log.info("1. Начинаем обновлять данные Заказа ДЛЯ Работника");
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
    String OrderEditPostDelete(@ModelAttribute ("ordersDTO") OrderDTO orderDTO, @PathVariable Long companyId, @PathVariable Long orderId, RedirectAttributes rm, Principal principal, Model model){
        log.info("1. Начинаем удалять Заказ");
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
    String changeStatusForChecking( @PathVariable Long orderID, @PathVariable Long companyID, Model model, RedirectAttributes rm) throws Exception {
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
    String changeStatusOnChecking( @PathVariable Long orderID, @RequestParam(defaultValue = "В проверку") String status, @RequestParam(defaultValue = "0") int pageNumber) throws Exception {
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
    String changeStatusForCorrect( @PathVariable Long orderID, @RequestParam(defaultValue = "На проверке") String status, @RequestParam(defaultValue = "0") int pageNumber) throws Exception {
        if(orderService.changeStatusForOrder(orderID, "Коррекция")) {
            log.info("статус заказа успешно изменен на Коррекция");
        } else {
            log.info("ошибка при изменении статуса заказа на Коррекция");
        }
        String encodedStatus = UriUtils.encode(status, StandardCharsets.UTF_8);
        return "redirect:/orders/all_orders?pageNumber=" + pageNumber + "&status=" + encodedStatus;
    } // смена статуса на "Коррекция"

    @PostMapping ("/order_to_archive/{companyID}/{orderID}") // смена статуса на "Архив"
    String changeStatusForArchive( @PathVariable Long orderID, @RequestParam(defaultValue = "На проверке") String status, @RequestParam(defaultValue = "0") int pageNumber) throws Exception {
        if(orderService.changeStatusForOrder(orderID, "Архив")) {
            log.info("статус заказа успешно изменен на Архив");
        } else {
            log.info("ошибка при изменении статуса заказа на Архив");
        }
        String encodedStatus = UriUtils.encode(status, StandardCharsets.UTF_8);
        return "redirect:/orders/all_orders?pageNumber=" + pageNumber + "&status=" + encodedStatus;
    } // смена статуса на "Архив"


    @PostMapping ("/status_for_publish/{companyID}/{orderID}") // смена статуса на "Публикация"
    String changeStatusForPublish(@PathVariable Long orderID, @RequestParam(defaultValue = "На проверке") String status, @RequestParam(defaultValue = "0") int pageNumber) throws Exception {

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
    String changeStatusForPublishOk(@PathVariable Long orderID, @RequestParam(defaultValue = "Публикация") String status, @RequestParam(defaultValue = "0") int pageNumber) throws Exception {
        Order order = orderService.getOrder(orderID);
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
    String changeStatusToPay(@PathVariable Long orderID, @RequestParam(defaultValue = "Опубликовано") String status, @RequestParam(defaultValue = "0") int pageNumber) throws Exception {
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
    String changeStatusRemember(@PathVariable Long orderID, @RequestParam(defaultValue = "Выставлен счет") String status, @RequestParam(defaultValue = "0") int pageNumber) throws Exception {
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
    String changeStatusNoPay(@PathVariable Long orderID, @RequestParam(defaultValue = "Напоминание") String status, @RequestParam(defaultValue = "0") int pageNumber) throws Exception {
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
    String changeStatusPay(@PathVariable Long orderID, @RequestParam(defaultValue = "Выставлен счет") String status, @RequestParam(defaultValue = "0") int pageNumber) throws Exception {
        Order order = orderService.getOrder(orderID);
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
    String changeStatusForChecking2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber) throws Exception {
        if(orderService.changeStatusForOrder(orderID, "В проверку")) {
            log.info("статус заказа успешно изменен на на проверке");
            model.addAttribute("companyId", companyID);
        } else {
            log.info("ошибка при изменении статуса заказа на на проверке");
        }
        return "redirect:/ordersCompany/ordersDetails/" + companyID + "?pageNumber=" + pageNumber;
    } // смена статуса на "на проверке"

    @PostMapping ("/status_on_checking2/{companyID}/{orderID}") // смена статуса на "на проверке"
    String changeStatusOnChecking2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber) throws Exception {
        if(orderService.changeStatusForOrder(orderID, "На проверке")) {
            log.info("статус заказа успешно изменен на на проверке");
        } else {
            log.info("ошибка при изменении статуса заказа на на проверке");
        }
        return "redirect:/ordersCompany/ordersDetails/" + companyID + "?pageNumber=" + pageNumber;
    } // смена статуса на "на проверке"

    @PostMapping ("/status_for_correct2/{companyID}/{orderID}") // смена статуса на "Коррекция"
    String changeStatusForCorrect2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber) throws Exception {
        if(orderService.changeStatusForOrder(orderID, "Коррекция")) {
            log.info("статус заказа успешно изменен на Коррекция");
        } else {
            log.info("ошибка при изменении статуса заказа на Коррекция");
        }
        return "redirect:/ordersCompany/ordersDetails/{companyID}";
    } // смена статуса на "Коррекция"

    @PostMapping ("/order_to_archive2/{companyID}/{orderID}") // смена статуса на "Коррекция"
    String changeStatusForArchive2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber) throws Exception {
        if(orderService.changeStatusForOrder(orderID, "Архив")) {
            log.info("статус заказа успешно изменен на Архив");
        } else {
            log.info("ошибка при изменении статуса заказа на Архив");
        }
        return "redirect:/ordersCompany/ordersDetails/" + companyID + "?pageNumber=" + pageNumber;
    } // смена статуса на "Коррекция"

    @PostMapping ("/status_for_publish2/{companyID}/{orderID}") // смена статуса на "Публикация"
    String changeStatusForPublish2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber) throws Exception {
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
    String changeStatusForPublishOk2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber) throws Exception {
        Order order = orderService.getOrder(orderID);
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
    String changeStatusToPay2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber) throws Exception {
        if(orderService.changeStatusForOrder(orderID, "Выставлен счет")) {
            log.info("статус заказа успешно изменен на Выставлен счет");
            return "redirect:/ordersCompany/ordersDetails/{companyID}";
        } else {
            log.info("ошибка при изменении статуса заказа на Выставлен счет");
            return "redirect:/ordersCompany/ordersDetails/" + companyID + "?pageNumber=" + pageNumber;
        }
    } // смена статуса на "Выставлен счет"

    @PostMapping ("/remember2/{companyID}/{orderID}") // смена статуса на "Напоминание"
    String changeStatusRemember2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber) throws Exception {
        if(orderService.changeStatusForOrder(orderID, "Напоминание")) {
            log.info("статус заказа успешно изменен на Напоминание");
        } else {
            log.info("ошибка при изменении статуса заказа на Напоминание");
        }
        return "redirect:/ordersCompany/ordersDetails/" + companyID + "?pageNumber=" + pageNumber;
    } // смена статуса на "Напоминание"

    @PostMapping ("/status_no_pay2/{companyID}/{orderID}") // смена статуса на "Не оплачено"
    String changeStatusNoPay2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber) throws Exception {
        if(orderService.changeStatusForOrder(orderID, "Не оплачено")) {
            log.info("статус заказа успешно изменен на Не оплачено");
        } else {
            log.info("ошибка при изменении статуса заказа на Не оплачено");
        }
        return "redirect:/ordersCompany/ordersDetails/" + companyID + "?pageNumber=" + pageNumber;
    } // смена статуса на "Не оплачено"

    @PostMapping ("/status_pay2/{companyID}/{orderID}") // смена статуса на "Оплачено"
    String changeStatusPay2( @PathVariable Long orderID, @PathVariable Long companyID, Model model, @RequestParam int pageNumber) throws Exception {
        Order order = orderService.getOrder(orderID);
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
        System.out.printf(text + "%.4f сек%n", timeElapsed);
    }
//    =========================================== СМЕНА СТАТУСА ========================================================
private String getRole(Principal principal){ // Берем роль пользователя
    // Получите текущий объект аутентификации
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    // Получите имя текущего пользователя (пользователя, не роль)
    String username = principal.getName();
    // Получите роль пользователя (предположим, что она хранится в поле "role" в объекте User)
    return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
} // Берем роль пользователя

}

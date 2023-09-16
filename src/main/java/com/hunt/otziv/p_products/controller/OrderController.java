package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.dto.AmountDTO;
import com.hunt.otziv.r_review.services.AmountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/ordersCompany")
public class OrderController {

    private final ProductService productService;
    private final OrderService orderService;
    private final CompanyService companyService;
    private final AmountService amountService;


    @GetMapping("/{companyID}")
    String ProductListToCompany(@PathVariable Long companyID, Model model){
        model.addAttribute("companyID", companyID);
        model.addAttribute("amounts", amountService.getAmountDTOList());
        model.addAttribute("products", productService.findAll());
        model.addAttribute("newOrder", orderService.newOrderDTO(companyID));
        return "products/products_list";
    }

    @GetMapping("/{companyID}/{orderId}") // Переход на страницу заказа продукта для нового Заказа
    String ProductListToCompany2(@PathVariable Long companyID, @PathVariable Long orderId, Model model){
        model.addAttribute("companyID", companyID);
        model.addAttribute("amounts", amountService.getAmountDTOList());
        model.addAttribute("products", productService.findAll());
        model.addAttribute("newOrder", orderService.newOrderDTO(companyID));
        return "products/products_list";
    }

    @PostMapping ("/{companyID}/{id}") // Пост запрос на создание нового заказа и редирект на оформление нового заказа
    String newOrder(@ModelAttribute ("newOrder") OrderDTO orderDTO, @PathVariable Long companyID, @PathVariable Long id, Model model){
        System.out.println("==============================================");
        System.out.println(orderDTO);
        System.out.println("==============================================");
        orderService.createNewOrderWithReviews(companyID, id, orderDTO);
        return "redirect:/companies/allCompany";
    }

    @GetMapping("/ordersDetails/{companyId}") // Страница просмотра всех заказов компании
    String OrderListToCompany(@PathVariable Long companyId, Model model){
        model.addAttribute("companyID", companyId);
        model.addAttribute("orders", companyService.getCompaniesById(companyId).getOrderList());
        return "products/orders_list";
    }

    //    =========================================== ORDER EDIT =======================================================
    @GetMapping("/ordersDetails/{companyId}/{orderId}") // Страница редактирования Заказа - Get
    String OrderEdit(@PathVariable Long companyId, @PathVariable Long orderId,  Model model){
        model.addAttribute("ordersDTO", orderService.getOrderDTO(orderId));
        model.addAttribute("companyID", companyId);
        System.out.println(orderService.getOrderDTO(orderId));
        return "products/order_edit";
    }

    @PostMapping("/ordersDetails/{companyId}/{orderId}") // Страница редактирования Заказа - Post
    String OrderEditPost(@ModelAttribute ("ordersDTO") OrderDTO orderDTO, @PathVariable Long companyId, @PathVariable Long orderId, RedirectAttributes rm,  Model model){
        log.info("1. Начинаем обновлять данные Заказа");
        orderService.updateOrder(orderDTO, companyId, orderId);
        log.info("5. Обновление Заказа прошло успешно");
        rm.addFlashAttribute("saveSuccess", "true");
        return "redirect:/ordersCompany/ordersDetails/{companyId}/{orderId}";
    }


//    ==========================================================================================================
    @PostMapping ("/status_for_checking/{companyID}/{orderID}") // смена статуса на "на проверке"
    String changeStatusForChecking( @PathVariable Long orderID, @PathVariable Long companyID, Model model){
        if(orderService.changeStatusForOrder(orderID, "На проверке")) {
            log.info("статус заказа успешно изменен на на проверке");
            return "redirect:/ordersCompany/ordersDetails/{companyID}";
        } else {
            log.info("ошибка при изменении статуса заказа на на проверке");
            return "products/orders_list";
        }
    }
    @PostMapping ("/status_for_correct/{companyID}/{orderID}") // смена статуса на "Коррекция"
    String changeStatusForCorrect( @PathVariable Long orderID, @PathVariable Long companyID, Model model){
        if(orderService.changeStatusForOrder(orderID, "Коррекция")) {
            log.info("статус заказа успешно изменен на Коррекция");
            return "redirect:/ordersCompany/ordersDetails/{companyID}";
        } else {
            log.info("ошибка при изменении статуса заказа на Коррекция");
            return "products/orders_list";
        }
    }
    @PostMapping ("/status_for_publish/{companyID}/{orderID}") // смена статуса на "Публикация"
    String changeStatusForPublish( @PathVariable Long orderID, @PathVariable Long companyID, Model model){
        if(orderService.changeStatusForOrder(orderID, "Публикация")) {
            log.info("статус заказа успешно изменен на Публикация");
            return "redirect:/ordersCompany/ordersDetails/{companyID}";
        } else {
            log.info("ошибка при изменении статуса заказа на Публикация");
            return "products/orders_list";
        }
    }
    @PostMapping ("/status_for_publish_ok/{companyID}/{orderID}") // смена статуса на "Опубликовано"
    String changeStatusForPublishOk( @PathVariable Long orderID, @PathVariable Long companyID, Model model){
        if(orderService.changeStatusForOrder(orderID, "Опубликовано")) {
            log.info("статус заказа успешно изменен на Опубликовано");
            return "redirect:/ordersCompany/ordersDetails/{companyID}";
        } else {
            log.info("ошибка при изменении статуса заказа на Опубликовано");
            return "products/orders_list";
        }
    }
    @PostMapping ("/status_to_pay/{companyID}/{orderID}") // смена статуса на "Выставлен счет"
    String changeStatusToPay( @PathVariable Long orderID, @PathVariable Long companyID, Model model){
        if(orderService.changeStatusForOrder(orderID, "Выставлен счет")) {
            log.info("статус заказа успешно изменен на Выставлен счет");
            return "redirect:/ordersCompany/ordersDetails/{companyID}";
        } else {
            log.info("ошибка при изменении статуса заказа на Выставлен счет");
            return "products/orders_list";
        }
    }
    @PostMapping ("/status_no_pay/{companyID}/{orderID}") // смена статуса на "Не оплачено"
    String changeStatusNoPay( @PathVariable Long orderID, @PathVariable Long companyID, Model model){
        if(orderService.changeStatusForOrder(orderID, "Напоминание")) {
            log.info("статус заказа успешно изменен на Не оплачено");
            return "redirect:/ordersCompany/ordersDetails/{companyID}";
        } else {
            log.info("ошибка при изменении статуса заказа на Не оплачено");
            return "products/orders_list";
        }
    }
    @PostMapping ("/status_pay/{companyID}/{orderID}") // смена статуса на "Оплачено"
    String changeStatusPay( @PathVariable Long orderID, @PathVariable Long companyID, Model model){
        if(orderService.changeStatusForOrder(orderID, "Оплачено")) {
            log.info("статус заказа успешно изменен на Оплачено");
            return "redirect:/ordersCompany/ordersDetails/{companyID}";
        } else {
            log.info("ошибка при изменении статуса заказа на Выставлен счет");
            return "products/orders_list";
        }
    }
//    ==========================================================================================================
}

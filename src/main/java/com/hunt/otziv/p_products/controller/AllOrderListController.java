package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.l_lead.services.PromoTextService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.services.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.util.Comparator;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/orders")
public class AllOrderListController {

    private final PromoTextService promoTextService;
    private final OrderService orderService;

    int pageSize = 10; // желаемый размер страницы

    //    =========================================== ORDER ALL =======================================================
    @GetMapping("/all_orders") // Страница просмотра всех заказов компании по всем статусам
    public String AllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            model.addAttribute("TitleName", "Все заказы");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeyword(keyword, pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/all_orders для Админа: ", startTime);
            return "products/orders/order_page";
//            return "products/orders/all_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            model.addAttribute("TitleName", "Все заказы");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManagerAll(principal, keyword, pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/all_orders для Менеджера: ", startTime);
            return "products/orders/order_page";
        }
        else return "products/orders/order_page";
    } // Страница просмотра всех заказов компании по всем статусам

    @GetMapping("/new_orders") // Все заказы - Новые
    public String NewAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех новых заказов для Админа");
            model.addAttribute("TitleName", "Новые заказы");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordAndStatus(keyword, "Новый",pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/new_orders для Админа: ", startTime);
            return "products/orders/order_page";
//            return "products/orders/new_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли в список всех новых заказов для Менеджера");
            model.addAttribute("TitleName", "Новые заказы");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword,"Новый", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/new_orders для Менеджера: ", startTime);
            return "products/orders/order_page";
        }
        if ("ROLE_WORKER".equals(userRole)){
            log.info("Зашли в список всех новых заказов для Работника");
            model.addAttribute("TitleName", "Новые заказы");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByWorker(principal, keyword, "Новый", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/new_orders для Работника: ", startTime);
            return "products/orders/order_page";
        }
        else return "products/orders/order_page";
    } // Все заказы - Новые

    @GetMapping("/to_check") // Все заказы - В проверку
    public String ToCheckAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли в список всех заказов в проверку для Админа");
            model.addAttribute("TitleName", "Заказы в проверку");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordAndStatus(keyword,"В проверку", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/to_check для Админа: ", startTime);
            return "products/orders/order_page";
//            return "products/orders/to_check_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли в список всех заказов в проверку для Менеджера");
            model.addAttribute("TitleName", "Заказы в проверку");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword, "В проверку", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/to_check для Менеджера: ", startTime);
            return "products/orders/order_page";
        }
        else return "products/orders/order_page";
    } // Все заказы - В проверку

    @GetMapping("/on_check") // Все заказы - На проверке
    public String OnCheckAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли в список всех заказов на проверке для Админа");
            model.addAttribute("TitleName", "Заказы на проверке");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordAndStatus(keyword,"На проверке", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/on_check для Админа: ", startTime);
            return "products/orders/order_page";
//            return "products/orders/on_check_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли в список всех заказов на проверке для Менеджера");
            model.addAttribute("TitleName", "Заказы на проверке");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword, "На проверке", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/on_check для Менеджера: ", startTime);
            return "products/orders/order_page";
        }
        else return "products/orders/order_page";
    } // Все заказы - На проверке

    @GetMapping("/correct") // Все заказы - Коррекция
    public String CorrectAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли в список всех коррекций заказов для Админа");
            model.addAttribute("TitleName", "Коррекция заказов");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordAndStatus(keyword,"Коррекция", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/correct для Админа: ", startTime);
            return "products/orders/order_page";
//            return "products/orders/correct_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли в список всех коррекций заказов для Менеджера");
            model.addAttribute("TitleName", "Коррекция заказов");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword, "Коррекция", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/correct для Менеджера: ", startTime);
            return "products/orders/order_page";
        }
        else return "products/orders/order_page";
    } // Все заказы - Коррекция

    @GetMapping("/to_published") // Все заказы - Публикация
    public String ToPublishedAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли в список всех заказов в процессе выполнения для Админа");
            model.addAttribute("TitleName", "Заказы на публикации");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordAndStatus(keyword,"Публикация", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/to_published для Админа: ", startTime);
            return "products/orders/order_page";
//            return "products/orders/to_published_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли в список всех заказов в процессе выполнения для Менеджера");
            model.addAttribute("TitleName", "Заказы на публикации");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword, "Публикация", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/to_published для Менеджера: ", startTime);
            return "products/orders/order_page";
        }
        else return "products/orders/order_page";
    } // Все заказы - Публикация

    @GetMapping("/published") // Все заказы - Опубликовано
    public String PublishedAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли в список всех выполненых заказов для Админа");
            model.addAttribute("TitleName", "Опубликованные заказы");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordAndStatus(keyword,"Опубликовано", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/published для Админа: ", startTime);
            return "products/orders/order_page";
//            return "products/orders/published_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли в список всех выполненых заказов для Менеджера");
            model.addAttribute("TitleName", "Опубликованные заказы");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword, "Опубликовано", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/published для Менеджера: ", startTime);
            return "products/orders/order_page";
        }
        else return "products/orders/order_page";
    } // Все заказы - Опубликовано

    @GetMapping("/payment_check") // Все заказы - Выставлен счет
    public String PaymentCheckOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли в список всех заказов которым выставлен счет для Админа");
            model.addAttribute("TitleName", "Выставлен счет ждем оплату");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordAndStatus(keyword,"Выставлен счет", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/payment_check для Админа: ", startTime);
            return "products/orders/order_page";
//            return "products/orders/payment_check_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли в список всех заказов которым выставлен счет  для Менеджера");
            model.addAttribute("TitleName", "Выставлен счет ждем оплату");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword, "Выставлен счет", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/payment_check для Менеджера: ", startTime);
            return "products/orders/order_page";
        }
        else return "products/orders/order_page";
    } // Все заказы - Выставлен счет

    @GetMapping("/remember") // Все заказы - Напоминание
    public String RememberAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли в список всех напоминаний об оплате заказов для Админа");
            model.addAttribute("TitleName", "Напоминание об оплате");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordAndStatus(keyword,"Напоминание", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/remember для Админа: ", startTime);
            return "products/orders/order_page";
//            return "products/orders/remember_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли в список всех напоминаний об оплате заказов для Менеджера");
            model.addAttribute("TitleName", "Напоминание об оплате");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword,"Напоминание",pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/remember для Менеджера: ", startTime);
            return "products/orders/order_page";
        }
        else return "products/orders/order_page";
    } // Все заказы - Напоминание

    @GetMapping("/no_pay") // Все заказы - Не оплачено
    public String NoPayAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли в список всех не оплаченных заказов для Админа");
            model.addAttribute("TitleName", "Не оплаченые заказы");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordAndStatus(keyword,"Не оплачено", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/no_pay для Админа: ", startTime);
            return "products/orders/order_page";
//            return "products/orders/no_pay_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли в список всех не оплаченных заказов для Менеджера");
            model.addAttribute("TitleName", "Не оплаченые заказы");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword, "Не оплачено",pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/no_pay для Менеджера: ", startTime);
            return "products/orders/order_page";
        }
        else return "products/orders/order_page";
    } // Все заказы - Не оплачено

    @GetMapping("/pay") // Все заказы - Оплачено
    public String PayAllOrdersList(@RequestParam(defaultValue = "") String keyword, Model model, Principal principal, @RequestParam(defaultValue = "0") int pageNumber){
        long startTime = System.nanoTime();
        String userRole = gerRole(principal);

        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли в список всех оплаченных заказов для Админа");
            model.addAttribute("TitleName", "Оплаченные заказы");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordAndStatus(keyword,"Оплачено", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/pay для Админа: ", startTime);
            return "products/orders/order_page";
//            return "products/orders/pay_orders_list";
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли в список всех оплаченных заказов для Менеджера");
            model.addAttribute("TitleName", "Оплаченные заказы");
            model.addAttribute("promoTexts", promoTextService.getAllPromoTexts());
            model.addAttribute("orders", orderService.getAllOrderDTOAndKeywordByManager(principal, keyword, "Оплачено", pageNumber, pageSize));
            checkTimeMethod("Время выполнения AllOrderListController/orders/pay для Менеджера: ", startTime);
            return "products/orders/order_page";
        }
        else return "products/orders/order_page";
    } // Все заказы - Оплачено

    private String gerRole(Principal principal){
        // Получите текущий объект аутентификации
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Получите имя текущего пользователя (пользователя, не роль)
        String username = principal.getName();
        // Получите роль пользователя (предположим, что она хранится в поле "role" в объекте User)
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    } // Берем роль пользователя


    private void checkTimeMethod(String text, long startTime){
        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        System.out.printf(text + "%.4f сек%n", timeElapsed);
    }

//    =========================================== ORDER ALL =======================================================
}


//Hibernate: select p1_0.id,p1_0.promo_text from text_promo p1_0
//        Hibernate: select o1_0.order_id from orders o1_0 order by o1_0.order_changed
//        Hibernate: select o1_0.order_id,o1_0.order_amount,o1_0.order_changed,c1_0.company_id,c1_0.company_active,c2_0.category_id,c2_0.category_title,c1_0.company_city,c1_0.company_comments,c1_0.company_counter_no_pay,c1_0.company_counter_pay,c1_0.create_date,c1_0.date_new_try,c1_0.company_email,c1_0.company_manager,c1_0.company_operator,s3_0.company_status_id,s3_0.status_title,s2_0.subcategory_id,s2_0.category_id,s2_0.subcategory_title,c1_0.company_sum,c1_0.company_phone,c1_0.company_title,c1_0.update_status,c1_0.company_url_chat,c1_0.company_user,o1_0.order_complete,o1_0.order_counter,o1_0.order_created,d1_0.order_detail_order,d1_0.order_detail_id,d1_0.order_detail_amount,d1_0.order_detail_comments,d1_0.order_detail_price,d1_0.order_detail_product,d1_0.order_detail_date_published,f1_0.filial_id,f1_0.company_id,f1_0.filial_title,f1_0.filial_url,m1_0.manager_id,m1_0.pay_text,u2_0.id,u2_0.activate_code,u2_0.active,u2_0.coefficient,u2_0.create_time,u2_0.email,u2_0.fio,u2_0.image,u2_0.password,u2_0.phone_number,u2_0.username,o1_0.order_pay_day,s1_0.order_status_id,s1_0.order_status_title,o1_0.order_sum,w1_0.worker_id,u1_0.id,u1_0.activate_code,u1_0.active,u1_0.coefficient,u1_0.create_time,u1_0.email,u1_0.fio,u1_0.image,u1_0.password,u1_0.phone_number,u1_0.username from orders o1_0 left join order_details d1_0 on o1_0.order_id=d1_0.order_detail_order left join order_statuses s1_0 on s1_0.order_status_id=o1_0.order_status left join filial f1_0 on f1_0.filial_id=o1_0.order_filial left join companies c1_0 on c1_0.company_id=o1_0.order_company left join categorys c2_0 on c2_0.category_id=c1_0.company_category left join subcategoryes s2_0 on s2_0.subcategory_id=c1_0.company_subcategory left join company_status s3_0 on s3_0.company_status_id=c1_0.company_status left join workers w1_0 on w1_0.worker_id=o1_0.order_worker left join users u1_0 on u1_0.id=w1_0.user_id left join managers m1_0 on m1_0.manager_id=o1_0.order_manager join users u2_0 on u2_0.id=m1_0.user_id where o1_0.order_id in(?,?) order by o1_0.order_changed

//Hibernate: select p1_0.id,p1_0.promo_text from text_promo p1_0
//        Hibernate: select o1_0.order_id,o1_0.order_amount,o1_0.order_changed,o1_0.order_company,o1_0.order_complete,o1_0.order_counter,o1_0.order_created,o1_0.order_filial,o1_0.order_manager,o1_0.order_pay_day,o1_0.order_status,o1_0.order_sum,o1_0.order_worker from orders o1_0
//        Hibernate: select o1_0.order_status_id,o1_0.order_status_title from order_statuses o1_0 where o1_0.order_status_id=?
//        Hibernate: select c1_0.company_id,c1_0.company_active,c1_0.company_category,c1_0.company_city,c1_0.company_comments,c1_0.company_counter_no_pay,c1_0.company_counter_pay,c1_0.create_date,c1_0.date_new_try,c1_0.company_email,c1_0.company_manager,c1_0.company_operator,s1_0.company_status_id,s1_0.status_title,c1_0.company_subcategory,c1_0.company_sum,c1_0.company_phone,c1_0.company_title,c1_0.update_status,c1_0.company_url_chat,c1_0.company_user from companies c1_0 left join company_status s1_0 on s1_0.company_status_id=c1_0.company_status where c1_0.company_id=?
//        Hibernate: select m1_0.manager_id,m1_0.pay_text,m1_0.user_id from managers m1_0 where m1_0.manager_id=?
//        Hibernate: select w1_0.company_id,w1_1.worker_id,w1_1.user_id from workers_companies w1_0 join workers w1_1 on w1_1.worker_id=w1_0.worker_id where w1_0.company_id=?
//        Hibernate: select u1_0.id,u1_0.activate_code,u1_0.active,u1_0.coefficient,u1_0.create_time,u1_0.email,u1_0.fio,i1_0.id,i1_0.bytes,i1_0.content_type,i1_0.name,i1_0.original_file_name,i1_0.size,u1_0.password,u1_0.phone_number,u1_0.username from users u1_0 left join images i1_0 on i1_0.id=u1_0.image where u1_0.id=?
//        Hibernate: select f1_0.company_id,f1_0.filial_id,f1_0.filial_title,f1_0.filial_url from filial f1_0 where f1_0.company_id=?
//        Hibernate: select c1_0.category_id,c1_0.category_title from categorys c1_0 where c1_0.category_id=?
//        Hibernate: select s1_0.subcategory_id,s1_0.category_id,s1_0.subcategory_title from subcategoryes s1_0 where s1_0.subcategory_id=?
//        Hibernate: select d1_0.order_detail_order,d1_0.order_detail_id,d1_0.order_detail_amount,d1_0.order_detail_comments,d1_0.order_detail_price,d1_0.order_detail_product,d1_0.order_detail_date_published from order_details d1_0 where d1_0.order_detail_order=?
//        Hibernate: select p1_0.product_id,p1_0.product_price,p2_0.product_category_id,p2_0.product_category_title,p1_0.product_title from products p1_0 left join product_categorys p2_0 on p2_0.product_category_id=p1_0.product_category where p1_0.product_id=?
//        Hibernate: select r1_0.review_order_details,r1_0.review_id,r1_0.review_answer,b1_0.bot_id,b1_0.bot_active,b1_0.bot_counter,b1_0.bot_fio,b1_0.bot_login,b1_0.bot_password,b1_0.bot_status,w1_0.worker_id,w1_0.user_id,r1_0.review_category,r1_0.review_changed,r1_0.review_created,f1_0.filial_id,f1_0.company_id,f1_0.filial_title,f1_0.filial_url,r1_0.review_publish,r1_0.review_publish_date,r1_0.review_subcategory,r1_0.review_text,w2_0.worker_id,w2_0.user_id from reviews r1_0 left join bots b1_0 on b1_0.bot_id=r1_0.review_bot left join workers w1_0 on w1_0.worker_id=b1_0.bot_worker left join filial f1_0 on f1_0.filial_id=r1_0.review_filial left join workers w2_0 on w2_0.worker_id=r1_0.review_worker where r1_0.review_order_details=?
//        Hibernate: select c1_0.company_id,c1_0.company_active,c1_0.company_category,c1_0.company_city,c1_0.company_comments,c1_0.company_counter_no_pay,c1_0.company_counter_pay,c1_0.create_date,c1_0.date_new_try,c1_0.company_email,c1_0.company_manager,c1_0.company_operator,s1_0.company_status_id,s1_0.status_title,c1_0.company_subcategory,c1_0.company_sum,c1_0.company_phone,c1_0.company_title,c1_0.update_status,c1_0.company_url_chat,c1_0.company_user from companies c1_0 left join company_status s1_0 on s1_0.company_status_id=c1_0.company_status where c1_0.company_id=?
//        Hibernate: select w1_0.company_id,w1_1.worker_id,w1_1.user_id from workers_companies w1_0 join workers w1_1 on w1_1.worker_id=w1_0.worker_id where w1_0.company_id=?
//        Hibernate: select f1_0.company_id,f1_0.filial_id,f1_0.filial_title,f1_0.filial_url from filial f1_0 where f1_0.company_id=?
//        Hibernate: select c1_0.category_id,c1_0.category_title from categorys c1_0 where c1_0.category_id=?
//        Hibernate: select s1_0.subcategory_id,s1_0.category_id,s1_0.subcategory_title from subcategoryes s1_0 where s1_0.subcategory_id=?
//        Hibernate: select d1_0.order_detail_order,d1_0.order_detail_id,d1_0.order_detail_amount,d1_0.order_detail_comments,d1_0.order_detail_price,d1_0.order_detail_product,d1_0.order_detail_date_published from order_details d1_0 where d1_0.order_detail_order=?
//        Hibernate: select r1_0.review_order_details,r1_0.review_id,r1_0.review_answer,b1_0.bot_id,b1_0.bot_active,b1_0.bot_counter,b1_0.bot_fio,b1_0.bot_login,b1_0.bot_password,b1_0.bot_status,w1_0.worker_id,w1_0.user_id,r1_0.review_category,r1_0.review_changed,r1_0.review_created,f1_0.filial_id,f1_0.company_id,f1_0.filial_title,f1_0.filial_url,r1_0.review_publish,r1_0.review_publish_date,r1_0.review_subcategory,r1_0.review_text,w2_0.worker_id,w2_0.user_id from reviews r1_0 left join bots b1_0 on b1_0.bot_id=r1_0.review_bot left join workers w1_0 on w1_0.worker_id=b1_0.bot_worker left join filial f1_0 on f1_0.filial_id=r1_0.review_filial left join workers w2_0 on w2_0.worker_id=r1_0.review_worker where r1_0.review_order_details=?
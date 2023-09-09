package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/ordersCompany")
public class OrderController {

    private final ProductService productService;
    private final OrderService orderService;
    private final CompanyService companyService;



    @GetMapping("/{id}")
    String ProductListToCompany(@PathVariable Long id, Model model){
        List<Integer> amounts = new ArrayList<>();
        amounts.add(5);
        amounts.add(10);
        amounts.add(15);
        amounts.add(20);
        model.addAttribute("companyID", id);
        model.addAttribute("amounts", amounts);
        model.addAttribute("products", productService.findAll());
        model.addAttribute("newOrder", orderService.newOrderDTO(id));
        return "products/products_list";
    }

    @GetMapping("/{companyID}/{id}")
    String ProductListToCompany2(@PathVariable Long companyID, @PathVariable Long id, Model model){
        List<Integer> amounts = new ArrayList<>();
        amounts.add(5);
        amounts.add(10);
        amounts.add(15);
        amounts.add(20);
//        System.out.println(companyID);
//        System.out.println(id);
        model.addAttribute("companyID", companyID);
        model.addAttribute("amounts", amounts);
        model.addAttribute("products", productService.findAll());
        model.addAttribute("newOrder", orderService.newOrderDTO(companyID));


        return "products/products_list";
    }

    @PostMapping ("/{companyID}/{id}")
    String newOrder(@ModelAttribute ("newOrder") OrderDTO orderDTO, @PathVariable Long companyID, @PathVariable Long id, Model model){
        System.out.println(orderDTO.getAmount());
        List<Integer> amounts = new ArrayList<>();
        amounts.add(5);
        amounts.add(10);
        amounts.add(15);
        amounts.add(20);
        System.out.println(companyID);
        System.out.println(id);
        System.out.println(orderDTO.getCompany());
        System.out.println(orderDTO.getWorker());
        System.out.println(orderDTO.getCompany().getFilial());
        orderService.createNewOrderWithReviews(companyID, id, orderDTO);
        return "redirect:/ordersCompany/{companyID}/{id}";
    }


    @GetMapping("/ordersDetails/{id}")
    String OrderListToCompany(@PathVariable Long id, Model model){
        model.addAttribute("companyID", id);
//        model.addAttribute("products", productService.findAll());
//        model.addAttribute("newOrder", orderService.newOrderDTO(id));
        model.addAttribute("orders", companyService.getCompaniesById(id).getOrderList());
        return "products/orders_list";
    }
}

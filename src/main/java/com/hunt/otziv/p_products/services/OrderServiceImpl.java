package com.hunt.otziv.p_products.services;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.dto.OrderStatusDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.webjars.NotFoundException;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final CompanyService companyService;
    private final WorkerService workerService;
    private final ManagerService managerService;
    private final OrderDetailsService orderDetailsService;
    private final ProductService productService;
    private final CategoryService categoryService;
    private final SubCategoryService subCategoryService;
    private final BotService botService;
    private final FilialService filialService;
    private final ReviewService reviewService;
    private final OrderStatusService orderStatusService;
    @Override
    public OrderDTO newOrderDTO(Long id) {
        CompanyDTO companyDTO = companyService.getCompaniesDTOById(id); // берем компанию по id с переводом ее в дто нового заказа
        OrderDTO orderDTO = new OrderDTO();
        orderDTO.setCompany(companyDTO); // устанавливаем заказу компанию
        orderDTO.setWorkers(companyDTO.getWorkers()); // список работников в этой компании
        orderDTO.setManager(companyDTO.getManager());
        orderDTO.setStatus(orderStatusService.getOrderStatusDTOByTitle("Новый"));
        return orderDTO;
    }

    @Override
    @Transactional
    public boolean createNewOrderWithReviews(Long companyId, Long productId, OrderDTO orderDTO) { // сохранение нового ORDER, ORDER_DETAIL и списка REVIEWS
        Order order = toEntityOrderFromDTO(orderDTO, productId);
        Order saveOrder = orderRepository.save(order);
        log.info("1. Сохранили ORDER");
        OrderDetails orderDetails = toEntityOrderDetailFromDTO(orderDTO, saveOrder,productId);
        OrderDetails orderDetailsSave = orderDetailsService.save(orderDetails);
        log.info("2. Сохранили ORDER-DETAIL");
        List<Review> reviews = toEntityListReviewsFromDTO(orderDTO, orderDetailsSave);
        log.info("3. Сохранили REVIEWS");
        orderDetailsSave.setReviews(reviews);
        log.info("4. Установили REVIEWS в ORDER-DETAIL");
        OrderDetails orderDetails2 = orderDetailsService.save(orderDetailsSave);
        log.info("5. Сохранили  ORDER-DETAIL с REVIEWS");
        List<OrderDetails> detailsList;
        if (saveOrder.getDetails() != null){
            detailsList = saveOrder.getDetails();
            log.info("6. Взяли ORDER-DETAIL лист");
        }
        else {
            detailsList = new ArrayList<>();
            log.info("6. Создали пустой ORDER-DETAIL лист");
        }
        detailsList.add(orderDetails2);
        log.info("7. Добавили ORDER-DETAIL лист ");
        saveOrder.setDetails(detailsList);
        log.info("8. Установили его в ORDER");
        Order saveOrder2 = orderRepository.save(saveOrder);
        log.info("9. Сохранили ORDER с ORDER-DETAIL в БД");
        System.out.println(saveOrder2);
        return true;
    }

    private Order toEntityOrderFromDTO(OrderDTO orderDTO, Long productId){ // перевод из ДТО в Сущность ORDER
        Product product1 = productService.findById(productId);
        System.out.println(orderDTO.getFilial());
        return Order.builder()
                .amount(orderDTO.getAmount())
                .complete(false)
                .worker(convertWorkerDTOToWorker(orderDTO.getWorker()))
                .company(convertCompanyDTOToCompany(orderDTO.getCompany()))
                .manager(convertManagerDTOToManager(orderDTO.getManager()))
                .filial(convertFilialDTOToFilial(orderDTO.getFilial()))
                .sum(product1.getPrice().multiply(BigDecimal.valueOf(orderDTO.getAmount())))
                .status(convertStatusDTOToStatus(orderDTO.getStatus()))
                .build();
    }

    private Worker convertWorkerDTOToWorker(WorkerDTO workerDTO){ // поиск и перевод из ДТО в Сущность
        return workerService.getWorkerById(workerDTO.getWorkerId());
    }
    private Company convertCompanyDTOToCompany(CompanyDTO companyDTO){
        return companyService.getCompaniesById(companyDTO.getId());
    }
    private Manager convertManagerDTOToManager(ManagerDTO managerDTO){
        return managerService.getManagerById(managerDTO.getManagerId());
    }
    private OrderStatus convertStatusDTOToStatus(OrderStatusDTO orderStatusDTO){
        return orderStatusService.getOrderStatusByTitle(orderStatusDTO.getTitle());
    }
    private Filial convertFilialDTOToFilial(FilialDTO filialDTO){
        System.out.println(filialDTO.getId());
        return filialService.getFilial(filialDTO.getId());
    }


    private OrderDetails toEntityOrderDetailFromDTO(OrderDTO orderDTO, Order order, Long productId){ // перевод из ДТО в Сущность ORDER_DETAIL
        Product product1 = productService.findById(productId);
        return OrderDetails.builder()
                .amount(orderDTO.getAmount())
                .price(product1.getPrice().multiply(BigDecimal.valueOf(orderDTO.getAmount())))
                .order(order)
                .product(product1)
                .build();
    }

    private List<Review> toEntityListReviewsFromDTO(OrderDTO orderDTO, OrderDetails orderDetails){ // составляем список отзывов
        List<Review> reviewList = new ArrayList<>();
        for (int i = 0; i < orderDTO.getAmount(); i++) {
            Review review = toEntityReviewFromDTO(orderDTO.getCompany(), orderDetails, orderDTO.getWorker(), orderDTO.getFilial());
            Review review2 = reviewService.save(review);
            reviewList.add(review2);
        }
        return reviewList;
    }

    private Review toEntityReviewFromDTO(CompanyDTO companyDTO, OrderDetails orderDetails, WorkerDTO workerDTO, FilialDTO filialDTO){ // перевод из ДТО в Сущность REVIEW
        List<Bot> bots = botService.getAllBotsByWorkerIdActiveIsTrue(workerDTO.getWorkerId());
        var random = new SecureRandom();
        return Review.builder()
                .category(convertCategoryDTOToCompany(companyDTO.getCategoryCompany()))
                .subCategory(convertSubCompanyDTOToSubCompany(companyDTO.getSubCategory()))
                .text("Текст отзыва")
                .answer("Ответ на отзыв")
                .orderDetails(orderDetails)
                .bot(bots.get(random.nextInt(bots.size())))
                .filial(convertFilialDTOToFilial(filialDTO))
                .build();
    }
    private Category convertCategoryDTOToCompany(CategoryDTO categoryDTO){
        return categoryService.getCategoryByIdCategory(categoryDTO.getId());
    }
    private SubCategory convertSubCompanyDTOToSubCompany(SubCategoryDTO subCategoryDTO){
        return subCategoryService.getSubCategoryById(subCategoryDTO.getId());
    }

//    ==================================================================================================================

    public boolean changeStatusForOrder(Long orderID, String title){
        try {
            Order order = orderRepository.findById(orderID).orElseThrow(() -> new NotFoundException("Order  not found for orderID: " + orderID));
            order.setStatus(orderStatusService.getOrderStatusByTitle(title));
            orderRepository.save(order);
            return true;
        } catch (Exception e){
            System.out.println(e);
            return false;
        }
    }

//    ==================================================================================================================
}

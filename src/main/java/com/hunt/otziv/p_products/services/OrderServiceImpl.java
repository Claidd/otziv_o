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
import com.hunt.otziv.p_products.dto.ProductDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.UserDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.ObjectNotFoundException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.webjars.NotFoundException;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
        log.info("10. Обновляем счетчик компании в БД");
        Company  company = companyService.getCompaniesById(companyId);
        company.setCounterNoPay(saveOrder2.getAmount());
        companyService.save(company);
        return true;
    }

    private Order toEntityOrderFromDTO(OrderDTO orderDTO, Long productId){ // перевод из ДТО в Сущность ORDER
        Product product1 = productService.findById(productId);
        System.out.println(orderDTO);
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

    //    ======================================== FILIAL UPDATE =========================================================
    // Обновить профиль юзера - начало
    @Override
    @Transactional
    public void updateOrder(OrderDTO orderDTO, Long companyId, Long orderId) {
        log.info("2. Вошли в обновление данных Заказа");
        Order saveOrder = orderRepository.findById(orderId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", orderId)));
        log.info("Достали Заказ");
        boolean isChanged = false;

        /*Временная проверка сравнений*/
        System.out.println("filial: " + !Objects.equals(orderDTO.getFilial().getId(), saveOrder.getFilial().getId()));
        System.out.println("worker: " + !Objects.equals(orderDTO.getWorker().getWorkerId(), saveOrder.getWorker().getId()));
        System.out.println("manager: " + !Objects.equals(orderDTO.getManager().getManagerId(), saveOrder.getWorker().getId()));
        System.out.println("complete: " + !Objects.equals(orderDTO.isComplete(), saveOrder.isComplete()));


        if (!Objects.equals(orderDTO.getFilial().getTitle(), saveOrder.getFilial().getTitle())){ /*Проверка смены названия*/
            log.info("Обновляем филиал");
            saveOrder.setFilial(convertFilialDTOToFilial(orderDTO.getFilial()));
            isChanged = true;
        }
        if (!Objects.equals(orderDTO.getWorker().getWorkerId(), saveOrder.getWorker().getId())){ /*Проверка смены работника*/
            log.info("Обновляем работника");
            saveOrder.setWorker(convertWorkerDTOToWorker(orderDTO.getWorker()));
            isChanged = true;
        }
        if (!Objects.equals(orderDTO.getManager().getManagerId(), saveOrder.getWorker().getId())){ /*Проверка смены работника*/
            log.info("Обновляем менеджера");
            saveOrder.setManager(convertManagerDTOToManager(orderDTO.getManager()));
            isChanged = true;
        }
        if (!Objects.equals(orderDTO.isComplete(), saveOrder.isComplete())){ /*Проверка статус заказа*/
            log.info("Обновляем выполнение Заказа");
            saveOrder.setComplete(orderDTO.isComplete());
            isChanged = true;
        }


        if  (isChanged){
            log.info("3. Начали сохранять обновленный Заказ в БД");
            orderRepository.save(saveOrder);
            log.info("4. Сохранили обновленный Заказ в БД");
        }
        else {
            log.info("3. Изменений не было, сущность в БД не изменена");
        }
    }

//    =====================================================================================================

    public OrderDTO getOrderDTO(Long orderId){
        return  toDTO(orderRepository.findById(orderId).orElseThrow());
    }
    private OrderDTO toDTO (Order order){
        return OrderDTO.builder()
                .id(order.getId())
                .amount(order.getAmount())
                .sum(order.getSum())
                .created(order.getCreated())
                .changed(order.getChanged())
                .status(convertToOrderDTO(order.getStatus()))
                .company(convertToCompanyDTO(order.getCompany()))
                .filial(convertToFilialDTO(order.getFilial()))
                .manager(convertToManagerDTO(order.getManager()))
                .worker(convertToWorkerDTO(order.getWorker()))
                .details(convertToDetailsDTOList(order.getDetails()))
                .complete(order.isComplete())
                .counter(order.getCounter())
                .build();
    }

//    ================================================== CONVERTER =====================================================
    private CompanyDTO convertToCompanyDTO(Company company){
        return CompanyDTO.builder()
                .id(company.getId())
                .title(company.getTitle())
                .manager(convertToManagerDTO(company.getManager()))
                .workers(convertToWorkerDTOList(company.getWorkers()))
                .filials(convertToFilialDTOList(company.getFilial()))
                .build();
    }

    private ManagerDTO convertToManagerDTO(Manager manager){
        return ManagerDTO.builder()
                .managerId(manager.getId())
                .user(manager.getUser())
                .build();
    }

    private OrderStatusDTO convertToOrderDTO(OrderStatus orderStatus){
        return OrderStatusDTO.builder()
                .id(orderStatus.getId())
                .title(orderStatus.getTitle())
                .build();
    }

    private Set<WorkerDTO> convertToWorkerDTOList(Set<Worker> workers){
        return workers.stream().map(this::convertToWorkerDTO).collect(Collectors.toSet());
    }
    private WorkerDTO convertToWorkerDTO(Worker worker){
        return WorkerDTO.builder()
                .workerId(worker.getId())
                .user(worker.getUser())
                .build();
    }
    private Set<FilialDTO> convertToFilialDTOList(Set<Filial> filials){
        return filials.stream().map(this::convertToFilialDTO).collect(Collectors.toSet());
    }
    private FilialDTO convertToFilialDTO(Filial filial){
        return FilialDTO.builder()
                .id(filial.getId())
                .title(filial.getTitle())
                .url(filial.getUrl())
                .build();
    }
    private List<OrderDetailsDTO> convertToDetailsDTOList(List<OrderDetails> details){
        return details.stream().map(this::convertToDetailsDTO).collect(Collectors.toList());
    }
    private OrderDetailsDTO convertToDetailsDTO(OrderDetails orderDetails){
        return OrderDetailsDTO.builder()
                .id(orderDetails.getId())
                .amount(orderDetails.getAmount())
                .price(orderDetails.getPrice())
                .publishedDate(orderDetails.getPublishedDate())
                .product(convertToProductDTO(orderDetails.getProduct()))
                .order(convertToOrderDTO(orderDetails.getOrder()))
                .reviews(convertToReviewsDTOList(orderDetails.getReviews()))
                .comment(orderDetails.getComment())
                .build();
    }
    private ProductDTO convertToProductDTO(Product product){
        return ProductDTO.builder()
                .id(product.getId())
                .title(product.getTitle())
                .price(product.getPrice())
                .build();
    }
    private OrderDTO convertToOrderDTO(Order order){
        return OrderDTO.builder()
                .id(order.getId())
                .build();
    }
    private List<ReviewDTO> convertToReviewsDTOList(List<Review> reviews){
        return reviews.stream().map(this::convertToReviewsDTO).collect(Collectors.toList());
    }
    private ReviewDTO convertToReviewsDTO(Review review){
        return ReviewDTO.builder()
                .id(review.getId())
                .text(review.getText())
                .answer(review.getAnswer())
                .build();
    }

//    ================================================== CONVERTER =====================================================

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
        return filialService.getFilial(filialDTO.getId());
    }


    private OrderDetails toEntityOrderDetailFromDTO(OrderDTO orderDTO, Order order, Long productId){ // перевод из ДТО в Сущность ORDER_DETAIL
        Product product1 = productService.findById(productId);
        return OrderDetails.builder()
                .amount(orderDTO.getAmount())
                .price(product1.getPrice().multiply(BigDecimal.valueOf(orderDTO.getAmount())))
                .order(order)
                .product(product1)
                .comment("")
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
                .publish(false)
                .worker(orderDetails.getOrder().getWorker())
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
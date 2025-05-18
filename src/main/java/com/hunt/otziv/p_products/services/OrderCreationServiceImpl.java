package com.hunt.otziv.p_products.services;

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
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderStatusDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderCreationService;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
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

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderCreationServiceImpl implements OrderCreationService {

    private final OrderRepository orderRepository;
    private final OrderDetailsService orderDetailsService;
    private final CompanyService companyService;
    private final CompanyStatusService companyStatusService;
    private final TelegramService telegramService;
    private final ProductService productService;
    private final ReviewService reviewService;
    private final BotService botService;
    private final ManagerService managerService;
    private final WorkerService workerService;
    private final OrderStatusService orderStatusService;
    private final SubCategoryService subCategoryService;
    private final CategoryService categoryService;
    private final FilialService filialService;

    private static final String STATUS_COMPANY_IN_WORK = "В работе";

    @Transactional
    public boolean createNewOrderWithReviews(Long companyId, Long productId, OrderDTO orderDTO) {
        try {
            Order order = saveOrder(orderDTO, productId);
            log.info("1. Сохранили ORDER");

            // 2. Создаём и сохраняем orderDetails без отзывов
            OrderDetails orderDetails = toEntityOrderDetailFromDTO(orderDTO, order, productId);
            orderDetails = orderDetailsService.save(orderDetails);
            log.info("2. Сохранили ORDER-DETAIL без отзывов");

            // 3. Создаём и сохраняем отзывы, ссылающиеся на уже сохранённый orderDetails
            List<Review> reviews = toEntityListReviewsFromDTO(orderDTO, orderDetails);
            reviewService.saveAll(reviews);
            log.info("3. Сохранили {} отзывов", reviews.size());

            // 4. Присваиваем отзывы orderDetails и обновляем его
            orderDetails.setReviews(reviews);
            orderDetailsService.save(orderDetails);
            log.info("4. Привязали отзывы к ORDER-DETAIL и обновили его");

            // 5. Обновляем заказ с добавленным orderDetails
            updateOrder(order, orderDetails);
            log.info("5. Сохранили ORDER с ORDER-DETAIL в БД");

            // 6. Обновляем счётчики компании
            updateCompanyCounter(order, companyId);
            log.info("6. Обновили счётчики компании");

            // 7. Оповещение
            notifyWorker(order);

            return true;
        } catch (Exception e) {
            log.error("Ошибка при создании нового заказа с отзывами", e);
            throw new RuntimeException("Ошибка при создании нового заказа с отзывами", e);
        }
    }

    private Order saveOrder(OrderDTO orderDTO, Long productId) {
        Order order = toEntityOrderFromDTO(orderDTO, productId);
        return orderRepository.save(order);
    }

    private void updateOrder(Order order, OrderDetails orderDetails) {
        List<OrderDetails> detailsList = Optional.ofNullable(order.getDetails()).orElse(new ArrayList<>());
        detailsList.add(orderDetails);
        order.setDetails(detailsList);
        orderRepository.save(order);
    }

    private void updateCompanyCounter(Order order, Long companyId) {
        Company company = companyService.getCompaniesById(companyId);
        int updatedCounter = company.getCounterNoPay() + (order.getAmount() - company.getCounterNoPay());
        company.setCounterNoPay(updatedCounter);
        company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_WORK));
        companyService.save(company);
    }

    private void notifyWorker(Order order) {
        if (order.getWorker() != null && order.getWorker().getUser() != null) {
            Long chatId = order.getWorker().getUser().getTelegramChatId();
            if (chatId != null) {
                String msg = "У вас новый заказ для: " + order.getCompany().getTitle();
                telegramService.sendMessage(chatId, msg);
            }
        }
    }


    private List<Review> toEntityListReviewsFromDTO(OrderDTO orderDTO, OrderDetails orderDetails){ // Конвертер из DTO для списка отзывов
        List<Review> reviewList = new ArrayList<>();
//        List<Bot> bots = findAllBotsMinusFilial(orderDTO, convertFilialDTOToFilial(orderDTO.getFilial()));
        List<Bot> bots = findAllBotsMinusFilial(orderDTO, convertFilialDTOToFilial(orderDTO.getFilial()));

        for (int i = 0; i < orderDTO.getAmount(); i++) {
            Review review = toEntityReviewFromDTO(orderDTO.getCompany(), orderDetails, orderDTO.getFilial(), bots);
            Review review2 = reviewService.save(review);
            reviewList.add(review2);
        }
        return reviewList;
    } // Конвертер из DTO для списка отзывов

    private Review toEntityReviewFromDTO(CompanyDTO companyDTO, OrderDetails orderDetails, FilialDTO filialDTO, List<Bot> bots){ // Конвертер из DTO для отзыва
        var random = new SecureRandom();
        return Review.builder()
                .category(convertCategoryDTOToCompany(companyDTO.getCategoryCompany()))
                .subCategory(convertSubCompanyDTOToSubCompany(companyDTO.getSubCategory()))
                .text("Текст отзыва")
                .answer("")
                .orderDetails(orderDetails)
                .bot(!bots.isEmpty() ? bots.get(random.nextInt(bots.size())) : null)
                .filial(convertFilialDTOToFilial(filialDTO))
                .publish(false)
                .worker(orderDetails.getOrder().getWorker())
                .product(orderDetails.getProduct())
                .price(orderDetails.getProduct().getPrice())
                .build();
    }// Конвертер из DTO для отзыва

    private List<Bot> findAllBotsMinusFilial(OrderDTO orderDTO, Filial filial){
        List<Bot> bots = botService.getFindAllByFilialCityId(filial.getCity().getId());
        System.out.println("Боты вытащенные из базы по определнному городу: " + bots.size());
        List<Review> reviewListFilial = reviewService.findAllByFilial(filial);

        List<Bot> botsCompany = reviewListFilial.stream().map(Review::getBot).toList();
        System.out.println("Боты вытащенные из базы по определнному городу для удаления: " +  botsCompany.size());
        bots.removeAll(botsCompany);
        System.out.println("Оставшиеся: " + bots.size());
        return bots;
    }

    //    ================================================== CONVERTER =====================================================

    private Worker convertWorkerDTOToWorker(WorkerDTO workerDTO){ // Конвертер из DTO для работника
        return workerService.getWorkerById(workerDTO.getWorkerId());
    } // Конвертер из DTO для работника
    private Company convertCompanyDTOToCompany(CompanyDTO companyDTO){ // Конвертер из DTO для компании
        return companyService.getCompaniesById(companyDTO.getId());
    } // Конвертер из DTO для компании
    private Manager convertManagerDTOToManager(ManagerDTO managerDTO){// Конвертер из DTO для менеджера
        return managerService.getManagerById(managerDTO.getManagerId());
    } // Конвертер из DTO для менеджера
    private OrderStatus convertStatusDTOToStatus(OrderStatusDTO orderStatusDTO){// Конвертер из DTO для статуса заказа
        return orderStatusService.getOrderStatusByTitle(orderStatusDTO.getTitle());
    } // Конвертер из DTO для статуса заказа
    private Filial convertFilialDTOToFilial(FilialDTO filialDTO){// Конвертер из DTO для филиала
        return filialService.getFilial(filialDTO.getId());
    } // Конвертер из DTO для филиала
    private Order toEntityOrderFromDTO(OrderDTO orderDTO, Long productId){ // Конвертер из DTO для заказа
        Product product1 = productService.findById(productId);
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
    } // Конвертер из DTO для заказа
    private OrderDetails toEntityOrderDetailFromDTO(OrderDTO orderDTO, Order order, Long productId){ // Конвертер из DTO для деталей заказа
        Product product1 = productService.findById(productId);
        return OrderDetails.builder()
                .amount(orderDTO.getAmount())
                .price(product1.getPrice().multiply(BigDecimal.valueOf(orderDTO.getAmount())))
                .order(order)
                .product(product1)
                .comment("")
                .build();
    } // Конвертер из DTO для деталей заказа

    private Category convertCategoryDTOToCompany(CategoryDTO categoryDTO){ // Конвертер из DTO для категории
        return categoryService.getCategoryByIdCategory(categoryDTO.getId());
    } // Конвертер из DTO для категории
    private SubCategory convertSubCompanyDTOToSubCompany(SubCategoryDTO subCategoryDTO){// Конвертер из DTO для субкатегории
        return subCategoryService.getSubCategoryById(subCategoryDTO.getId());
    } // Конвертер из DTO для субкатегории

    public OrderDTO convertToOrderDTOToRepeat(Order order){ // Конвертер DTO для создания нового заказа после завершения предыдущего
        return OrderDTO.builder()
                .id(order.getId())
                .amount(order.getAmount())
                .worker(convertToWorkerDTO(order.getWorker()))
                .manager(convertToManagerDTO(order.getManager()))
                .company(convertToCompanyDTO(order.getCompany()))
                .filial(convertToFilialDTO(order.getFilial()))
                .commentsCompany(order.getCompany().getCommentsCompany())
                .status(convertToStatusDTO("Новый"))
                .build();
    } // Конвертер DTO для создания нового заказа после завершения предыдущего
    private WorkerDTO convertToWorkerDTO(Worker worker){// Конвертер DTO для работника
        return WorkerDTO.builder()
                .workerId(worker.getId())
                .user(worker.getUser())
                .build();
    } // Конвертер DTO для работника
    private ManagerDTO convertToManagerDTO(Manager manager){// Конвертер DTO для менеджера
        return ManagerDTO.builder()
                .managerId(manager.getId())
                .user(manager.getUser())
                .payText(manager.getPayText())
                .clientId(manager.getClientId())
                .build();
    } // Конвертер DTO для менеджера
    private CompanyDTO convertToCompanyDTO(Company company){ // Конвертер DTO для компании
        return CompanyDTO.builder()
                .id(company.getId())
                .title(company.getTitle())
                .telephone(company.getTelephone())
                .urlChat(company.getUrlChat())
                .manager(convertToManagerDTO(company.getManager()))
                .workers(convertToWorkerDTOList(company.getWorkers()))
                .filials(convertToFilialDTOList(company.getFilial()))
                .categoryCompany(company.getCategoryCompany() != null ? convertToCategoryDto(company.getCategoryCompany()) : null)
                .subCategory(company.getSubCategory() != null ? convertToSubCategoryDto(company.getSubCategory()) : null)
                .groupId(company.getGroupId())
                .build();
    } // Конвертер DTO для компании

    private OrderStatusDTO convertToStatusDTO(String status) {
        return OrderStatusDTO.builder()
                .title(status)
                .build();
    }
    private FilialDTO convertToFilialDTO(Filial filial){// Конвертер DTO для филиала
        return FilialDTO.builder()
                .id(filial.getId())
                .title(filial.getTitle())
                .url(filial.getUrl())
                .build();
    } // Конвертер DTO для филиала
    private Set<WorkerDTO> convertToWorkerDTOList(Set<Worker> workers){// Конвертер DTO для списка работников
        return workers.stream().map(this::convertToWorkerDTO).collect(Collectors.toSet());
    } // Конвертер DTO для списка работников
    private Set<FilialDTO> convertToFilialDTOList(Set<Filial> filials){ // Конвертер DTO для списка филиалов
        return filials.stream().map(this::convertToFilialDTO).collect(Collectors.toSet());
    } // Конвертер DTO для списка филиалов
    private CategoryDTO convertToCategoryDto(Category category) {// Конвертер DTO для категории
        CategoryDTO categoryDTO = new CategoryDTO();
        categoryDTO.setId(category.getId() != null ? category.getId() : null);
        categoryDTO.setCategoryTitle(category.getCategoryTitle() != null ? category.getCategoryTitle() : null);
        // Other fields if needed
        return categoryDTO;
    } // Конвертер DTO для категории
    private SubCategoryDTO convertToSubCategoryDto(SubCategory subCategory) { // Конвертер DTO для субкатегории
        SubCategoryDTO subCategoryDTO = new SubCategoryDTO();
        subCategoryDTO.setId(subCategory.getId() != null ? subCategory.getId() : 0L);
        subCategoryDTO.setSubCategoryTitle(subCategory.getSubCategoryTitle() != null ? subCategory.getSubCategoryTitle() : "Не выбрано");
        // Other fields if needed
        return subCategoryDTO;
    } // Конвертер DTO для субкатегории


}

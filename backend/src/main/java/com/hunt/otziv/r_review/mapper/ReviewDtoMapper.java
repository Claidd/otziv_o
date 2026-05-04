package com.hunt.otziv.r_review.mapper;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.dto.ProductDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
public class ReviewDtoMapper {

    private static final Long STUB_BOT_ID = 1L;

    public ReviewDTOOne toReviewDTOOne(Review review) {
        if (review == null) {
            return fallbackReviewDTOOne(null);
        }

        try {
            OrderDetails orderDetails = review != null ? review.getOrderDetails() : null;
            Bot bot = review != null ? review.getBot() : null;

            boolean isStubBot = bot != null && bot.getId() != null && STUB_BOT_ID.equals(bot.getId());

            String botFio;
            if (orderDetails == null) {
                botFio = "НЕТ ЗАКАЗА";
            } else if (bot == null) {
                botFio = "Добавьте аккаунты и нажмите сменить";
            } else if (isStubBot) {
                botFio = "Нет доступных аккаунтов";
            } else {
                botFio = Optional.ofNullable(bot.getFio())
                        .filter(name -> !name.trim().isEmpty())
                        .orElse("Бот без имени");
            }

            String companyTitle = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getOrder)
                    .map(Order::getCompany)
                    .map(Company::getTitle)
                    .orElse("НЕТ ЗАКАЗА");

            Long companyId = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getOrder)
                    .map(Order::getCompany)
                    .map(Company::getId)
                    .orElse(null);

            UUID orderDetailsId = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getId)
                    .orElse(null);

            Long orderId = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getOrder)
                    .map(Order::getId)
                    .orElse(null);

            String orderStatus = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getOrder)
                    .map(order -> order.getStatus() != null ? order.getStatus().getTitle() : "")
                    .orElse("");

            Product reviewProduct = review.getProduct();
            Product detailsProduct = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getProduct)
                    .orElse(null);
            String productTitle = Optional.ofNullable(reviewProduct)
                    .map(Product::getTitle)
                    .orElseGet(() -> Optional.ofNullable(detailsProduct)
                            .map(Product::getTitle)
                            .orElse("НЕТ ПРОДУКТА"));
            Long productId = reviewProduct != null ? reviewProduct.getId() : null;
            boolean productPhoto = reviewProduct != null && Boolean.TRUE.equals(reviewProduct.getPhoto());

            String comment = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getComment)
                    .orElse("");

            String orderComments = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getOrder)
                    .map(Order::getZametka)
                    .orElse("");

            String commentCompany = Optional.ofNullable(orderDetails)
                    .map(OrderDetails::getOrder)
                    .map(Order::getCompany)
                    .map(Company::getCommentsCompany)
                    .orElse("");

            String workerFio = Optional.ofNullable(review.getWorker())
                    .map(Worker::getUser)
                    .map(User::getFio)
                    .orElse("");

            if (workerFio.isEmpty()) {
                workerFio = Optional.ofNullable(orderDetails)
                        .map(OrderDetails::getOrder)
                        .map(Order::getManager)
                        .map(Manager::getUser)
                        .map(User::getFio)
                        .orElse("");
            }

            String filialCity = Optional.ofNullable(review.getFilial())
                    .map(Filial::getCity)
                    .map(City::getTitle)
                    .orElse("");

            String filialTitle = Optional.ofNullable(review.getFilial())
                    .map(Filial::getTitle)
                    .orElse("");

            String filialUrl = Optional.ofNullable(review.getFilial())
                    .map(Filial::getUrl)
                    .orElse("");

            String category = Optional.ofNullable(review.getCategory())
                    .map(Category::getCategoryTitle)
                    .orElse("Нет категории");

            String subCategory = Optional.ofNullable(review.getSubCategory())
                    .map(SubCategory::getSubCategoryTitle)
                    .orElse("Нет подкатегории");

            LocalDate created = review.getCreated() != null ? review.getCreated() : LocalDate.now();
            LocalDate changed = review.getChanged() != null ? review.getChanged() : created;
            LocalDate publishedDate = review.getPublishedDate();

            Long botId = null;
            String botLogin = "";
            String botPassword = "";
            Integer botCounter = 0;

            if (bot != null) {
                botId = bot.getId();
                botLogin = Optional.ofNullable(bot.getLogin()).orElse("");
                botPassword = Optional.ofNullable(bot.getPassword()).orElse("");
                botCounter = safeBotCounter(bot);
            }

            return ReviewDTOOne.builder()
                    .id(review.getId())
                    .companyId(companyId)
                    .commentCompany(commentCompany)
                    .orderDetailsId(orderDetailsId)
                    .orderId(orderId)
                    .orderStatus(orderStatus)
                    .text(review.getText() != null ? review.getText() : "")
                    .answer(review.getAnswer() != null ? review.getAnswer() : "")
                    .category(category)
                    .subCategory(subCategory)
                    .botId(botId)
                    .botFio(botFio)
                    .botLogin(botLogin)
                    .botPassword(botPassword)
                    .botCounter(botCounter)
                    .companyTitle(companyTitle)
                    .productTitle(productTitle)
                    .filialCity(filialCity)
                    .filialTitle(filialTitle)
                    .filialUrl(filialUrl)
                    .productId(productId)
                    .workerFio(workerFio)
                    .created(created)
                    .changed(changed)
                    .publishedDate(publishedDate)
                    .publish(review.isPublish())
                    .vigul(review.isVigul())
                    .comment(comment)
                    .orderComments(orderComments)
                    .product(reviewProduct)
                    .productPhoto(productPhoto)
                    .price(review.getPrice())
                    .url(review.getUrl() != null ? review.getUrl() : "")
                    .urlPhoto(review.getUrl() != null ? review.getUrl() : "")
                    .build();

        } catch (Exception e) {
            log.error("Ошибка при преобразовании отзыва ID {} в DTO: {}",
                    review != null ? review.getId() : "null", e.getMessage(), e);

            return fallbackReviewDTOOne(review);
        }
    }

    public ReviewDTO toReviewDTO(Review review) {
        if (review == null) {
            log.error("Попытка преобразования null Review в DTO");
            return null;
        }

        OrderDetails orderDetails = review.getOrderDetails();

        Bot bot = null;
        boolean isStubBot = false;

        try {
            bot = review.getBot();
            if (bot != null) {
                Long botId = bot.getId();
                isStubBot = botId != null && STUB_BOT_ID.equals(botId);
            }
        } catch (EntityNotFoundException e) {
            log.warn("Бот для отзыва ID {} не найден в базе. Будет использована заглушка", review.getId());
            bot = null;
        }

        String botName = getBotName(orderDetails, bot, isStubBot);

        BotDTO botDTO;
        if (bot != null && !isStubBot) {
            try {
                botDTO = convertToBotDTO(bot);
            } catch (EntityNotFoundException e) {
                botDTO = deletedBotDTO();
            }
        } else if (bot == null) {
            botDTO = deletedBotDTO();
        } else {
            botDTO = convertToBotDTO(bot);
        }

        String comment = orderDetails != null ? Optional.ofNullable(orderDetails.getComment()).orElse("") : "";
        UUID orderDetailsId = orderDetails != null ? orderDetails.getId() : null;

        return ReviewDTO.builder()
                .id(review.getId())
                .text(Optional.ofNullable(review.getText()).orElse(""))
                .answer(Optional.ofNullable(review.getAnswer()).orElse(""))
                .created(Optional.ofNullable(review.getCreated()).orElse(LocalDate.now()))
                .changed(Optional.ofNullable(review.getChanged()).orElse(LocalDate.now()))
                .publishedDate(review.getPublishedDate())
                .publish(review.isPublish())
                .vigul(review.isVigul())
                .category(convertToCategoryDto(review.getCategory()))
                .subCategory(convertToSubCategoryDto(review.getSubCategory()))
                .bot(botDTO)
                .botName(botName)
                .botPassword(botDTO != null ? botDTO.getPassword() : "")
                .filial(convertToFilialDTO(review.getFilial()))
                .orderDetails(convertToDetailsDTO(orderDetails))
                .worker(convertToWorkerDTO(review.getWorker()))
                .comment(comment)
                .orderDetailsId(orderDetailsId)
                .product(review.getProduct())
                .price(review.getPrice())
                .url(Optional.ofNullable(review.getUrl()).orElse(""))
                .build();
    }

    private String getBotName(OrderDetails orderDetails, Bot bot, boolean isStubBot) {
        if (orderDetails == null) {
            return "НЕТ ЗАКАЗА";
        } else if (bot == null) {
            return "Бот был удален";
        } else if (isStubBot) {
            return "Нет доступных аккаунтов";
        } else {
            try {
                return Optional.ofNullable(bot.getFio())
                        .filter(name -> !name.trim().isEmpty())
                        .orElse("Бот без имени");
            } catch (EntityNotFoundException e) {
                return "Бот удален";
            }
        }
    }

    private BotDTO convertToBotDTO(Bot bot) {
        if (bot == null) {
            return null;
        }

        if (STUB_BOT_ID.equals(bot.getId())) {
            return BotDTO.builder()
                    .id(STUB_BOT_ID)
                    .login("stub")
                    .password("stub")
                    .fio("Нет доступных аккаунтов")
                    .active(false)
                    .counter(0)
                    .status("Заглушка")
                    .worker(null)
                    .build();
        }

        return BotDTO.builder()
                .id(bot.getId())
                .login(Optional.ofNullable(bot.getLogin()).orElse(""))
                .password(Optional.ofNullable(bot.getPassword()).orElse(""))
                .fio(Optional.ofNullable(bot.getFio()).orElse("Аккаунт без имени"))
                .active(bot.isActive())
                .counter(safeBotCounter(bot))
                .status(bot.getStatus() != null
                        ? Optional.ofNullable(bot.getStatus().getBotStatusTitle()).orElse("Неизвестен")
                        : "Неизвестен")
                .worker(bot.getWorker())
                .build();
    }

    private BotDTO deletedBotDTO() {
        return BotDTO.builder()
                .id(null)
                .login("УДАЛЕН")
                .fio("Бот был удален")
                .password("")
                .active(false)
                .counter(0)
                .status("Удален")
                .build();
    }

    private CategoryDTO convertToCategoryDto(Category category) {
        if (category == null) {
            return null;
        }
        CategoryDTO categoryDTO = new CategoryDTO();
        categoryDTO.setId(category.getId());
        categoryDTO.setCategoryTitle(category.getCategoryTitle());
        return categoryDTO;
    }

    private SubCategoryDTO convertToSubCategoryDto(SubCategory subCategory) {
        if (subCategory == null) {
            return null;
        }
        SubCategoryDTO subCategoryDTO = new SubCategoryDTO();
        subCategoryDTO.setId(subCategory.getId());
        subCategoryDTO.setSubCategoryTitle(subCategory.getSubCategoryTitle());
        return subCategoryDTO;
    }

    private FilialDTO convertToFilialDTO(Filial filial) {
        if (filial == null) {
            return null;
        }
        return FilialDTO.builder()
                .id(filial.getId())
                .title(filial.getTitle())
                .url(filial.getUrl())
                .build();
    }

    private WorkerDTO convertToWorkerDTO(Worker worker) {
        if (worker == null) {
            return null;
        }
        return WorkerDTO.builder()
                .workerId(worker.getId())
                .user(worker.getUser())
                .build();
    }

    private OrderDetailsDTO convertToDetailsDTO(OrderDetails orderDetails) {
        if (orderDetails == null) {
            return null;
        }
        return OrderDetailsDTO.builder()
                .id(orderDetails.getId())
                .amount(orderDetails.getAmount())
                .price(orderDetails.getPrice())
                .publishedDate(orderDetails.getPublishedDate())
                .product(convertToProductDTO(orderDetails.getProduct()))
                .order(convertToOrderDTO(orderDetails.getOrder()))
                .comment(orderDetails.getComment())
                .build();
    }

    private ProductDTO convertToProductDTO(Product product) {
        if (product == null) {
            return null;
        }
        return ProductDTO.builder()
                .id(product.getId())
                .title(product.getTitle())
                .price(product.getPrice())
                .build();
    }

    private OrderDTO convertToOrderDTO(Order order) {
        if (order == null) {
            return null;
        }
        return OrderDTO.builder()
                .id(order.getId())
                .company(convertToCompanyDTO(order.getCompany()))
                .build();
    }

    private CompanyDTO convertToCompanyDTO(Company company) {
        if (company == null) {
            return null;
        }
        return CompanyDTO.builder()
                .id(company.getId())
                .title(company.getTitle())
                .build();
    }

    private int safeBotCounter(Bot bot) {
        return bot != null ? bot.getCounter() : 0;
    }

    private ReviewDTOOne fallbackReviewDTOOne(Review review) {
        return ReviewDTOOne.builder()
                .id(review != null ? review.getId() : 0L)
                .companyTitle("ОШИБКА ПРИ ОБРАБОТКЕ")
                .botFio("ОШИБКА")
                .text(review != null && review.getText() != null ? review.getText() : "Не удалось загрузить данные отзыва")
                .build();
    }
}

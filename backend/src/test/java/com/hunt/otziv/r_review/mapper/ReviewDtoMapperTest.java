package com.hunt.otziv.r_review.mapper;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewDtoMapperTest {

    private final ReviewDtoMapper mapper = new ReviewDtoMapper();

    @Test
    void toReviewDTOOneMapsReviewBoardFields() {
        Review review = reviewFixture();

        ReviewDTOOne dto = mapper.toReviewDTOOne(review);

        assertEquals(42L, dto.getId());
        assertEquals(5L, dto.getCompanyId());
        assertEquals("Компания", dto.getCompanyTitle());
        assertEquals("Комментарий компании", dto.getCommentCompany());
        assertEquals(10L, dto.getOrderId());
        assertEquals("В работе", dto.getOrderStatus());
        assertEquals("Заметка заказа", dto.getOrderComments());
        assertEquals("Текст отзыва", dto.getText());
        assertEquals("Ответ", dto.getAnswer());
        assertEquals("Категория", dto.getCategory());
        assertEquals("Подкатегория", dto.getSubCategory());
        assertEquals(3L, dto.getBotId());
        assertEquals("Bot Fio", dto.getBotFio());
        assertEquals("bot-login", dto.getBotLogin());
        assertEquals("bot-pass", dto.getBotPassword());
        assertEquals(2, dto.getBotCounter());
        assertEquals("Иркутск", dto.getFilialCity());
        assertEquals("Центр", dto.getFilialTitle());
        assertEquals("https://filial.example", dto.getFilialUrl());
        assertEquals(21L, dto.getProductId());
        assertEquals("Продукт отзыва", dto.getProductTitle());
        assertTrue(dto.isProductPhoto());
        assertEquals("Worker Fio", dto.getWorkerFio());
        assertEquals(LocalDate.of(2026, 1, 2), dto.getCreated());
        assertEquals(LocalDate.of(2026, 1, 3), dto.getChanged());
        assertEquals(LocalDate.of(2026, 1, 4), dto.getPublishedDate());
        assertTrue(dto.isPublish());
        assertFalse(dto.isVigul());
        assertEquals("Комментарий детали", dto.getComment());
        assertEquals(new BigDecimal("200.00"), dto.getPrice());
        assertEquals("https://review.example/photo.jpg", dto.getUrl());
        assertEquals("https://review.example/photo.jpg", dto.getUrlPhoto());
    }

    @Test
    void toReviewDTOMapsEditorFieldsAndNestedDtos() {
        Review review = reviewFixture();

        ReviewDTO dto = mapper.toReviewDTO(review);

        assertEquals(42L, dto.getId());
        assertEquals("Текст отзыва", dto.getText());
        assertEquals("Ответ", dto.getAnswer());
        assertEquals("Bot Fio", dto.getBotName());
        assertEquals("bot-pass", dto.getBotPassword());
        assertEquals(3L, dto.getBot().getId());
        assertEquals("Готов", dto.getBot().getStatus());
        assertEquals(11L, dto.getCategory().getId());
        assertEquals("Категория", dto.getCategory().getCategoryTitle());
        assertEquals(12L, dto.getSubCategory().getId());
        assertEquals("Подкатегория", dto.getSubCategory().getSubCategoryTitle());
        assertEquals(8L, dto.getFilial().getId());
        assertEquals("Центр", dto.getFilial().getTitle());
        assertEquals(30L, dto.getWorker().getWorkerId());
        assertEquals("Worker Fio", dto.getWorker().getUser().getFio());
        assertEquals("Комментарий детали", dto.getComment());
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000042"), dto.getOrderDetailsId());
        assertEquals("Продукт детали", dto.getOrderDetails().getProduct().getTitle());
        assertEquals("Компания", dto.getOrderDetails().getOrder().getCompany().getTitle());
        assertSame(review.getProduct(), dto.getProduct());
    }

    @Test
    void toReviewDTOOneReturnsFallbackForNullReview() {
        ReviewDTOOne dto = mapper.toReviewDTOOne(null);

        assertEquals(0L, dto.getId());
        assertEquals("ОШИБКА ПРИ ОБРАБОТКЕ", dto.getCompanyTitle());
        assertEquals("ОШИБКА", dto.getBotFio());
        assertEquals("Не удалось загрузить данные отзыва", dto.getText());
    }

    private Review reviewFixture() {
        Company company = Company.builder()
                .id(5L)
                .title("Компания")
                .commentsCompany("Комментарий компании")
                .build();
        OrderStatus orderStatus = OrderStatus.builder()
                .title("В работе")
                .build();
        Manager manager = Manager.builder()
                .user(User.builder().fio("Manager Fio").build())
                .build();
        Order order = Order.builder()
                .id(10L)
                .company(company)
                .status(orderStatus)
                .manager(manager)
                .zametka("Заметка заказа")
                .build();
        Product detailProduct = Product.builder()
                .id(20L)
                .title("Продукт детали")
                .price(new BigDecimal("100.00"))
                .photo(false)
                .build();
        OrderDetails orderDetails = OrderDetails.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000042"))
                .order(order)
                .product(detailProduct)
                .amount(2)
                .price(new BigDecimal("100.00"))
                .publishedDate(LocalDate.of(2026, 1, 4))
                .comment("Комментарий детали")
                .build();
        City city = City.builder()
                .id(7L)
                .title("Иркутск")
                .build();
        Filial filial = Filial.builder()
                .id(8L)
                .title("Центр")
                .url("https://filial.example")
                .city(city)
                .build();
        Product reviewProduct = Product.builder()
                .id(21L)
                .title("Продукт отзыва")
                .price(new BigDecimal("200.00"))
                .photo(true)
                .build();
        Bot bot = Bot.builder()
                .id(3L)
                .login("bot-login")
                .password("bot-pass")
                .fio("Bot Fio")
                .active(true)
                .counter(2)
                .status(StatusBot.builder().botStatusTitle("Готов").build())
                .build();
        Worker worker = Worker.builder()
                .id(30L)
                .user(User.builder().fio("Worker Fio").build())
                .build();

        return Review.builder()
                .id(42L)
                .text("Текст отзыва")
                .answer("Ответ")
                .created(LocalDate.of(2026, 1, 2))
                .changed(LocalDate.of(2026, 1, 3))
                .publishedDate(LocalDate.of(2026, 1, 4))
                .publish(true)
                .vigul(false)
                .category(Category.builder().id(11L).categoryTitle("Категория").build())
                .subCategory(SubCategory.builder().id(12L).subCategoryTitle("Подкатегория").build())
                .bot(bot)
                .orderDetails(orderDetails)
                .filial(filial)
                .worker(worker)
                .product(reviewProduct)
                .price(new BigDecimal("200.00"))
                .url("https://review.example/photo.jpg")
                .build();
    }
}

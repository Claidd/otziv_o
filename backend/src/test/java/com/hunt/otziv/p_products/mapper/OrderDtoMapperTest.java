package com.hunt.otziv.p_products.mapper;

import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OrderDtoMapperTest {

    private final OrderDtoMapper mapper = new OrderDtoMapper();

    @Test
    void toBoardDTOMapsOrderForManagerBoard() {
        Order order = orderFixture();

        OrderDTOList dto = mapper.toBoardDTO(order);

        assertEquals(10L, dto.getId());
        assertEquals(5L, dto.getCompanyId());
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000010"), dto.getOrderDetailsId());
        assertEquals("Компания", dto.getCompanyTitle());
        assertEquals("Комментарий компании", dto.getCompanyComments());
        assertEquals("Центр", dto.getFilialTitle());
        assertEquals("https://filial.example", dto.getFilialUrl());
        assertEquals("В работе", dto.getStatus());
        assertEquals(new BigDecimal("300.00"), dto.getSum());
        assertEquals("https://chat.example", dto.getCompanyUrlChat());
        assertEquals("79000000000", dto.getCompanyTelephone());
        assertEquals("pay text", dto.getManagerPayText());
        assertEquals(2, dto.getAmount());
        assertEquals(7, dto.getCounter());
        assertEquals("Worker Fio", dto.getWorkerUserFio());
        assertEquals("Категория", dto.getCategoryTitle());
        assertEquals("Подкатегория", dto.getSubCategoryTitle());
        assertEquals("Заметка заказа", dto.getOrderComments());
        assertEquals(3, dto.getDayToChangeStatusAgo());
    }

    @Test
    void toBoardDTORowMapsProjectionFieldsAndFallbacks() {
        UUID detailId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        LocalDate now = LocalDate.now();
        Object[] row = {
                10L,
                5L,
                detailId,
                "Компания",
                null,
                "Центр",
                "https://filial.example",
                "В работе",
                new BigDecimal("300.00"),
                "https://chat.example",
                "79000000000",
                "pay text",
                2,
                7,
                "Worker Fio",
                null,
                "Подкатегория",
                now.minusDays(10),
                now.minusDays(4),
                now.plusDays(1),
                null
        };

        OrderDTOList dto = mapper.toBoardDTO(row);

        assertEquals(10L, dto.getId());
        assertEquals(5L, dto.getCompanyId());
        assertEquals(detailId, dto.getOrderDetailsId());
        assertEquals("Компания", dto.getCompanyTitle());
        assertEquals("", dto.getCompanyComments());
        assertEquals("Центр", dto.getFilialTitle());
        assertEquals("В работе", dto.getStatus());
        assertEquals(new BigDecimal("300.00"), dto.getSum());
        assertEquals(2, dto.getAmount());
        assertEquals(7, dto.getCounter());
        assertEquals("Worker Fio", dto.getWorkerUserFio());
        assertEquals("Не выбрано", dto.getCategoryTitle());
        assertEquals("Подкатегория", dto.getSubCategoryTitle());
        assertEquals(now.minusDays(10), dto.getCreated());
        assertEquals(now.minusDays(4), dto.getChanged());
        assertEquals(now.plusDays(1), dto.getPayDay());
        assertEquals(4, dto.getDayToChangeStatusAgo());
        assertEquals("нет заметок", dto.getOrderComments());
    }

    @Test
    void toOrderDTOMapsNestedDetailsAndReviews() {
        Order order = orderFixture();

        OrderDTO dto = mapper.toOrderDTO(order);

        assertEquals(10L, dto.getId());
        assertEquals(2, dto.getAmount());
        assertEquals(new BigDecimal("300.00"), dto.getSum());
        assertEquals("В работе", dto.getStatus().getTitle());
        assertEquals("Компания", dto.getCompany().getTitle());
        assertEquals("Комментарий компании", dto.getCommentsCompany());
        assertEquals("Центр", dto.getFilial().getTitle());
        assertEquals(2L, dto.getManager().getManagerId());
        assertEquals(3L, dto.getWorker().getWorkerId());
        assertEquals(1, dto.getDetails().size());
        assertEquals("Продукт", dto.getDetails().get(0).getProduct().getTitle());
        assertEquals(1, dto.getDetails().get(0).getReviews().size());
        assertEquals("Текст отзыва", dto.getDetails().get(0).getReviews().get(0).getText());
        assertFalse(dto.isComplete());
        assertEquals(7, dto.getCounter());
        assertEquals("Заметка заказа", dto.getOrderComments());
        assertEquals("group-1", dto.getGroupId());
    }

    @Test
    void toRepeatOrderDTOForcesNewStatus() {
        OrderDTO dto = mapper.toRepeatOrderDTO(orderFixture(), "Новый");

        assertEquals(10L, dto.getId());
        assertEquals(2, dto.getAmount());
        assertEquals("Новый", dto.getStatus().getTitle());
        assertEquals("Компания", dto.getCompany().getTitle());
        assertEquals("Центр", dto.getFilial().getTitle());
    }

    private Order orderFixture() {
        Category category = Category.builder()
                .id(20L)
                .categoryTitle("Категория")
                .build();
        SubCategory subCategory = SubCategory.builder()
                .id(21L)
                .subCategoryTitle("Подкатегория")
                .build();
        Worker worker = Worker.builder()
                .id(3L)
                .user(User.builder().fio("Worker Fio").build())
                .build();
        Manager manager = Manager.builder()
                .id(2L)
                .user(User.builder().fio("Manager Fio").build())
                .payText("pay text")
                .clientId("client-1")
                .build();
        Filial filial = Filial.builder()
                .id(6L)
                .title("Центр")
                .url("https://filial.example")
                .build();
        Company company = Company.builder()
                .id(5L)
                .title("Компания")
                .telephone("79000000000")
                .urlChat("https://chat.example")
                .commentsCompany("Комментарий компании")
                .manager(manager)
                .workers(Set.of(worker))
                .filial(Set.of(filial))
                .categoryCompany(category)
                .subCategory(subCategory)
                .groupId("group-1")
                .build();
        Product product = Product.builder()
                .id(30L)
                .title("Продукт")
                .price(new BigDecimal("150.00"))
                .build();
        Order order = Order.builder()
                .id(10L)
                .created(LocalDate.now().minusDays(10))
                .changed(LocalDate.now().minusDays(3))
                .payDay(LocalDate.now().plusDays(2))
                .amount(2)
                .sum(new BigDecimal("300.00"))
                .counter(7)
                .zametka("Заметка заказа")
                .status(OrderStatus.builder().id(1L).title("В работе").build())
                .worker(worker)
                .manager(manager)
                .company(company)
                .filial(filial)
                .complete(false)
                .build();
        OrderDetails details = OrderDetails.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000010"))
                .order(order)
                .product(product)
                .amount(2)
                .price(new BigDecimal("300.00"))
                .comment("Комментарий детали")
                .publishedDate(LocalDate.now().plusDays(5))
                .build();
        Review review = Review.builder()
                .id(40L)
                .text("Текст отзыва")
                .answer("Ответ")
                .orderDetails(details)
                .build();
        details.setReviews(List.of(review));
        order.setDetails(List.of(details));
        return order;
    }
}

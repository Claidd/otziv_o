package com.hunt.otziv.p_products.status;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderReviewCheckMessageBuilderTest {

    @Test
    void buildsReviewCheckTextWithShortOrderDetailLink() {
        AppSettingService appSettingService = mock(AppSettingService.class);
        OrderReviewCheckMessageBuilder builder = new OrderReviewCheckMessageBuilder(appSettingService);
        UUID detailId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_REVIEW_REMINDER_TEXT,
                "{companyAndFilial}\n\nЗдравствуйте! Напоминаем, пожалуйста, проверьте шаблоны отзывов и внесите правки, если они нужны.\n\nСсылка на проверку отзывов: {reviewLink}"
        )).thenReturn("{companyAndFilial}\n{reviewLink}\n{sum}");
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_REVIEW_LINK_BASE_URL,
                "https://o-ogo.ru"
        )).thenReturn("https://o-ogo.ru/");

        Order order = new Order();
        order.setCompany(company("СТК"));
        order.setFilial(filial("Тестовая, 1"));
        order.setSum(BigDecimal.valueOf(1230));
        OrderDetails detail = new OrderDetails();
        detail.setId(detailId);
        order.setDetails(new LinkedList<>());
        order.getDetails().add(detail);

        assertEquals(
                "СТК. Тестовая, 1\nhttps://o-ogo.ru/11111111-1111-1111-1111-111111111111\n1230",
                builder.reviewCheckMessage(order)
        );
    }

    private Company company(String title) {
        Company company = new Company();
        company.setTitle(title);
        return company;
    }

    private Filial filial(String title) {
        Filial filial = new Filial();
        filial.setTitle(title);
        return filial;
    }
}

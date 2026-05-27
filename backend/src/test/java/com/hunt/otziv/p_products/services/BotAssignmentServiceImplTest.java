package com.hunt.otziv.p_products.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotAssignmentServiceImplTest {

    @Mock
    private BotService botService;

    @Mock
    private FilialService filialService;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private TelegramService telegramService;

    @Test
    void getAvailableBotsByRulesExcludesBotsAlreadyUsedInCompany() {
        BotAssignmentServiceImpl service = service();
        City city = city(5L, "Иркутск");
        Filial filial = filial(20L, company(10L), city);
        Bot usedInCompany = bot(101L, "Впишите Имя Фамилию", 0);
        Bot free = bot(102L, "Впишите Имя Фамилию", 0);

        when(botService.getFindAllByFilialCityId(5L)).thenReturn(List.of(usedInCompany, free));
        when(reviewRepository.findUsedBotIdsByCompanyId(10L)).thenReturn(Set.of(101L));
        when(filialService.findByCityId(5L)).thenReturn(List.of(filial));

        List<Bot> available = service.getAvailableBotsByRules(filial, false, 1);

        assertEquals(List.of(free), available);
    }

    @Test
    @SuppressWarnings("unchecked")
    void assignBotForReviewChangeExcludesCompanyUsedBotsWhenClaimingReserveBot() {
        BotAssignmentServiceImpl service = service();
        City city = city(5L, "Иркутск");
        Filial filial = filial(20L, company(10L), city);
        Review review = new Review();
        review.setFilial(filial);
        Bot stubBot = bot(1L, "Нет доступных аккаунтов", 0);

        when(botService.getFindAllByFilialCityId(5L)).thenReturn(List.of());
        when(reviewRepository.findUsedBotIdsByCompanyId(10L)).thenReturn(Set.of(777L));
        when(filialService.findByCityId(5L)).thenReturn(List.of(filial));
        when(botService.claimReserveBotForCity(eq(city), anyCollection())).thenReturn(Optional.empty());
        when(botService.findBotById(1L)).thenReturn(stubBot);

        Bot assigned = service.assignBotForReviewChange(review, Set.of(9L));

        ArgumentCaptor<Collection<Long>> excludedIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(botService).claimReserveBotForCity(eq(city), excludedIdsCaptor.capture());
        assertTrue(excludedIdsCaptor.getValue().containsAll(Set.of(777L, 9L)));
        assertSame(stubBot, assigned);
    }

    @Test
    void assignBotsToNewReviewsUsesPerReviewFilialsWhenRepeatingMixedOrder() {
        BotAssignmentServiceImpl service = service();
        Company company = company(10L);
        City cityA = city(5L, "Иркутск");
        City cityB = city(6L, "Ангарск");
        Filial filialA = filial(20L, company, cityA);
        Filial filialB = filial(21L, company, cityB);
        Bot botA = bot(101L, "А", 0);
        Bot botB = bot(102L, "Б", 0);

        Order order = new Order();
        order.setCompany(company);
        order.setFilial(filialA);
        Product product = Product.builder().id(1L).build();
        OrderDetails details = OrderDetails.builder()
                .order(order)
                .product(product)
                .build();

        OrderDTO orderDTO = OrderDTO.builder()
                .amount(2)
                .reviewFilialIds(List.of(20L, 21L))
                .build();

        when(reviewRepository.findUsedBotIdsByCompanyId(10L)).thenReturn(Set.of());
        when(filialService.getFilial(20L)).thenReturn(filialA);
        when(filialService.getFilial(21L)).thenReturn(filialB);
        when(botService.getFindAllByFilialCityId(5L)).thenReturn(List.of(botA));
        when(botService.getFindAllByFilialCityId(6L)).thenReturn(List.of(botB));
        when(filialService.findByCityId(5L)).thenReturn(List.of(filialA));
        when(filialService.findByCityId(6L)).thenReturn(List.of(filialB));

        List<Review> reviews = service.assignBotsToNewReviews(orderDTO, details);

        assertEquals(2, reviews.size());
        assertSame(filialA, reviews.get(0).getFilial());
        assertSame(botA, reviews.get(0).getBot());
        assertSame(filialB, reviews.get(1).getFilial());
        assertSame(botB, reviews.get(1).getBot());
    }

    private BotAssignmentServiceImpl service() {
        return new BotAssignmentServiceImpl(botService, filialService, reviewRepository, telegramService);
    }

    private Bot bot(Long id, String fio, int counter) {
        Bot bot = new Bot();
        bot.setId(id);
        bot.setFio(fio);
        bot.setCounter(counter);
        bot.setActive(true);
        StatusBot status = new StatusBot();
        status.setBotStatusTitle("Новый");
        bot.setStatus(status);
        return bot;
    }

    private Company company(Long id) {
        Company company = new Company();
        company.setId(id);
        return company;
    }

    private City city(Long id, String title) {
        City city = new City();
        city.setId(id);
        city.setTitle(title);
        return city;
    }

    private Filial filial(Long id, Company company, City city) {
        Filial filial = new Filial();
        filial.setId(id);
        filial.setCompany(company);
        filial.setCity(city);
        return filial;
    }
}

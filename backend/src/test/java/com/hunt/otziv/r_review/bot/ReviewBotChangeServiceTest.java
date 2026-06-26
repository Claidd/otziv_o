package com.hunt.otziv.r_review.bot;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.config.email.EmailService;
import com.hunt.otziv.p_products.services.service.BotAssignmentService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewBotChangeServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private BotService botService;

    @Mock
    private EmailService emailService;

    @Mock
    private BotAssignmentService botAssignmentService;

    @Mock
    private FilialService filialService;

    @Mock
    private ReviewAccountWalkScheduleService accountWalkScheduleService;

    @Mock
    private ReviewBotCooldownService botCooldownService;

    @Mock
    private BusinessAuditService businessAuditService;

    @Test
    void changeBotAssignsNewBotAndUpdatesVigulByCounter() {
        ReviewBotChangeService service = service();
        Review review = new Review();
        review.setVigul(true);
        Bot selectedBot = bot(7L, "Иван Петров", 2);

        when(reviewRepository.findById(15L)).thenReturn(Optional.of(review));
        when(botAssignmentService.assignBotForReviewChange(same(review), eq(Set.of())))
                .thenReturn(selectedBot);

        service.changeBot(15L);

        assertSame(selectedBot, review.getBot());
        assertFalse(review.isVigul());
        verify(reviewRepository).save(review);
    }

    @Test
    void deActivateAndChangeBotUsesCurrentBotWhenRequestBotIdIsMissing() {
        ReviewBotChangeService service = service();
        City city = city(9L, "Иркутск");
        Filial filial = filial(3L, city);
        Bot currentBot = bot(5L, "Старый Бот", 0);
        currentBot.setActive(true);
        Bot selectedBot = bot(8L, "Иван Петров", 4);
        Review review = new Review();
        review.setFilial(filial);
        review.setBot(currentBot);
        review.setVigul(false);

        when(reviewRepository.findById(21L)).thenReturn(Optional.of(review));
        when(botService.getFindAllByFilialCityId(9L)).thenReturn(List.of(currentBot));
        when(botService.findBotById(5L)).thenReturn(currentBot);
        when(botAssignmentService.assignBotForReviewChange(same(review), eq(Set.of(5L))))
                .thenReturn(selectedBot);

        service.deActivateAndChangeBot(21L, null);

        assertFalse(currentBot.isActive());
        assertSame(selectedBot, review.getBot());
        assertTrue(review.isVigul());
        verify(botService).save(currentBot);
        verify(emailService).sendSimpleEmail(
                eq("o-company-server@mail.ru"),
                eq("Мало аккаунтов у города"),
                contains("Иркутск")
        );
        verify(reviewRepository).save(review);
    }

    @Test
    void deActivateAndChangeBotReassignsOtherUnpublishedReviewsWithBlockedBot() {
        ReviewBotChangeService service = service();
        City city = city(9L, "Иркутск");
        Filial filial = filial(3L, city);
        Bot blockedBot = bot(5L, "Старый Бот", 0);
        blockedBot.setActive(true);
        Bot currentReplacement = bot(8L, "Иван Петров", 4);
        Bot affectedReplacement = bot(9L, "Петр Иванов", 1);

        Review currentReview = new Review();
        currentReview.setId(21L);
        currentReview.setFilial(filial);
        currentReview.setBot(blockedBot);

        Review affectedReview = new Review();
        affectedReview.setId(22L);
        affectedReview.setFilial(filial);
        affectedReview.setBot(blockedBot);

        when(reviewRepository.findById(21L)).thenReturn(Optional.of(currentReview));
        when(botService.getFindAllByFilialCityId(9L)).thenReturn(List.of(blockedBot));
        when(botService.findBotById(5L)).thenReturn(blockedBot);
        when(botAssignmentService.assignBotForReviewChange(same(currentReview), anyCollection()))
                .thenReturn(currentReplacement);
        when(botAssignmentService.assignBotForReviewChange(same(affectedReview), anyCollection()))
                .thenReturn(affectedReplacement);
        when(reviewRepository.findUnpublishedReviewsByBotIdForReassignment(5L, 21L))
                .thenReturn(List.of(affectedReview));

        service.deActivateAndChangeBot(21L, null);

        assertFalse(blockedBot.isActive());
        assertSame(currentReplacement, currentReview.getBot());
        assertSame(affectedReplacement, affectedReview.getBot());
        ArgumentCaptor<Iterable<Review>> savedReviewsCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(reviewRepository).saveAll(savedReviewsCaptor.capture());
        assertTrue(savedReviewsCaptor.getValue().iterator().hasNext());
    }

    @Test
    void findAllBotsMinusFilialPrefersTemplateBotsForNotVigulReview() {
        ReviewBotChangeService service = service();
        City city = city(4L, "Иркутск");
        Filial filial = filial(11L, city);
        Review review = new Review();
        review.setId(100L);
        review.setFilial(filial);
        review.setVigul(false);

        Bot templateBot = bot(31L, "Впишите Имя Фамилию", 0);
        Bot regularBot = bot(32L, "Иван Петров", 0);
        Bot usedBot = bot(33L, "Петр Иванов", 0);

        when(botService.getFindAllByFilialCityId(4L))
                .thenReturn(List.of(templateBot, regularBot, usedBot));
        when(reviewRepository.findBotIdsByFilialIdExcludingReview(11L, 100L)).thenReturn(Set.of(33L));
        when(filialService.findByCityId(4L)).thenReturn(List.of(filial));

        List<Bot> bots = service.findAllBotsMinusFilial(review);

        assertEquals(List.of(templateBot), bots);
    }

    @Test
    void changeBotClearsVigulWhenAssignmentReturnsNull() {
        ReviewBotChangeService service = service();
        Review review = new Review();
        review.setVigul(true);

        when(reviewRepository.findById(16L)).thenReturn(Optional.of(review));
        when(botAssignmentService.assignBotForReviewChange(same(review), anyCollection()))
                .thenReturn(null);

        service.changeBot(16L);

        assertFalse(review.isVigul());
        verify(reviewRepository).save(review);
    }

    @Test
    void assignNewAccountClaimsAccountAndClearsVigulForRegularFilialCity() {
        ReviewBotChangeService service = service();
        City city = city(9L, "Иркутск");
        Filial filial = filial(11L, city);
        filial.setCompany(company(22L));
        Bot currentBot = bot(5L, "Старый Бот", 0);
        Bot selectedBot = bot(88L, "Впиши Имя Фамилию", 99);
        Review review = new Review();
        review.setFilial(filial);
        review.setBot(currentBot);
        review.setVigul(true);

        when(reviewRepository.findByIdForBotChange(44L)).thenReturn(Optional.of(review));
        when(reviewRepository.findUsedBotIdsByCompanyId(22L)).thenReturn(Set.of(77L));
        when(botService.claimNewAccountForCity(same(city), eq(Set.of(77L, 5L)))).thenReturn(Optional.of(selectedBot));

        service.assignNewAccount(44L);

        assertSame(selectedBot, review.getBot());
        verify(accountWalkScheduleService).synchronizeAfterAccountChange(review, false);
        verify(reviewRepository).save(review);
    }

    @Test
    void assignNewAccountUsesOwnCityCleanAccountsForSpecialCities() {
        ReviewBotChangeService service = service();

        for (Long cityId : List.of(320L, 326L)) {
            City city = city(cityId, "Город " + cityId);
            Filial filial = filial(12L + cityId, city);
            filial.setCompany(company(90L + cityId));
            Bot currentBot = bot(5L + cityId, "Старый Бот", 0);
            Bot selectedBot = bot(800L + cityId, "Впиши Имя Фамилию", 0);
            Review review = new Review();
            Long reviewId = 45L + cityId;
            review.setId(reviewId);
            review.setFilial(filial);
            review.setBot(currentBot);

            when(reviewRepository.findByIdForBotChange(reviewId)).thenReturn(Optional.of(review));
            when(reviewRepository.findUsedBotIdsByCompanyId(90L + cityId)).thenReturn(Set.of(77L + cityId));
            when(botService.claimNewAccountFromOwnCity(same(city), eq(Set.of(77L + cityId, 5L + cityId))))
                    .thenReturn(Optional.of(selectedBot));

            service.assignNewAccount(reviewId);

            assertSame(selectedBot, review.getBot());
            verify(reviewRepository).save(review);
        }
        verify(botService, never()).claimNewAccountForCity(any(), anyCollection());
    }

    private ReviewBotChangeService service() {
        return new ReviewBotChangeService(
                reviewRepository,
                botService,
                emailService,
                botAssignmentService,
                filialService,
                accountWalkScheduleService,
                botCooldownService,
                businessAuditService
        );
    }

    private Bot bot(Long id, String fio, int counter) {
        Bot bot = new Bot();
        bot.setId(id);
        bot.setFio(fio);
        bot.setCounter(counter);
        bot.setActive(true);
        bot.setStatus(newStatus());
        return bot;
    }

    private StatusBot newStatus() {
        StatusBot status = new StatusBot();
        status.setBotStatusTitle("Новый");
        return status;
    }

    private City city(Long id, String title) {
        City city = new City();
        city.setId(id);
        city.setTitle(title);
        return city;
    }

    private Filial filial(Long id, City city) {
        Filial filial = new Filial();
        filial.setId(id);
        filial.setCity(city);
        return filial;
    }

    private Company company(Long id) {
        Company company = new Company();
        company.setId(id);
        return company;
    }
}

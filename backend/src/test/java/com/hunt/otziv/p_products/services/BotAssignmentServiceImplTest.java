package com.hunt.otziv.p_products.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.r_review.bot.service.ReviewAccountWalkScheduleService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.bot.service.ReviewBotCooldownService;
import com.hunt.otziv.r_review.bot.service.ReviewBotAssignmentGuardService;
import com.hunt.otziv.r_review.bot.model.ReviewBotAssignmentMode;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotAssignmentServiceImplTest {

    @Mock
    private BotService botService;

    @Mock
    private FilialService filialService;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private TelegramService telegramService;

    @Mock
    private ReviewBotCooldownService botCooldownService;

    @Mock
    private ReviewAccountWalkScheduleService accountWalkScheduleService;

    @Mock
    private ReviewBotAssignmentGuardService assignmentGuardService;

    @Mock
    private BusinessAuditService businessAuditService;

    @BeforeEach
    void allowCompanyLocks() {
        lenient().when(companyRepository.findByIdForBotAssignmentLock(anyLong()))
                .thenAnswer(invocation -> Optional.of(company(invocation.getArgument(0))));
        lenient().when(assignmentGuardService.scope(anyLong(), any()))
                .thenAnswer(invocation -> new ReviewBotAssignmentGuardService.AssignmentScope(
                        invocation.getArgument(0), invocation.getArgument(1), null, null));
        lenient().when(assignmentGuardService.blockedBotIds(any())).thenReturn(Set.of());
        lenient().when(assignmentGuardService.lockIfEligible(any(), any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
    }

    @Test
    void assignBotsToNewReviewsUsesSelectedAccountReadiness() {
        BotAssignmentServiceImpl service = service();
        Company company = company(10L);
        City city = city(5L, "Иркутск");
        Filial filial = filial(20L, company, city);
        Bot walkedBot = bot(101L, "Готовый аккаунт", 2);
        Bot unwalkedBot = bot(102L, "Новый аккаунт", 1);
        Order order = new Order();
        order.setCompany(company);
        order.setFilial(filial);
        OrderDetails details = OrderDetails.builder()
                .order(order)
                .product(Product.builder().id(1L).build())
                .build();

        when(botService.getFindAllByFilialCityId(5L)).thenReturn(List.of(walkedBot, unwalkedBot));
        when(filialService.findByCityId(5L)).thenReturn(List.of(filial));
        when(botCooldownService.isAvailableForAssignment(any())).thenReturn(true);
        when(accountWalkScheduleService.isWalkedAccount(walkedBot)).thenReturn(true);
        when(accountWalkScheduleService.isWalkedAccount(unwalkedBot)).thenReturn(false);

        List<Review> reviews = service.assignBotsToNewReviews(
                OrderDTO.builder().amount(2).build(),
                details
        );

        assertEquals(2, reviews.size());
        assertTrue(reviews.stream().filter(review -> review.getBot() == walkedBot).findFirst().orElseThrow().isVigul());
        assertFalse(reviews.stream().filter(review -> review.getBot() == unwalkedBot).findFirst().orElseThrow().isVigul());
    }

    @Test
    void promotionOnlyMovesFalseToTrueAndPreservesManualWalkConfirmation() {
        BotAssignmentServiceImpl service = service();
        Bot bot = bot(101L, "Готовый аккаунт", 2);
        Review pending = new Review();
        pending.setId(1L);
        pending.setBot(bot);
        Review manuallyWalked = new Review();
        manuallyWalked.setId(2L);
        manuallyWalked.setBot(bot);
        manuallyWalked.setVigul(true);

        when(accountWalkScheduleService.isWalkedAccount(bot)).thenReturn(true);
        doAnswer(invocation -> {
            Review review = invocation.getArgument(0);
            review.setVigul(true);
            return null;
        }).when(accountWalkScheduleService).synchronizeAfterAccountChange(pending);

        int promoted = service.promoteReviewsWithWalkedAccounts(List.of(pending, manuallyWalked));

        assertEquals(1, promoted);
        assertTrue(pending.isVigul());
        assertTrue(manuallyWalked.isVigul());
        verify(reviewRepository).saveAll(List.of(pending));
        verify(accountWalkScheduleService, never())
                .synchronizeAfterAccountChange(manuallyWalked);
    }

    @Test
    void getAvailableBotsByRulesExcludesBotsAlreadyUsedInCompany() {
        BotAssignmentServiceImpl service = service();
        City city = city(5L, "Иркутск");
        Filial filial = filial(20L, company(10L), city);
        Bot usedInCompany = bot(101L, "Впишите Имя Фамилию", 0);
        Bot free = bot(102L, "Впишите Имя Фамилию", 0);

        when(botService.getFindAllByFilialCityId(5L)).thenReturn(List.of(usedInCompany, free));
        when(assignmentGuardService.blockedBotIds(any())).thenReturn(Set.of(101L));
        when(filialService.findByCityId(5L)).thenReturn(List.of(filial));
        when(botCooldownService.isAvailableForAssignment(any())).thenReturn(true);

        List<Bot> available = service.getAvailableBotsByRules(filial, false, 1);

        assertEquals(List.of(free), available);
    }

    @Test
    void getAvailableBotsByRulesExcludesInactiveAndReservedBots() {
        BotAssignmentServiceImpl service = service();
        City city = city(5L, "Иркутск");
        Filial filial = filial(20L, company(10L), city);
        Bot inactive = bot(101L, "Впишите Имя Фамилию", 0);
        inactive.setActive(false);
        Bot reserved = bot(102L, "Впишите Имя Фамилию", 0);
        Bot free = bot(103L, "Впишите Имя Фамилию", 0);

        when(botService.getFindAllByFilialCityId(5L)).thenReturn(List.of(inactive, reserved, free));
        when(assignmentGuardService.blockedBotIds(any())).thenReturn(Set.of(102L));
        when(filialService.findByCityId(5L)).thenReturn(List.of(filial));
        when(botCooldownService.isAvailableForAssignment(any())).thenReturn(true);

        List<Bot> available = service.getAvailableBotsByRules(filial, false, 1);

        assertEquals(List.of(free), available);
    }

    @Test
    void getAvailableBotsByRulesExcludesCoolingDownBots() {
        BotAssignmentServiceImpl service = service();
        City city = city(5L, "Иркутск");
        Filial filial = filial(20L, company(10L), city);
        Bot coolingDown = bot(101L, "Впишите Имя Фамилию", 0);
        Bot free = bot(102L, "Впишите Имя Фамилию", 0);

        when(botService.getFindAllByFilialCityId(5L)).thenReturn(List.of(coolingDown, free));
        when(filialService.findByCityId(5L)).thenReturn(List.of(filial));
        when(botCooldownService.isAvailableForAssignment(any())).thenReturn(true);
        when(botCooldownService.isAvailableForAssignment(coolingDown)).thenReturn(false);

        List<Bot> available = service.getAvailableBotsByRules(filial, false, 1);

        assertEquals(List.of(free), available);
    }

    @Test
    @SuppressWarnings("unchecked")
    void assignBotForReviewChangeExcludesCompanyUsedBotsWhenClaimingNewAccount() {
        BotAssignmentServiceImpl service = service();
        City city = city(5L, "Иркутск");
        Filial filial = filial(20L, company(10L), city);
        Review review = new Review();
        review.setFilial(filial);
        Bot stubBot = bot(1L, "Нет доступных аккаунтов", 0);

        when(botService.getFindAllByFilialCityId(5L)).thenReturn(List.of());
        when(assignmentGuardService.blockedBotIds(any())).thenReturn(Set.of(777L, 888L));
        when(filialService.findByCityId(5L)).thenReturn(List.of(filial));
        when(botService.claimNewAccountForCity(eq(city), anyCollection())).thenReturn(Optional.empty());
        when(botService.findBotById(1L)).thenReturn(stubBot);

        Bot assigned = service.assignBotForReviewChange(review, Set.of(9L));

        ArgumentCaptor<Collection<Long>> excludedIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(botService).claimNewAccountForCity(eq(city), excludedIdsCaptor.capture());
        assertTrue(excludedIdsCaptor.getValue().containsAll(Set.of(777L, 888L, 9L)));
        assertSame(stubBot, assigned);
    }

    @Test
    void nagulChangeAssignsOnlyCounterZeroOrOneAccount() {
        BotAssignmentServiceImpl service = service();
        City city = city(5L, "Иркутск");
        Filial filial = filial(20L, company(10L), city);
        Bot walked = bot(101L, "Иван Петров", 2);
        Bot needsWalk = bot(102L, "Петр Иванов", 1);
        Review review = new Review();
        review.setId(50L);
        review.setFilial(filial);
        review.setVigul(false);

        when(botService.getFindAllByFilialCityId(5L)).thenReturn(List.of(walked, needsWalk));
        when(filialService.findByCityId(5L)).thenReturn(List.of(filial));
        when(botCooldownService.isAvailableForAssignment(any())).thenReturn(true);
        when(accountWalkScheduleService.isEligibleForNagul(walked)).thenReturn(false);
        when(accountWalkScheduleService.isEligibleForNagul(needsWalk)).thenReturn(true);

        Bot assigned = service.assignBotForReviewChange(
                review,
                Set.of(),
                ReviewBotAssignmentMode.NAGUL_ONLY
        );

        assertSame(needsWalk, assigned);
    }

    @Test
    void publicationFallsBackToUnwalkedAfterRejectedWalkedAccountsAreExhausted() {
        BotAssignmentServiceImpl service = service();
        City city = city(5L, "Иркутск");
        Filial filial = filial(20L, company(10L), city);
        Bot rejectedWalked = bot(101L, "Иван Петров", 2);
        Bot needsWalk = bot(102L, "Петр Иванов", 1);
        Review review = new Review();
        review.setId(51L);
        review.setFilial(filial);
        review.setVigul(true);

        when(botService.getFindAllByFilialCityId(5L)).thenReturn(List.of(rejectedWalked, needsWalk));
        when(filialService.findByCityId(5L)).thenReturn(List.of(filial));
        when(botCooldownService.isAvailableForAssignment(any())).thenReturn(true);
        when(accountWalkScheduleService.isEligibleForNagul(needsWalk)).thenReturn(true);

        Bot assigned = service.assignBotForReviewChange(
                review,
                Set.of(101L),
                ReviewBotAssignmentMode.PUBLISH_PREFER_WALKED
        );

        assertSame(needsWalk, assigned);
    }

    @Test
    void publicationClaimsNewAccountAfterAllCityCandidatesAreRejected() {
        BotAssignmentServiceImpl service = service();
        City city = city(5L, "Иркутск");
        Filial filial = filial(20L, company(10L), city);
        Bot freshAccount = bot(900L, "Впиши Имя Фамилию", 0);
        Review review = new Review();
        review.setId(52L);
        review.setFilial(filial);
        review.setVigul(true);

        when(botService.getFindAllByFilialCityId(5L)).thenReturn(List.of());
        when(filialService.findByCityId(5L)).thenReturn(List.of(filial));
        when(botService.claimNewAccountForCity(eq(city), anyCollection()))
                .thenReturn(Optional.of(freshAccount));
        when(accountWalkScheduleService.isEligibleForNagul(freshAccount)).thenReturn(true);

        Bot assigned = service.assignBotForReviewChange(
                review,
                Set.of(101L, 102L),
                ReviewBotAssignmentMode.PUBLISH_PREFER_WALKED
        );

        assertSame(freshAccount, assigned);
        verify(botService).claimNewAccountForCity(eq(city), anyCollection());
    }

    @Test
    @SuppressWarnings("unchecked")
    void checkAndNotifyAboutStubBotsReplacesStubFromReserveBeforeAlert() {
        BotAssignmentServiceImpl service = service();
        City city = city(5L, "Иркутск");
        Filial filial = filial(20L, company(10L), city);
        Bot existingBot = bot(101L, "Аккаунт", 0);
        Bot stubBot = bot(1L, "Нет доступных аккаунтов", 0);
        Bot reserveBot = bot(900L, "Впиши Имя Фамилию", 0);

        Review existingReview = new Review();
        existingReview.setId(1L);
        existingReview.setFilial(filial);
        existingReview.setBot(existingBot);

        Review stubReview = new Review();
        stubReview.setId(2L);
        stubReview.setFilial(filial);
        stubReview.setBot(stubBot);
        stubReview.setVigul(true);

        when(botService.claimReserveBotForCity(eq(city), anyCollection()))
                .thenReturn(Optional.of(reserveBot));
        when(assignmentGuardService.blockedBotIds(any())).thenReturn(Set.of(777L, 888L));
        service.checkAndNotifyAboutStubBots(List.of(existingReview, stubReview));

        ArgumentCaptor<Collection<Long>> excludedIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(botService).claimReserveBotForCity(eq(city), excludedIdsCaptor.capture());
        assertTrue(excludedIdsCaptor.getValue().contains(101L));
        assertTrue(excludedIdsCaptor.getValue().containsAll(Set.of(777L, 888L)));
        assertSame(reserveBot, stubReview.getBot());
        assertEquals(false, stubReview.isVigul());
        verify(accountWalkScheduleService).synchronizeAfterAccountChange(stubReview);
        verify(reviewRepository).saveAll(List.of(stubReview));
        verify(telegramService, never()).sendAlertToAdmins(anyString());
    }

    @Test
    void checkAndNotifyAboutStubBotsAlwaysChecksWalkDelayWhenReserveAssigned() {
        BotAssignmentServiceImpl service = service();
        City city = city(5L, "Иркутск");
        Filial filial = filial(20L, company(10L), city);
        Bot stubBot = bot(1L, "Нет доступных аккаунтов", 0);
        Bot reserveBot = bot(900L, "Впиши Имя Фамилию", 0);

        Review stubReview = new Review();
        stubReview.setId(2L);
        stubReview.setFilial(filial);
        stubReview.setBot(stubBot);

        when(botService.claimReserveBotForCity(eq(city), anyCollection()))
                .thenReturn(Optional.of(reserveBot));

        service.checkAndNotifyAboutStubBots(List.of(stubReview), true);

        assertSame(reserveBot, stubReview.getBot());
        verify(accountWalkScheduleService).synchronizeAfterAccountChange(stubReview);
        verify(reviewRepository).saveAll(List.of(stubReview));
    }

    @Test
    void assignBotsToExistingReviewsAlwaysChecksWalkDelay() {
        BotAssignmentServiceImpl service = service();
        City city = city(5L, "Иркутск");
        Filial filial = filial(20L, company(10L), city);
        Bot candidate = bot(900L, "Впиши Имя Фамилию", 0);
        Review review = new Review();
        review.setId(2L);
        review.setFilial(filial);

        when(botService.getFindAllByFilialCityId(5L)).thenReturn(List.of(candidate));
        when(filialService.findByCityId(5L)).thenReturn(List.of(filial));
        when(botCooldownService.isAvailableForAssignment(candidate)).thenReturn(true);

        assertTrue(service.assignBotsToExistingReviews(List.of(review), filial, true));

        assertSame(candidate, review.getBot());
        verify(accountWalkScheduleService).synchronizeAfterAccountChange(review);
        verify(reviewRepository).saveAll(List.of(review));
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

        when(filialService.getFilial(20L)).thenReturn(filialA);
        when(filialService.getFilial(21L)).thenReturn(filialB);
        when(botService.getFindAllByFilialCityId(5L)).thenReturn(List.of(botA));
        when(botService.getFindAllByFilialCityId(6L)).thenReturn(List.of(botB));
        when(filialService.findByCityId(5L)).thenReturn(List.of(filialA));
        when(filialService.findByCityId(6L)).thenReturn(List.of(filialB));
        when(botCooldownService.isAvailableForAssignment(any())).thenReturn(true);

        List<Review> reviews = service.assignBotsToNewReviews(orderDTO, details);

        assertEquals(2, reviews.size());
        assertSame(filialA, reviews.get(0).getFilial());
        assertSame(botA, reviews.get(0).getBot());
        assertSame(filialB, reviews.get(1).getFilial());
        assertSame(botB, reviews.get(1).getBot());
    }

    private BotAssignmentServiceImpl service() {
        return new BotAssignmentServiceImpl(
                botService,
                filialService,
                companyRepository,
                reviewRepository,
                telegramService,
                botCooldownService,
                accountWalkScheduleService,
                assignmentGuardService,
                businessAuditService
        );
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

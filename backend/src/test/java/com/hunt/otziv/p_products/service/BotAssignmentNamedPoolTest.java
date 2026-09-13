package com.hunt.otziv.p_products.service;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.service.BotService;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.c_companies.service.FilialService;
import com.hunt.otziv.r_review.bot.model.ReviewBotAssignmentMode;
import com.hunt.otziv.r_review.bot.service.ReviewAccountWalkScheduleService;
import com.hunt.otziv.r_review.bot.service.ReviewBotAssignmentGuardService;
import com.hunt.otziv.r_review.bot.service.ReviewBotCooldownService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.utils.ReviewBotPolicy;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BotAssignmentNamedPoolTest {
    @Mock private BotService botService;
    @Mock private FilialService filialService;
    @Mock private CompanyRepository companyRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private TelegramService telegramService;
    @Mock private ReviewBotCooldownService botCooldownService;
    @Mock private ReviewAccountWalkScheduleService accountWalkScheduleService;
    @Mock private ReviewBotAssignmentGuardService assignmentGuardService;
    @Mock private BusinessAuditService businessAuditService;
    @Mock private EntityManager entityManager;
    @InjectMocks private BotAssignmentServiceImpl service;

    private final City targetCity = City.builder().id(5L).title("Иркутск").build();
    private final Company company = Company.builder().id(10L).build();
    private final Filial filial = Filial.builder().id(20L).city(targetCity).company(company).build();
    private Bot stub;

    @BeforeEach
    void setUp() {
        stub = poolBot(1L, 0);
        stub.setActive(false);
        lenient().when(botService.findBotById(1L)).thenReturn(stub);
        lenient().when(botService.getFindAllByFilialCityId(anyLong())).thenReturn(List.of());
        lenient().when(companyRepository.findByIdForBotAssignmentLock(10L)).thenReturn(Optional.of(company));
        lenient().when(filialService.findByCityId(anyLong())).thenReturn(List.of(filial));
        lenient().when(assignmentGuardService.scope(anyLong(), any()))
                .thenAnswer(call -> new ReviewBotAssignmentGuardService.AssignmentScope(
                        call.getArgument(0), call.getArgument(1), null, null));
        lenient().when(assignmentGuardService.blockedBotIds(any())).thenReturn(Set.of());
        lenient().when(assignmentGuardService.lockIfEligible(any(), any()))
                .thenAnswer(call -> Optional.ofNullable(call.getArgument(0, Bot.class)));
        lenient().when(botService.save(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(botCooldownService.isAvailableForAssignment(any())).thenAnswer(call -> {
            Bot bot = call.getArgument(0);
            return bot.getCooldownUntil() == null || !bot.getCooldownUntil().isAfter(LocalDate.now());
        });
        lenient().when(accountWalkScheduleService.isEligibleForNagul(any()))
                .thenAnswer(call -> ReviewBotPolicy.isEligibleForNagul(call.getArgument(0), 2));
        lenient().when(accountWalkScheduleService.isWalkedAccount(any()))
                .thenAnswer(call -> ReviewBotPolicy.isWalkedAccount(call.getArgument(0), 2));
    }

    @ParameterizedTest
    @CsvSource({"NAGUL_ONLY,0", "NAGUL_ONLY,1", "PUBLISH_PREFER_WALKED,2", "PUBLISH_PREFER_WALKED,7"})
    void claimsNamedAccountOnlyAfterFreshPoolIsEmptyAndPreservesItsData(ReviewBotAssignmentMode mode, int counter) {
        Bot candidate = poolBot(900L, counter);
        when(botService.getFindAllByFilialCityId(325L)).thenReturn(List.of(candidate));

        Bot result = service.assignBotForReviewChange(review(mode), Set.of());

        assertSame(candidate, result);
        assertSame(targetCity, result.getBotCity());
        assertEquals("Иван Петров", result.getFio());
        assertEquals(counter, result.getCounter());
        assertTrue(result.isActive());
        assertEquals("account-900", result.getLogin());
        assertEquals("test-password", result.getPassword());
        var order = inOrder(botService, assignmentGuardService, entityManager);
        order.verify(botService).claimNewAccountForCity(eq(targetCity), anyCollection());
        order.verify(botService).getFindAllByFilialCityId(325L);
        order.verify(assignmentGuardService).lockIfEligible(eq(candidate), any());
        order.verify(entityManager).refresh(candidate, LockModeType.PESSIMISTIC_WRITE);
        order.verify(botService).save(candidate);
    }

    @ParameterizedTest
    @CsvSource({"NAGUL_ONLY,-1", "NAGUL_ONLY,2", "NAGUL_ONLY,7",
            "PUBLISH_PREFER_WALKED,-1", "PUBLISH_PREFER_WALKED,0", "PUBLISH_PREFER_WALKED,1"})
    void rejectsCounterOutsideRequestedMode(ReviewBotAssignmentMode mode, int counter) {
        Bot candidate = poolBot(900L, counter);
        when(botService.getFindAllByFilialCityId(325L)).thenReturn(List.of(candidate));

        assertSame(stub, service.assignBotForReviewChange(review(mode), Set.of()));
        assertEquals(325L, candidate.getBotCity().getId());
        verify(botService, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"template", "template_variant", "blank_name", "null_name", "inactive",
            "missing_login", "missing_password", "wrong_status", "missing_status", "other_city", "vk", "flamp", "cooldown"})
    void rejectsInvalidOrUnavailablePoolAccounts(String reason) {
        Bot candidate = poolBot(900L, 2);
        invalidate(candidate, reason);
        when(botService.getFindAllByFilialCityId(325L)).thenReturn(List.of(candidate));

        assertSame(stub, service.assignBotForReviewChange(review(ReviewBotAssignmentMode.PUBLISH_PREFER_WALKED), Set.of()));
        verify(assignmentGuardService, never()).lockIfEligible(eq(candidate), any());
        verify(botService, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"other_city", "counter", "template", "inactive", "wrong_status", "cooldown", "missing_password"})
    void rechecksFreshDatabaseStateAfterAcquiringTheAccountLock(String concurrentChange) {
        Bot candidate = poolBot(900L, 2);
        when(botService.getFindAllByFilialCityId(325L)).thenReturn(List.of(candidate));
        doAnswer(call -> { invalidate(candidate, concurrentChange); return null; })
                .when(entityManager).refresh(candidate, LockModeType.PESSIMISTIC_WRITE);

        assertSame(stub, service.assignBotForReviewChange(review(ReviewBotAssignmentMode.PUBLISH_PREFER_WALKED), Set.of()));
        verify(entityManager).refresh(candidate, LockModeType.PESSIMISTIC_WRITE);
        verify(botService, never()).save(any());
        assertNotEquals(targetCity.getId(), candidate.getBotCity().getId());
    }

    @Test
    void rejectsAccountThatTheSharedCompanyOrCurrentUsageGuardRejects() {
        Bot candidate = poolBot(900L, 2);
        when(botService.getFindAllByFilialCityId(325L)).thenReturn(List.of(candidate));
        when(assignmentGuardService.lockIfEligible(eq(candidate), any())).thenReturn(Optional.empty());

        assertSame(stub, service.assignBotForReviewChange(review(ReviewBotAssignmentMode.PUBLISH_PREFER_WALKED), Set.of()));
        verify(botService, never()).save(any());
        verify(entityManager, never()).refresh(any(), any(LockModeType.class));
        assertEquals(325L, candidate.getBotCity().getId());
    }

    @Test
    void continuesToAnotherPoolAccountAfterTheFirstCandidateLosesTheAssignmentRace() {
        Bot first = poolBot(900L, 2);
        Bot second = poolBot(901L, 3);
        when(botService.getFindAllByFilialCityId(325L)).thenReturn(List.of(first, second));
        when(assignmentGuardService.lockIfEligible(any(), any()))
                .thenReturn(Optional.empty())
                .thenAnswer(call -> Optional.of(call.getArgument(0, Bot.class)));

        Bot assigned = service.assignBotForReviewChange(review(ReviewBotAssignmentMode.PUBLISH_PREFER_WALKED), Set.of());

        var attempted = ArgumentCaptor.forClass(Bot.class);
        verify(assignmentGuardService, times(2)).lockIfEligible(attempted.capture(), any());
        assertEquals(325L, attempted.getAllValues().getFirst().getBotCity().getId());
        assertSame(attempted.getAllValues().getLast(), assigned);
        assertSame(targetCity, assigned.getBotCity());
        verify(botService, times(1)).save(assigned);
    }

    @Test
    void respectsPreviouslyRejectedAndCompanyExcludedAccountIds() {
        Bot rejected = poolBot(900L, 2);
        Bot companyUsed = poolBot(901L, 2);
        when(botService.getFindAllByFilialCityId(325L)).thenReturn(List.of(rejected, companyUsed));
        when(assignmentGuardService.blockedBotIds(any())).thenReturn(Set.of(901L));

        assertSame(stub, service.assignBotForReviewChange(review(ReviewBotAssignmentMode.PUBLISH_PREFER_WALKED), Set.of(900L)));
        verify(assignmentGuardService, never()).lockIfEligible(any(), any());
        verify(botService, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = ReviewBotAssignmentMode.class, names = {"NAGUL_ONLY", "PUBLISH_PREFER_WALKED"})
    void keepsFreshTemplatePoolPriority(ReviewBotAssignmentMode mode) {
        Bot fresh = poolBot(900L, 0);
        fresh.setFio("Впиши Имя Фамилию");
        fresh.setBotCity(targetCity);
        when(botService.claimNewAccountForCity(eq(targetCity), anyCollection())).thenReturn(Optional.of(fresh));

        assertSame(fresh, service.assignBotForReviewChange(review(mode), Set.of()));
        verify(botService, never()).getFindAllByFilialCityId(325L);
        verify(entityManager, never()).refresh(any(), any(LockModeType.class));
    }

    @Test
    void keepsSameCityAccountPriority() {
        Bot local = poolBot(900L, 2);
        local.setBotCity(targetCity);
        when(botService.getFindAllByFilialCityId(5L)).thenReturn(List.of(local));

        assertSame(local, service.assignBotForReviewChange(review(ReviewBotAssignmentMode.PUBLISH_PREFER_WALKED), Set.of()));
        verify(botService, never()).claimNewAccountForCity(any(), anyCollection());
        verify(botService, never()).getFindAllByFilialCityId(325L);
    }

    @ParameterizedTest
    @CsvSource({"320,NAGUL_ONLY", "320,PUBLISH_PREFER_WALKED", "326,NAGUL_ONLY", "326,PUBLISH_PREFER_WALKED"})
    void neverImportsNamedPoolAccountsIntoVkOrFlamp(long cityId, ReviewBotAssignmentMode mode) {
        targetCity.setId(cityId);

        assertSame(stub, service.assignBotForReviewChange(review(mode), Set.of()));
        verify(botService).claimNewAccountFromOwnCity(eq(targetCity), anyCollection());
        verify(botService, never()).getFindAllByFilialCityId(325L);
        verify(botService, never()).save(any());
    }

    @Test
    void doesNotChangeDefaultNewOrderAssignment() {
        assertSame(stub, service.assignBotForReviewChange(review(ReviewBotAssignmentMode.NAGUL_ONLY), Set.of(),
                ReviewBotAssignmentMode.DEFAULT_ORDER_ASSIGNMENT));
        verify(botService).claimReserveBotForCity(eq(targetCity), anyCollection());
        verify(botService, never()).getFindAllByFilialCityId(325L);
    }

    private Review review(ReviewBotAssignmentMode mode) {
        return Review.builder().id(50L).filial(filial)
                .vigul(mode == ReviewBotAssignmentMode.PUBLISH_PREFER_WALKED).build();
    }

    private Bot poolBot(long id, int counter) {
        return Bot.builder().id(id).botCity(City.builder().id(325L).title("Шаблон").build())
                .fio("Иван Петров").counter(counter).active(true)
                .login("account-" + id).password("test-password")
                .status(StatusBot.builder().id(1L).botStatusTitle("Новый").build()).build();
    }

    private void invalidate(Bot bot, String reason) {
        switch (reason) {
            case "template" -> bot.setFio("Впиши Имя Фамилию");
            case "template_variant" -> bot.setFio("  ВПИШИТЕ ФАМИЛИЮ ИМЯ  ");
            case "blank_name" -> bot.setFio("  ");
            case "null_name" -> bot.setFio(null);
            case "inactive" -> bot.setActive(false);
            case "missing_login" -> bot.setLogin(" ");
            case "missing_password" -> bot.setPassword(null);
            case "wrong_status" -> bot.getStatus().setBotStatusTitle("Средний");
            case "missing_status" -> bot.setStatus(null);
            case "other_city" -> bot.setBotCity(City.builder().id(6L).build());
            case "vk" -> bot.setBotCity(City.builder().id(320L).build());
            case "flamp" -> bot.setBotCity(City.builder().id(326L).build());
            case "cooldown" -> bot.setCooldownUntil(LocalDate.of(9999, 12, 31));
            case "counter" -> bot.setCounter(1);
            default -> throw new IllegalArgumentException(reason);
        }
    }
}

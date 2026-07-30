package com.hunt.otziv.r_review.bot.service;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.config.email.EmailService;
import com.hunt.otziv.p_products.services.service.BotAssignmentService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewBotChangeService {

    private static final Long STUB_BOT_ID = 1L;
    private static final Set<Long> OWN_CITY_NEW_ACCOUNT_CITY_IDS = Set.of(320L, 326L);
    private static final Set<String> TEMPLATE_BOT_NAMES = Set.of(
            "Впишите Имя Фамилию",
            "Впиши Имя Фамилию",
            "Впишите Фамилию Имя"
    );

    private final ReviewRepository reviewRepository;
    private final BotService botService;
    private final EmailService emailService;
    private final CompanyRepository companyRepository;
    private final BotAssignmentService botAssignmentService;
    private final FilialService filialService;
    private final ReviewAccountWalkScheduleService accountWalkScheduleService;
    private final ReviewBotCooldownService botCooldownService;
    private final ReviewBotAssignmentGuardService assignmentGuardService;
    private final BusinessAuditService businessAuditService;
    private final ReviewBotAssignmentExclusionService assignmentExclusionService;
    private final WorkerAssignmentMutationGuardService assignmentMutationGuardService;

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void changeBot(Long reviewId) {
        assignmentMutationGuardService.assertReview(reviewId);
        try {
            log.info("1. Начинаем замену бота для отзыва ID {}", reviewId);
            Review review = getReviewToChangeBot(reviewId);

            if (review.getBot() == null) {
                log.warn("2. Для отзыва ID {} не удалось установить бота (список доступных пуст)", reviewId);
            } else if (Objects.equals(review.getBot().getId(), STUB_BOT_ID)) {
                log.warn("2. Для отзыва ID {} установлен бот-заглушка (нет доступных ботов)", reviewId);
            } else {
                log.info("2. Установлен новый рандомный бот для отзыва ID {}", reviewId);
            }

            reviewRepository.save(review);
            log.info("3. Сохранили отзыв в БД");

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Ошибка при замене бота для отзыва ID {}: {}", reviewId, e.getMessage(), e);
            throw new RuntimeException("Не удалось заменить бота: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void deActivateAndChangeBot(Long reviewId, Long botId) {
        assignmentMutationGuardService.assertReview(reviewId);
        try {
            Review review = findReviewForBotChange(reviewId).orElse(null);
            if (review == null) {
                throw new RuntimeException("Отзыв не найден");
            }

            boolean wasVigul = review.isVigul();
            Bot currentBot = review.getBot();
            Long currentBotId = currentBot != null ? currentBot.getId() : null;

            if ((botId == null || botId == 0L) && currentBotId != null && currentBotId > 0) {
                botId = currentBotId;
                log.info("Используем ID реального бота отзыва: {}", botId);
            }

            notifyIfCityHasFewBots(review);

            if (botId != null && !Objects.equals(botId, STUB_BOT_ID) && botId > 0) {
                botActiveToFalse(botId);
            }

            Set<Long> excludedBotIds = new HashSet<>();
            if (botId != null && botId > 0) {
                excludedBotIds.add(botId);
            }
            assignmentExclusionService.rejectCurrentBot(review, "BLOCK");
            excludedBotIds.addAll(assignmentExclusions(review));
            assignBotUsingSharedRules(review, excludedBotIds);
            markReleasedIfChanged(currentBot, review.getBot(), "review bot blocked and changed");
            accountWalkScheduleService.synchronizeAfterAccountChange(review);
            addAssignedBotToExcluded(review, excludedBotIds);

            log.info("Vigul обновлен: {} -> {}", wasVigul, review.isVigul());
            reviewRepository.save(review);
            reassignUnpublishedReviewsForBlockedBot(botId, review.getId(), excludedBotIds);

        } catch (Exception e) {
            log.error("Что-то пошло не так и бот не деактивирован", e);
            throw new RuntimeException("Ошибка при деактивации и смене бота: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void assignNewAccount(Long reviewId) {
        assignmentMutationGuardService.assertReview(reviewId);
        Review review = findReviewForBotChange(reviewId)
                .orElseThrow(() -> new RuntimeException("Отзыв не найден"));

        Filial filial = review.getFilial();
        lockCompanyForBotAssignment(filial);
        City city = filial != null ? filial.getCity() : null;
        Long cityId = city != null ? city.getId() : null;

        if (cityId == null) {
            throw new RuntimeException("Город филиала не найден");
        }

        Set<Long> excludedBotIds = getUsedBotIdsInCompany(filial, review.getId());
        excludedBotIds.addAll(getReservedBotIdsByUnpublishedReviews(review.getId()));
        assignmentExclusionService.rejectCurrentBot(review, "NEW_ACCOUNT");
        excludedBotIds.addAll(assignmentExclusions(review));
        if (review.getBot() != null && review.getBot().getId() != null) {
            excludedBotIds.add(review.getBot().getId());
        }

        Bot selectedBot = claimNewAccount(city, cityId, excludedBotIds);
        selectedBot = assignmentGuardService.lockIfEligible(
                        selectedBot,
                        assignmentGuardService.scope(filial.getCompany().getId(), review.getId())
                )
                .orElseThrow(() -> new RuntimeException(
                        "Выбранный аккаунт уже использовался компанией или занят другой карточкой"
                ));

        Bot oldBot = review.getBot();
        review.setBot(selectedBot);
        markReleasedIfChanged(oldBot, selectedBot, "new account assigned to review");
        accountWalkScheduleService.synchronizeAfterAccountChange(review);
        reviewRepository.save(review);

        log.info("Новый аккаунт ID {} назначен отзыву ID {} для города филиала {}",
                selectedBot.getId(), reviewId, cityId);
    }

    private Bot claimNewAccount(City city, Long cityId, Set<Long> excludedBotIds) {
        if (OWN_CITY_NEW_ACCOUNT_CITY_IDS.contains(cityId)) {
            return botService.claimNewAccountFromOwnCity(city, excludedBotIds)
                    .orElseThrow(() -> new RuntimeException(
                            "Нет доступных чистых аккаунтов \"Впиши Имя Фамилию\" в городе " + cityId));
        }

        return botService.claimNewAccountForCity(city, excludedBotIds)
                .orElseThrow(() -> new RuntimeException("Нет доступных аккаунтов \"Впиши Имя Фамилию\" в городе 325"));
    }

    public List<Bot> findAllBotsMinusFilial(Review review) {
        if (review == null) {
            return Collections.emptyList();
        }

        Filial filial = review.getFilial();
        if (filial == null) {
            return Collections.emptyList();
        }

        City city = filial.getCity();
        if (city == null || city.getId() == null) {
            return Collections.emptyList();
        }

        List<Bot> allBots;
        try {
            allBots = botService.getFindAllByFilialCityId(city.getId());
        } catch (Exception e) {
            log.error("Ошибка при получении ботов по ID города: {}", city.getId(), e);
            return Collections.emptyList();
        }

        if (allBots == null || allBots.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> usedBotIdsInCompany = getUsedBotIdsInCompany(filial, review.getId());
        Set<Long> usedBotIdsGlobally = getUsedBotIdsGlobally(filial, review.getId());
        Set<Long> reservedBotIds = getReservedBotIdsByUnpublishedReviews(review.getId());

        boolean vigul = review.isVigul();

        List<Bot> idealBots = allBots.stream()
                .filter(Objects::nonNull)
                .filter(bot -> bot.getId() != null)
                .filter(Bot::isActive)
                .filter(bot -> !usedBotIdsInCompany.contains(bot.getId()))
                .filter(bot -> !usedBotIdsGlobally.contains(bot.getId()))
                .filter(bot -> !reservedBotIds.contains(bot.getId()))
                .filter(this::hasNewStatus)
                .collect(Collectors.toList());

        if (!idealBots.isEmpty()) {
            List<Bot> filteredBots = applyVigulFilters(idealBots, vigul);
            if (!filteredBots.isEmpty()) {
                return filteredBots;
            }
        }

        List<Bot> fallbackBots = allBots.stream()
                .filter(Objects::nonNull)
                .filter(bot -> bot.getId() != null)
                .filter(Bot::isActive)
                .filter(bot -> !usedBotIdsInCompany.contains(bot.getId()))
                .filter(bot -> !reservedBotIds.contains(bot.getId()))
                .filter(this::hasNewStatus)
                .collect(Collectors.toList());

        if (!fallbackBots.isEmpty()) {
            List<Bot> filteredBots = applyVigulFilters(fallbackBots, vigul);
            if (!filteredBots.isEmpty()) {
                return filteredBots;
            }
        }

        return Collections.emptyList();
    }

    private Review getReviewToChangeBot(Long reviewId) {
        Review review = findReviewForBotChange(reviewId)
                .orElseThrow(() -> new RuntimeException("Отзыв не найден"));
        boolean wasVigul = review.isVigul();
        Bot oldBot = review.getBot();
        assignmentExclusionService.rejectCurrentBot(review, "CHANGE");
        Bot selectedBot = selectBotUsingSharedRules(review, assignmentExclusions(review));
        if (!hasRealBot(selectedBot)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Нет доступных аккаунтов");
        }
        applySelectedBot(review, selectedBot);
        markReleasedIfChanged(oldBot, review.getBot(), "review bot changed");
        accountWalkScheduleService.synchronizeAfterAccountChange(review);

        log.info("Vigul обновлен: {} -> {}", wasVigul, review.isVigul());
        return review;
    }

    private java.util.Optional<Review> findReviewForBotChange(Long reviewId) {
        java.util.Optional<Review> review = reviewRepository.findByIdForBotChange(reviewId);
        return review.isPresent() ? review : reviewRepository.findById(reviewId);
    }

    private Set<Long> getUsedBotIdsInCompany(Filial filial, Long currentReviewId) {
        if (filial == null || filial.getCompany() == null || filial.getCompany().getId() == null) {
            throw new IllegalStateException("Невозможно проверить аккаунт: у филиала не указана компания");
        }
        return new HashSet<>(assignmentGuardService.blockedBotIds(
                assignmentGuardService.scope(filial.getCompany().getId(), currentReviewId)
        ));
    }

    private void reassignUnpublishedReviewsForBlockedBot(
            Long blockedBotId,
            Long currentReviewId,
            Set<Long> excludedBotIds
    ) {
        if (blockedBotId == null || blockedBotId <= 0 || STUB_BOT_ID.equals(blockedBotId)) {
            return;
        }

        List<Review> affectedReviews;
        try {
            affectedReviews = reviewRepository.findUnpublishedReviewsByBotIdForReassignment(blockedBotId, currentReviewId);
        } catch (Exception e) {
            log.error("Не удалось получить отзывы для каскадной замены бота {}", blockedBotId, e);
            return;
        }

        if (affectedReviews == null || affectedReviews.isEmpty()) {
            return;
        }

        int reassigned = 0;
        for (Review affectedReview : affectedReviews) {
            if (affectedReview == null) {
                continue;
            }

            Bot oldBot = affectedReview.getBot();
            assignmentExclusionService.reject(affectedReview.getId(), oldBot, "BLOCK_CASCADE");
            Set<Long> affectedExcludedBotIds = assignmentExclusions(affectedReview);
            affectedExcludedBotIds.addAll(excludedBotIds);
            assignBotUsingSharedRules(affectedReview, affectedExcludedBotIds);
            markReleasedIfChanged(oldBot, affectedReview.getBot(), "blocked bot reassigned in unpublished review");
            accountWalkScheduleService.synchronizeAfterAccountChange(affectedReview);
            addAssignedBotToExcluded(affectedReview, excludedBotIds);
            reassigned++;
        }

        reviewRepository.saveAll(affectedReviews);
        log.info("После блокировки бота {} выполнена каскадная замена в {} неопубликованных отзывах",
                blockedBotId, reassigned);
    }

    private void addAssignedBotToExcluded(Review review, Set<Long> excludedBotIds) {
        if (review == null || review.getBot() == null || review.getBot().getId() == null) {
            return;
        }

        Long botId = review.getBot().getId();
        if (!STUB_BOT_ID.equals(botId)) {
            excludedBotIds.add(botId);
        }
    }

    private void assignBotUsingSharedRules(Review review, Collection<Long> excludedBotIds) {
        Bot selectedBot = selectBotUsingSharedRules(review, excludedBotIds);
        applySelectedBot(review, selectedBot);
    }

    private Bot selectBotUsingSharedRules(Review review, Collection<Long> excludedBotIds) {
        return botAssignmentService.assignBotForReviewChange(review, excludedBotIds);
    }

    private void applySelectedBot(Review review, Bot selectedBot) {
        review.setBot(selectedBot);

        if (!hasRealBot(selectedBot)) {
            if (review.isVigul()) {
                review.setVigul(false);
            }
            return;
        }

        updateVigulBasedOnBotCounter(review);
    }

    private boolean hasRealBot(Bot bot) {
        return bot != null && bot.getId() != null && !STUB_BOT_ID.equals(bot.getId());
    }

    private Set<Long> assignmentExclusions(Review review) {
        Set<Long> excluded = assignmentExclusionService.excludedBotIds(review);
        return excluded == null ? new HashSet<>() : new HashSet<>(excluded);
    }

    private Set<Long> getReservedBotIdsByUnpublishedReviews(Long excludedReviewId) {
        try {
            Set<Long> botIds = reviewRepository.findReservedBotIdsByUnpublishedReviews(excludedReviewId);
            if (botIds == null) {
                return new HashSet<>();
            }

            return botIds.stream()
                    .filter(Objects::nonNull)
                    .filter(botId -> !STUB_BOT_ID.equals(botId))
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (Exception e) {
            log.error("Ошибка при получении занятых ботов по неопубликованным отзывам", e);
            throw new IllegalStateException("Не удалось проверить занятые аккаунты", e);
        }
    }

    private void notifyIfCityHasFewBots(Review review) {
        try {
            if (review.getFilial() != null && review.getFilial().getCity() != null) {
                List<Bot> cityBots = botService.getFindAllByFilialCityId(review.getFilial().getCity().getId());
                int botCount = cityBots != null ? cityBots.size() : 0;
                if (botCount < 50) {
                    String textMail = "Город: " + review.getFilial().getCity().getTitle() + ". Остаток у города: " + botCount;
                    emailService.sendSimpleEmail("o-company-server@mail.ru", "Мало аккаунтов у города", "Необходимо добавить аккаунты для: " + textMail);
                }
            }
        } catch (Exception e) {
            log.error("Сообщение о деактивации бота не отправилось", e);
        }
    }

    private boolean botActiveToFalse(Long botId) {
        try {
            if (botId == null || botId <= 0 || STUB_BOT_ID.equals(botId)) {
                return false;
            }

            Bot bot = botService.findBotById(botId);
            if (bot == null) {
                return false;
            }

            boolean oldActive = bot.isActive();
            bot.setActive(false);
            auditActiveChange(bot, oldActive, false, "review card block button");
            botService.save(bot);
            return true;

        } catch (Exception e) {
            log.error("3. Ошибка при деактивации бота {}: ", botId, e);
            return false;
        }
    }

    private Set<Long> getUsedBotIdsGlobally(Filial currentFilial, Long currentReviewId) {
        Set<Long> usedBotIds = new HashSet<>();

        try {
            City currentCity = currentFilial.getCity();
            if (currentCity == null || currentCity.getId() == null) {
                return usedBotIds;
            }

            List<Filial> filialsInSameCity = filialService.findByCityId(currentCity.getId());
            if (filialsInSameCity == null || filialsInSameCity.isEmpty()) {
                return usedBotIds;
            }

            List<Long> otherFilialIdsInCity = filialsInSameCity.stream()
                    .filter(filial -> filial != null && filial.getId() != null)
                    .filter(filial -> !filial.getId().equals(currentFilial.getId()))
                    .map(Filial::getId)
                    .collect(Collectors.toList());

            if (otherFilialIdsInCity.isEmpty()) {
                return usedBotIds;
            }

            Set<Long> activeBotIdsInSameCity = reviewRepository
                    .findActiveBotIdsByUnpublishedReviewsInFilials(otherFilialIdsInCity, currentReviewId);

            if (activeBotIdsInSameCity != null) {
                activeBotIdsInSameCity.stream()
                        .filter(Objects::nonNull)
                        .forEach(usedBotIds::add);
            }

        } catch (Exception e) {
            log.error("Ошибка при получении глобально использованных ботов", e);
        }

        return usedBotIds;
    }

    private List<Bot> applyVigulFilters(List<Bot> baseBots, boolean vigul) {
        if (!vigul) {
            List<Bot> strictFiltered = baseBots.stream()
                    .filter(this::isTemplateBotName)
                    .collect(Collectors.toList());

            if (!strictFiltered.isEmpty()) {
                return strictFiltered;
            }

            return baseBots;
        }

        List<Bot> strictFiltered = baseBots.stream()
                .filter(accountWalkScheduleService::isWalkedAccount)
                .collect(Collectors.toList());

        if (!strictFiltered.isEmpty()) {
            return strictFiltered;
        }

        List<Bot> fallbackFiltered = baseBots.stream()
                .filter(bot -> !accountWalkScheduleService.isWalkedAccount(bot))
                .collect(Collectors.toList());

        if (!fallbackFiltered.isEmpty()) {
            return fallbackFiltered;
        }

        return baseBots;
    }

    private void updateVigulBasedOnBotCounter(Review review) {
        if (review == null || review.getBot() == null) {
            return;
        }

        Bot bot = review.getBot();

        if (STUB_BOT_ID.equals(bot.getId())) {
            return;
        }

        review.setVigul(accountWalkScheduleService.isWalkedAccount(bot));
    }

    private boolean hasNewStatus(Bot bot) {
        if (bot.getStatus() == null) {
            return false;
        }
        String statusTitle = bot.getStatus().getBotStatusTitle();
        return statusTitle != null && "Новый".equals(statusTitle.trim());
    }

    private boolean isTemplateBotName(Bot bot) {
        return bot != null && bot.getFio() != null && TEMPLATE_BOT_NAMES.contains(bot.getFio().trim());
    }

    private void lockCompanyForBotAssignment(Filial filial) {
        Long companyId = filial != null && filial.getCompany() != null ? filial.getCompany().getId() : null;
        if (companyId == null) {
            return;
        }

        companyRepository.findByIdForBotAssignmentLock(companyId)
                .orElseThrow(() -> new RuntimeException("Компания для подбора аккаунта не найдена: " + companyId));
    }

    private void markReleasedIfChanged(Bot oldBot, Bot newBot, String reason) {
        Long oldBotId = oldBot != null ? oldBot.getId() : null;
        Long newBotId = newBot != null ? newBot.getId() : null;
        if (oldBotId != null && !Objects.equals(oldBotId, newBotId)) {
            botCooldownService.markReleased(oldBot, reason);
        }
    }

    private void auditActiveChange(Bot bot, boolean oldActive, boolean newActive, String details) {
        if (oldActive == newActive || bot == null || bot.getId() == null) {
            return;
        }

        businessAuditService.recordSafely(
                "bot_active_changed",
                "bot",
                bot.getId(),
                null,
                null,
                oldActive,
                newActive,
                details
        );
    }
}

package com.hunt.otziv.r_review.bot;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.config.email.EmailService;
import com.hunt.otziv.p_products.services.service.BotAssignmentService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    private static final int MAX_ACTIVE_REVIEWS_PER_BOT = 3;
    private static final Set<String> TEMPLATE_BOT_NAMES = Set.of(
            "Впишите Имя Фамилию",
            "Впиши Имя Фамилию",
            "Впишите Фамилию Имя"
    );

    private final ReviewRepository reviewRepository;
    private final BotService botService;
    private final EmailService emailService;
    private final BotAssignmentService botAssignmentService;
    private final FilialService filialService;

    public void changeBot(Long reviewId) {
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

        } catch (Exception e) {
            log.error("Ошибка при замене бота для отзыва ID {}: {}", reviewId, e.getMessage(), e);
            throw new RuntimeException("Не удалось заменить бота: " + e.getMessage(), e);
        }
    }

    public void deActivateAndChangeBot(Long reviewId, Long botId) {
        try {
            Review review = reviewRepository.findById(reviewId).orElse(null);
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

            Set<Long> excludedBotIds = botId != null && botId > 0 ? Set.of(botId) : Set.of();
            assignBotUsingSharedRules(review, excludedBotIds);

            log.info("Vigul обновлен: {} -> {}", wasVigul, review.isVigul());
            reviewRepository.save(review);

        } catch (Exception e) {
            log.error("Что-то пошло не так и бот не деактивирован", e);
            throw new RuntimeException("Ошибка при деактивации и смене бота: " + e.getMessage(), e);
        }
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

        Set<Long> usedBotIdsInThisFilial = getUsedBotIdsInFilial(filial, review.getId());
        Set<Long> usedBotIdsGlobally = getUsedBotIdsGlobally(filial, review.getId());

        boolean vigul = review.isVigul();

        List<Bot> idealBots = allBots.stream()
                .filter(Objects::nonNull)
                .filter(bot -> bot.getId() != null)
                .filter(bot -> !usedBotIdsInThisFilial.contains(bot.getId()))
                .filter(bot -> !usedBotIdsGlobally.contains(bot.getId()))
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
                .filter(bot -> !usedBotIdsInThisFilial.contains(bot.getId()))
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
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new RuntimeException("Отзыв не найден"));
        boolean wasVigul = review.isVigul();

        assignBotUsingSharedRules(review, Set.of());

        log.info("Vigul обновлен: {} -> {}", wasVigul, review.isVigul());
        return review;
    }

    private void assignBotUsingSharedRules(Review review, Collection<Long> excludedBotIds) {
        Bot selectedBot = botAssignmentService.assignBotForReviewChange(review, excludedBotIds);
        review.setBot(selectedBot);

        if (selectedBot == null || STUB_BOT_ID.equals(selectedBot.getId())) {
            if (review.isVigul()) {
                review.setVigul(false);
            }
            return;
        }

        updateVigulBasedOnBotCounter(review);
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

            bot.setActive(false);
            botService.save(bot);
            return true;

        } catch (Exception e) {
            log.error("3. Ошибка при деактивации бота {}: ", botId, e);
            return false;
        }
    }

    private Set<Long> getUsedBotIdsInFilial(Filial filial, Long currentReviewId) {
        Set<Long> usedBotIds = new HashSet<>();

        try {
            List<Review> allReviewsInFilial = reviewRepository.findAllByFilial(filial);

            if (allReviewsInFilial != null) {
                for (Review existingReview : allReviewsInFilial) {
                    if (existingReview != null
                            && existingReview.getId() != null
                            && !existingReview.getId().equals(currentReviewId)
                            && existingReview.getBot() != null
                            && existingReview.getBot().getId() != null) {
                        usedBotIds.add(existingReview.getBot().getId());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Ошибка при получении использованных ботов для филиала {}", filial.getId(), e);
        }

        return usedBotIds;
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

            List<Review> activeReviewsInSameCity = reviewRepository
                    .findByPublishFalseAndBotIsNotNullAndFilialIdIn(otherFilialIdsInCity);

            if (activeReviewsInSameCity != null) {
                for (Review existingReview : activeReviewsInSameCity) {
                    if (existingReview != null
                            && existingReview.getId() != null
                            && !existingReview.getId().equals(currentReviewId)
                            && existingReview.getBot() != null
                            && existingReview.getBot().getId() != null) {
                        usedBotIds.add(existingReview.getBot().getId());
                    }
                }
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
                .filter(bot -> safeBotCounter(bot) >= 3)
                .collect(Collectors.toList());

        if (!strictFiltered.isEmpty()) {
            return strictFiltered;
        }

        List<Bot> fallbackFiltered = baseBots.stream()
                .filter(bot -> {
                    int counter = safeBotCounter(bot);
                    return counter >= 0 && counter <= 2;
                })
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

        int botCounter = safeBotCounter(bot);
        boolean currentVigul = review.isVigul();

        if (currentVigul && botCounter < MAX_ACTIVE_REVIEWS_PER_BOT) {
            review.setVigul(false);
        } else if (!currentVigul && botCounter >= MAX_ACTIVE_REVIEWS_PER_BOT) {
            review.setVigul(true);
        }
    }

    private boolean hasNewStatus(Bot bot) {
        if (bot.getStatus() == null) {
            return false;
        }
        String statusTitle = bot.getStatus().getBotStatusTitle();
        return statusTitle != null && "Новый".equals(statusTitle.trim());
    }

    private int safeBotCounter(Bot bot) {
        return bot != null ? bot.getCounter() : 0;
    }

    private boolean isTemplateBotName(Bot bot) {
        return bot != null && bot.getFio() != null && TEMPLATE_BOT_NAMES.contains(bot.getFio().trim());
    }
}

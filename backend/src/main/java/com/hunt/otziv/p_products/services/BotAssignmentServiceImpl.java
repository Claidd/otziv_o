package com.hunt.otziv.p_products.services;




import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.services.service.BotAssignmentService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.services.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class BotAssignmentServiceImpl implements BotAssignmentService {

    private final BotService botService;
    private final ReviewService reviewService;
    private final FilialService filialService;
    private final ReviewRepository reviewRepository;

    private static final Long STUB_BOT_ID = 1L;
    private static final int MAX_ACTIVE_REVIEWS_PER_BOT = 3;

    @Override
    @Transactional
    public List<Review> assignBotsToNewReviews(OrderDTO orderDTO, OrderDetails orderDetails) {
        List<Review> reviewList = new ArrayList<>();

        // 1. Получаем филиал
        Filial filial = orderDetails.getOrder().getFilial();
        if (filial == null) {
            throw new IllegalArgumentException("Филиал не может быть null");
        }

        // 2. Получаем значение vigul (здесь нужно получить из orderDTO, если есть)
        boolean vigul = false; // TODO: получить из orderDTO, если есть
        int neededForOrder = orderDTO.getAmount();

        log.info("Назначение ботов для новых отзывов: vigul={}, требуется {} ботов", vigul, neededForOrder);

        // 3. Получаем доступных ботов по правилам
        List<Bot> availableBots = getAvailableBotsByRules(filial, vigul, neededForOrder);

        // 4. Создаем отзывы с УНИКАЛЬНЫМИ ботами
        Set<Long> usedBotIdsInThisOrder = new HashSet<>();

        for (int i = 0; i < neededForOrder; i++) {
            Bot assignedBot = findAndAssignUniqueBot(availableBots, usedBotIdsInThisOrder, i);

            // Создаем отзыв
            Review review = createReviewWithBot(orderDTO, orderDetails, filial, assignedBot, vigul);
            reviewList.add(review);
        }

        return reviewList;
    }

    @Override
    @Transactional
    public boolean assignBotsToExistingReviews(List<Review> reviews, Filial filial) {
        try {
            log.info("=== НАЧАЛО ПЕРЕНАЗНАЧЕНИЯ БОТОВ ДЛЯ СУЩЕСТВУЮЩИХ ОТЗЫВОВ ===");
            log.info("Филиал ID: {}, город: {}", filial.getId(), filial.getCity().getTitle());

            // 1. Фильтруем отзывы с null ботом
            List<Review> reviewsWithoutBots = reviews.stream()
                    .filter(review -> review.getBot() == null)
                    .collect(Collectors.toList());

            if (reviewsWithoutBots.isEmpty()) {
                log.warn("Нет отзывов с null ботом для переназначения");
                return false;
            }

            log.info("Найдено {} отзывов с null ботом", reviewsWithoutBots.size());

            // 2. Определяем vigul (берем из первого отзыва)
            boolean vigul = reviewsWithoutBots.stream()
                    .findFirst()
                    .map(Review::isVigul)
                    .orElse(false);

            int neededBots = reviewsWithoutBots.size();
            log.info("Требуется назначить {} ботов, vigul = {}", neededBots, vigul);

            // 3. Получаем доступных ботов по правилам
            List<Bot> availableBots = getAvailableBotsByRules(filial, vigul, neededBots);

            if (availableBots.isEmpty()) {
                log.error("Нет доступных ботов для назначения! Будет использована заглушка");

                // Назначаем всем отзывам бота-заглушку
                Bot stubBot = getStubBot();
                for (Review review : reviewsWithoutBots) {
                    review.setBot(stubBot);
                    log.warn("Отзыву ID {} назначен бот-заглушка", review.getId());
                }

                // Сохраняем отзывы
                reviewService.saveAll(reviewsWithoutBots);

                // Отправляем уведомление
                sendStubBotAlert(neededBots, neededBots);

                return false;
            }

            // 4. Назначаем ботов отзывам
            Set<Long> usedBotIdsInThisOrder = new HashSet<>();
            int assignedCount = 0;

            for (Review review : reviewsWithoutBots) {
                Bot assignedBot = findAndAssignUniqueBot(availableBots, usedBotIdsInThisOrder, assignedCount);

                // Назначаем бота отзыву
                review.setBot(assignedBot);

                // Обновляем isVigul на основе counter бота
                updateReviewVigulBasedOnBotCounter(review, assignedBot);

                assignedCount++;
            }

            // 5. Сохраняем обновленные отзывы
            reviewService.saveAll(reviewsWithoutBots);

            // 6. Проверяем, есть ли боты-заглушки
            long stubBotCount = reviewsWithoutBots.stream()
                    .filter(review -> review.getBot() != null &&
                            STUB_BOT_ID.equals(review.getBot().getId()))
                    .count();

            if (stubBotCount > 0) {
                log.error("ВНИМАНИЕ! После переназначения использовано {} ботов-заглушек из {} отзывов",
                        stubBotCount, reviewsWithoutBots.size());
                sendStubBotAlert(stubBotCount, reviewsWithoutBots.size());
            }

            log.info("=== УСПЕШНОЕ ПЕРЕНАЗНАЧЕНИЕ БОТОВ ===");
            log.info("Переназначено {} ботов из {} отзывов", assignedCount, reviewsWithoutBots.size());

            return assignedCount > 0;

        } catch (Exception e) {
            log.error("Ошибка при переназначении ботов для существующих отзывов", e);
            throw new RuntimeException("Ошибка при переназначении ботов", e);
        }
    }

    @Override
    public List<Bot> getAvailableBotsByRules(Filial filial, boolean vigul, int neededForOrder) {
        log.info("Получение доступных ботов для филиала ID {}, vigul={}, требуется={}",
                filial.getId(), vigul, neededForOrder);

        // 1. Получаем всех ботов для города
        List<Bot> allCityBots = botService.getFindAllByFilialCityId(filial.getCity().getId());
        log.info("Всего ботов в городе {}: {}", filial.getCity().getTitle(), allCityBots.size());

        // 2. Получаем ID ботов, использованных в этом филиале
        Set<Long> usedBotIdsInFilial = getUsedBotIdsInFilial(filial);
        log.info("Ботов уже использованных в филиале {}: {}", filial.getId(), usedBotIdsInFilial.size());

        // 3. Получаем ID ботов, занятых в активных отзывах других филиалов
        Set<Long> usedBotIdsGlobally = getUsedBotIdsGlobally(filial);
        log.info("Ботов занятых в активных отзывах других филиалов того же города: {}",
                usedBotIdsGlobally.size());

        // 4. Фильтруем ботов по основным условиям
        List<Bot> idealBots = allCityBots.stream()
                .filter(Objects::nonNull)
                .filter(bot -> bot.getId() != null)
                .filter(bot -> !usedBotIdsInFilial.contains(bot.getId()))
                .filter(bot -> !usedBotIdsGlobally.contains(bot.getId()))
                .filter(bot -> {
                    if (bot.getStatus() == null) return false;
                    String statusTitle = bot.getStatus().getBotStatusTitle();
                    return statusTitle != null && "Новый".equals(statusTitle.trim());
                })
                .collect(Collectors.toList());

        log.info("Идеальных ботов (не в этом филиале, не заняты в других): {}", idealBots.size());

        // 5. Применяем фильтры vigul к идеальным ботам
        List<Bot> filteredIdealBots = applyVigulFilters(idealBots, vigul, neededForOrder);
        log.info("Идеальных ботов после фильтра vigul: {}", filteredIdealBots.size());

        List<Bot> availableBots = new ArrayList<>(filteredIdealBots);

        // 6. Если идеальных ботов недостаточно, ищем запасных
        if (availableBots.size() < neededForOrder) {
            List<Bot> fallbackBots = allCityBots.stream()
                    .filter(Objects::nonNull)
                    .filter(bot -> bot.getId() != null)
                    .filter(bot -> !usedBotIdsInFilial.contains(bot.getId()))
                    .filter(bot -> {
                        if (bot.getStatus() == null) return false;
                        String statusTitle = bot.getStatus().getBotStatusTitle();
                        return statusTitle != null && "Новый".equals(statusTitle.trim());
                    })
                    .filter(bot -> !availableBots.contains(bot))
                    .collect(Collectors.toList());

            log.info("Запасных ботов (не в этом филиале, но могут быть заняты в других): {}",
                    fallbackBots.size());

            int remainingNeeded = neededForOrder - availableBots.size();
            List<Bot> filteredFallbackBots = applyVigulFilters(fallbackBots, vigul, remainingNeeded);
            log.info("Запасных ботов после фильтра vigul: {}", filteredFallbackBots.size());

            int toAdd = Math.min(remainingNeeded, filteredFallbackBots.size());
            availableBots.addAll(filteredFallbackBots.subList(0, toAdd));
        }

        log.info("Всего доступных ботов для назначения: {}/{}", availableBots.size(), neededForOrder);

        // Статистика выбора ботов
        logBotSelectionStatistics(availableBots, neededForOrder, vigul);

        return availableBots;
    }

    @Override
    public void checkAndNotifyAboutStubBots(List<Review> reviews) {
        log.info("Проверка наличия ботов-заглушек...");
        long stubBotCount = reviews.stream()
                .filter(review -> review.getBot() != null && STUB_BOT_ID.equals(review.getBot().getId()))
                .count();

        log.info("Найдено ботов-заглушек: {}", stubBotCount);

        if (stubBotCount > 0) {
            log.error("ВНИМАНИЕ! В заказе использовано {} ботов-заглушек из {} отзывов!",
                    stubBotCount, reviews.size());
            sendStubBotAlert(stubBotCount, reviews.size());
        } else {
            log.info("Ботов-заглушек не обнаружено");
        }
    }

    @Override
    public void updateReviewVigulBasedOnBotCounter(Review review, Bot bot) {
        if (review == null || bot == null) {
            return;
        }

        // Проверяем, не является ли бот заглушкой
        if (STUB_BOT_ID.equals(bot.getId())) {
            log.debug("Бот ID {} является заглушкой, пропускаем обновление isVigul", bot.getId());
            return;
        }

        // Получаем counter бота
        Integer botCounter = bot.getCounter();
        if (botCounter == null) {
            botCounter = 0;
        }

        // Если counter >= 3, устанавливаем isVigul = true
        if (botCounter >= MAX_ACTIVE_REVIEWS_PER_BOT) {
            if (!review.isVigul()) {
                review.setVigul(true);
                log.info("Обновлен отзыв ID {}: isVigul изменен с false на true (бот ID {} имеет counter={})",
                        review.getId(), bot.getId(), botCounter);
            }
        }
    }

    // ============ ПРИВАТНЫЕ ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ============

    private Bot findAndAssignUniqueBot(List<Bot> availableBots, Set<Long> usedBotIdsInThisOrder, int reviewIndex) {
        Bot assignedBot = null;

        // Ищем первого доступного бота, который еще не использован в этом заказе
        for (int j = 0; j < availableBots.size(); j++) {
            Bot candidateBot = availableBots.get(j);
            if (!usedBotIdsInThisOrder.contains(candidateBot.getId())) {
                assignedBot = availableBots.remove(j);
                usedBotIdsInThisOrder.add(assignedBot.getId());
                log.info("Назначен бот ID {} ({}) для отзыва {} (осталось доступных ботов: {})",
                        assignedBot.getId(), assignedBot.getFio(), reviewIndex + 1, availableBots.size());
                break;
            }
        }

        if (assignedBot == null) {
            // Если ботов не хватает, создаем бота-заглушку
            assignedBot = getStubBot();
            log.warn("Нет доступных ботов! Создан бот-заглушка для отзыва {}", reviewIndex + 1);
        }

        return assignedBot;
    }

    private Review createReviewWithBot(OrderDTO orderDTO, OrderDetails orderDetails,
                                       Filial filial, Bot bot, boolean vigul) {
        // Здесь нужно создать отзыв. В зависимости от вашей структуры,
        // может потребоваться дополнительные сервисы (CategoryService, SubCategoryService)
        // Для простоты оставим заглушку, которую вы заполните в соответствии с вашей логикой

        return Review.builder()
                .bot(bot)
                .filial(filial)
                .publish(false)
                .text("текст отзыва")
                .answer("")
                .worker(orderDetails.getOrder().getWorker())
                .product(orderDetails.getProduct())
                .price(orderDetails.getProduct().getPrice())
                .vigul(vigul)
                .orderDetails(orderDetails)
                .category(orderDetails.getOrder().getCompany().getCategoryCompany())
                .subCategory(orderDetails.getOrder().getCompany().getSubCategory())
                .build();
    }

    private Set<Long> getUsedBotIdsInFilial(Filial filial) {
        Set<Long> usedBotIds = new HashSet<>();

        try {
            List<Review> allReviewsInFilial = reviewRepository.findAllByFilial(filial);

            if (allReviewsInFilial != null) {
                for (Review existingReview : allReviewsInFilial) {
                    if (existingReview != null &&
                            existingReview.getBot() != null &&
                            existingReview.getBot().getId() != null &&
                            !STUB_BOT_ID.equals(existingReview.getBot().getId())) {

                        usedBotIds.add(existingReview.getBot().getId());
                    }
                }
            }
            log.debug("Найдено отзывов в филиале {}: {}",
                    filial.getId(), allReviewsInFilial != null ? allReviewsInFilial.size() : 0);

        } catch (Exception e) {
            log.error("Ошибка при получении использованных ботов для филиала {}", filial.getId(), e);
        }

        return usedBotIds;
    }

    private Set<Long> getUsedBotIdsGlobally(Filial currentFilial) {
        Set<Long> usedBotIds = new HashSet<>();

        try {
            City currentCity = currentFilial.getCity();
            if (currentCity == null || currentCity.getId() == null) {
                log.warn("У филиала ID {} не указан город", currentFilial.getId());
                return usedBotIds;
            }

            // Находим все филиалы того же города
            List<Filial> filialsInSameCity = filialService.findByCityId(currentCity.getId());
            if (filialsInSameCity == null || filialsInSameCity.isEmpty()) {
                return usedBotIds;
            }

            // Собираем ID всех филиалов того же города (кроме текущего)
            List<Long> otherFilialIdsInCity = filialsInSameCity.stream()
                    .filter(filial -> filial != null && filial.getId() != null)
                    .filter(filial -> !filial.getId().equals(currentFilial.getId()))
                    .map(Filial::getId)
                    .collect(Collectors.toList());

            if (otherFilialIdsInCity.isEmpty()) {
                return usedBotIds;
            }

            // Находим активные отзывы в этих филиалах
            List<Review> activeReviewsInSameCity = reviewRepository
                    .findByPublishFalseAndBotIsNotNullAndFilialIdIn(otherFilialIdsInCity);

            if (activeReviewsInSameCity != null) {
                for (Review existingReview : activeReviewsInSameCity) {
                    if (existingReview != null &&
                            existingReview.getBot() != null &&
                            existingReview.getBot().getId() != null &&
                            !STUB_BOT_ID.equals(existingReview.getBot().getId())) {

                        usedBotIds.add(existingReview.getBot().getId());
                    }
                }
            }

            log.debug("Найдено активных отзывов с ботами в других филиалах того же города: {}",
                    activeReviewsInSameCity != null ? activeReviewsInSameCity.size() : 0);

        } catch (Exception e) {
            log.error("Ошибка при получении глобально использованных ботов", e);
        }

        return usedBotIds;
    }

    private List<Bot> applyVigulFilters(List<Bot> baseBots, boolean vigul, int neededForOrder) {
        if (!vigul) {
            log.info("Фильтрация для vigul=false, требуется {} ботов", neededForOrder);

            // 1. Приоритет: боты с именем "Впиши Имя Фамилию"
            List<Bot> priority1 = baseBots.stream()
                    .filter(bot -> bot.getFio() != null &&
                            "Впиши Имя Фамилию".equals(bot.getFio().trim()))
                    .collect(Collectors.toList());

            log.info("Приоритет 1 - Боты с именем 'Впиши Имя Фамилию': {}", priority1.size());

            if (priority1.size() >= neededForOrder) {
                log.info("Ботов с именем достаточно, используем только их");
                return priority1;
            }

            // 2. Если не хватает, добавляем боты с counter >= 3
            List<Bot> result = new ArrayList<>(priority1);
            List<Bot> priority2 = baseBots.stream()
                    .filter(bot -> !priority1.contains(bot))
                    .filter(bot -> {
                        Integer counter = bot.getCounter();
                        if (counter == null) counter = 0;
                        return counter >= MAX_ACTIVE_REVIEWS_PER_BOT;
                    })
                    .collect(Collectors.toList());

            result.addAll(priority2);
            log.info("Приоритет 2 - Боты с counter >= 3: {}", priority2.size());
            log.info("Всего после приоритета 2: {}", result.size());

            if (result.size() >= neededForOrder) {
                log.info("Ботов с именем и counter >= 3 достаточно");
                return result;
            }

            // 3. Если все еще не хватает, добавляем боты с counter 0-2
            List<Bot> priority3 = baseBots.stream()
                    .filter(bot -> !priority1.contains(bot) && !priority2.contains(bot))
                    .filter(bot -> {
                        Integer counter = bot.getCounter();
                        if (counter == null) counter = 0;
                        return counter >= 0 && counter <= 2;
                    })
                    .collect(Collectors.toList());

            result.addAll(priority3);
            log.info("Приоритет 3 - Боты с counter 0-2: {}", priority3.size());
            log.info("Всего после приоритета 3: {}", result.size());

            if (result.size() >= neededForOrder) {
                log.info("Ботов достаточно после добавления counter 0-2");
                return result;
            }

            // 4. Если все еще не хватает, добавляем всех остальных ботов
            List<Bot> priority4 = baseBots.stream()
                    .filter(bot -> !result.contains(bot))
                    .collect(Collectors.toList());

            result.addAll(priority4);
            log.info("Приоритет 4 - Все остальные боты: {}", priority4.size());
            log.info("Всего доступных ботов: {}", result.size());

            return result;

        } else {
            log.info("Фильтрация для vigul=true, требуется {} ботов", neededForOrder);

            // 1. Приоритет: боты с counter >= 3
            List<Bot> priority1 = baseBots.stream()
                    .filter(bot -> {
                        Integer counter = bot.getCounter();
                        if (counter == null) counter = 0;
                        return counter >= MAX_ACTIVE_REVIEWS_PER_BOT;
                    })
                    .collect(Collectors.toList());

            log.info("Приоритет 1 - Боты с counter >= 3: {}", priority1.size());

            if (priority1.size() >= neededForOrder) {
                return priority1;
            }

            // 2. Если не хватает, добавляем боты с counter 0-2
            List<Bot> result = new ArrayList<>(priority1);
            List<Bot> priority2 = baseBots.stream()
                    .filter(bot -> !priority1.contains(bot))
                    .filter(bot -> {
                        Integer counter = bot.getCounter();
                        if (counter == null) counter = 0;
                        return counter >= 0 && counter <= 2;
                    })
                    .collect(Collectors.toList());

            result.addAll(priority2);
            log.info("Приоритет 2 - Боты с counter 0-2: {}", priority2.size());
            log.info("Всего после приоритета 2: {}", result.size());

            if (result.size() >= neededForOrder) {
                return result;
            }

            // 3. Если все еще не хватает, добавляем всех остальных
            List<Bot> priority3 = baseBots.stream()
                    .filter(bot -> !result.contains(bot))
                    .collect(Collectors.toList());

            result.addAll(priority3);
            log.info("Приоритет 3 - Все остальные боты: {}", priority3.size());
            log.info("Всего доступных ботов: {}", result.size());

            return result;
        }
    }

    private Bot getStubBot() {
        try {
            // Используем метод из BotService
            return botService.findBotById(STUB_BOT_ID);
        } catch (Exception e) {
            log.error("Ошибка при получении бота-заглушки", e);
            return createFallbackStubBot();
        }
    }

    private Bot createFallbackStubBot() {
        Bot stubBot = new Bot();
        stubBot.setId(STUB_BOT_ID);
        stubBot.setFio("Нет доступных аккаунтов");
        stubBot.setLogin("stub_account");
        stubBot.setPassword("");
        stubBot.setCounter(0);
        stubBot.setActive(false);
        return stubBot;
    }

    private void sendStubBotAlert(long stubCount, int totalReviews) {
        try {
            String alertMessage = String.format(
                    "⚠️ ВНИМАНИЕ! Недостаточно ботов!\n" +
                            "В заказе использовано ботов-заглушек: %d из %d\n" +
                            "Требуется пополнить базу ботов!",
                    stubCount, totalReviews
            );

            log.error(alertMessage);

            // Можно добавить отправку в Telegram
            // telegramService.sendAlertToAdmins(alertMessage);

        } catch (Exception e) {
            log.error("Ошибка при отправке оповещения о ботах-заглушках", e);
        }
    }

    private void logBotSelectionStatistics(List<Bot> selectedBots, int neededForOrder, boolean vigul) {
        Map<String, Long> stats = selectedBots.stream()
                .collect(Collectors.groupingBy(bot -> {
                    if (bot.getFio() != null && "Впиши Имя Фамилию".equals(bot.getFio().trim())) {
                        return "Имя 'Впиши Имя Фамилию'";
                    }
                    Integer counter = bot.getCounter();
                    if (counter == null) counter = 0;
                    if (counter >= 3) return "Counter >= 3";
                    if (counter >= 0 && counter <= 2) return "Counter 0-2";
                    return "Counter < 0 или null";
                }, Collectors.counting()));

        log.info("=== СТАТИСТИКА ВЫБОРА БОТОВ (vigul={}) ===", vigul);
        log.info("Требуется ботов: {}", neededForOrder);
        log.info("Выбрано ботов: {}", selectedBots.size());
        stats.forEach((category, count) ->
                log.info("  {}: {} ботов", category, count));
        log.info("================================");
    }
}
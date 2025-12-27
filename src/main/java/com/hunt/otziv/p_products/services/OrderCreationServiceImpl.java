package com.hunt.otziv.p_products.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderStatusDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderCreationService;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderCreationServiceImpl implements OrderCreationService {

    private final OrderRepository orderRepository;
    private final OrderDetailsService orderDetailsService;
    private final CompanyService companyService;
    private final CompanyStatusService companyStatusService;
    private final TelegramService telegramService;
    private final ProductService productService;
    private final ReviewService reviewService;
    private final BotService botService;
    private final ManagerService managerService;
    private final WorkerService workerService;
    private final OrderStatusService orderStatusService;
    private final SubCategoryService subCategoryService;
    private final CategoryService categoryService;
    private final FilialService filialService;
    private final ReviewRepository reviewRepository;

    private static final String STATUS_COMPANY_IN_WORK = "В работе";


    private static final Long STUB_BOT_ID = 1L;
    private static final int MAX_ACTIVE_REVIEWS_PER_BOT = 3; // Максимум активных отзывов на бота

    @Transactional
    public boolean createNewOrderWithReviews(Long companyId, Long productId, OrderDTO orderDTO) {
        try {
            Order order = saveOrder(orderDTO, productId);
            log.info("1. Сохранили ORDER");

            // 2. Создаём и сохраняем orderDetails без отзывов
            OrderDetails orderDetails = toEntityOrderDetailFromDTO(orderDTO, order, productId);
            orderDetails = orderDetailsService.save(orderDetails);
            log.info("2. Сохранили ORDER-DETAIL без отзывов");

            // 3. Создаём и сохраняем отзывы с уникальными ботами
            List<Review> reviews = createReviewsWithUniqueBots(orderDTO, orderDetails);
            reviewService.saveAll(reviews);
            log.info("3. Сохранили {} отзывов с уникальными ботами", reviews.size());

            // 4. Проверяем наличие ботов-заглушек и отправляем оповещение
            checkAndNotifyAboutStubBots(reviews);

            // 5. Присваиваем отзывы orderDetails и обновляем его
            orderDetails.setReviews(reviews);
            orderDetailsService.save(orderDetails);
            log.info("5. Привязали отзывы к ORDER-DETAIL и обновили его");

            // 6. Обновляем заказ с добавленным orderDetails
            updateOrder(order, orderDetails);
            log.info("6. Сохранили ORDER с ORDER-DETAIL в БД");

            // 7. Обновляем счётчики компании
            updateCompanyCounter(order, companyId);
            log.info("7. Обновили счётчики компании");

            // 8. Оповещение
            notifyWorker(order);

            return true;
        } catch (Exception e) {
            log.error("Ошибка при создании нового заказа с отзывами", e);
            throw new RuntimeException("Ошибка при создании нового заказа с отзывами", e);
        }
    }

    /**
     * Создание отзывов с уникальными ботами
     */
    private List<Review> createReviewsWithUniqueBots(OrderDTO orderDTO, OrderDetails orderDetails) {
        List<Review> reviewList = new ArrayList<>();

        // 1. Получаем филиал
        Filial filial = convertFilialDTOToFilial(orderDTO.getFilial());

        // 2. Получаем значение vigul (если есть в OrderDTO, иначе false)
        boolean vigul = false;
        log.info("Создание отзывов с vigul = {}", vigul);

        // 3. Получаем всех ботов для города
        List<Bot> allCityBots = botService.getFindAllByFilialCityId(filial.getCity().getId());
        log.info("Всего ботов в городе {}: {}", filial.getCity().getTitle(), allCityBots.size());

        // 4. Получаем ID ботов, использованных в этом филиале (ВСЕ отзывы)
        Set<Long> usedBotIdsInFilial = getUsedBotIdsInFilial(filial);
        log.info("Ботов уже использованных в филиале {}: {}", filial.getId(), usedBotIdsInFilial.size());

        // 5. Получаем ID ботов, занятых в активных отзывах других филиалов того же города
        Set<Long> usedBotIdsGlobally = getUsedBotIdsGlobally(filial);
        log.info("Ботов занятых в активных отзывах других филиалов того же города: {}", usedBotIdsGlobally.size());

        // 6. Фильтруем ботов по основным условиям
        // Идеальные боты - не использовались в этом филиале и не заняты в других
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

        // 7. Применяем фильтры vigul к идеальным ботам
        List<Bot> filteredIdealBots = applyVigulFilters(idealBots, vigul);
        log.info("Идеальных ботов после фильтра vigul: {}", filteredIdealBots.size());

        List<Bot> availableBots = new ArrayList<>(filteredIdealBots);

        // 8. Если идеальных ботов недостаточно, ищем запасных
        int neededForOrder = orderDTO.getAmount();
        if (availableBots.size() < neededForOrder) {
            // Запасные боты - не использовались в этом филиале, но могут быть заняты в других
            List<Bot> fallbackBots = allCityBots.stream()
                    .filter(Objects::nonNull)
                    .filter(bot -> bot.getId() != null)
                    .filter(bot -> !usedBotIdsInFilial.contains(bot.getId())) // Главное правило!
                    .filter(bot -> {
                        if (bot.getStatus() == null) return false;
                        String statusTitle = bot.getStatus().getBotStatusTitle();
                        return statusTitle != null && "Новый".equals(statusTitle.trim());
                    })
                    .filter(bot -> !availableBots.contains(bot)) // Не дублируем уже выбранных
                    .collect(Collectors.toList());

            log.info("Запасных ботов (не в этом филиале, но могут быть заняты в других): {}", fallbackBots.size());

            // Применяем фильтры vigul к запасным ботам
            List<Bot> filteredFallbackBots = applyVigulFilters(fallbackBots, vigul);
            log.info("Запасных ботов после фильтра vigul: {}", filteredFallbackBots.size());

            // Добавляем необходимое количество запасных ботов
            int needed = neededForOrder - availableBots.size();
            int toAdd = Math.min(needed, filteredFallbackBots.size());
            availableBots.addAll(filteredFallbackBots.subList(0, toAdd));
        }

        log.info("Всего доступных ботов для заказа: {}", availableBots.size());

        // 9. Создаем отзывы с УНИКАЛЬНЫМИ ботами
        Set<Long> usedBotIdsInThisOrder = new HashSet<>();

        for (int i = 0; i < neededForOrder; i++) {
            Bot assignedBot = null;

            // Ищем первого доступного бота, который еще не использован в этом заказе
            for (int j = 0; j < availableBots.size(); j++) {
                Bot candidateBot = availableBots.get(j);
                if (!usedBotIdsInThisOrder.contains(candidateBot.getId())) {
                    assignedBot = availableBots.remove(j);
                    usedBotIdsInThisOrder.add(assignedBot.getId());
                    log.info("Назначен бот ID {} ({}) для отзыва {} (осталось доступных ботов: {})",
                            assignedBot.getId(), assignedBot.getFio(), i + 1, availableBots.size());
                    break;
                }
            }

            if (assignedBot == null) {
                // Если ботов не хватает, создаем бота-заглушку
                assignedBot = getStubBot();
                log.warn("Нет доступных ботов! Создан бот-заглушка для отзыва {}", i + 1);
            }

            Review review = createReviewWithBot(orderDTO, orderDetails, filial, assignedBot, vigul);
            reviewList.add(review);
        }

        return reviewList;
    }

    /**
     * Получение ID ботов, которые уже использовались в этом филиале (все отзывы)
     */
    private Set<Long> getUsedBotIdsInFilial(Filial filial) {
        Set<Long> usedBotIds = new HashSet<>();

        try {
            List<Review> allReviewsInFilial = reviewRepository.findAllByFilial(filial);

            if (allReviewsInFilial != null) {
                for (Review existingReview : allReviewsInFilial) {
                    if (existingReview != null &&
                            existingReview.getBot() != null &&
                            existingReview.getBot().getId() != null &&
                            // Исключаем бота-заглушку
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

    /**
     * Получение ID ботов, занятых в активных отзывах других филиалов того же города
     */
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
                            // Исключаем бота-заглушку
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

    /**
     * Выделенный метод для применения фильтров по vigul
     */
    private List<Bot> applyVigulFilters(List<Bot> baseBots, boolean vigul) {
        if (!vigul) {
            // Для vigul = false
            log.info("Фильтрация для vigul=false");

            // Сначала ищем ботов с именем "Впиши Имя Фамилию"
            List<Bot> strictFiltered = baseBots.stream()
                    .filter(bot -> {
                        if (bot.getFio() == null) {
                            return false;
                        }
                        boolean hasCorrectName = "Впиши Имя Фамилию".equals(bot.getFio().trim());
                        return hasCorrectName;
                    })
                    .collect(Collectors.toList());

            log.info("Ботов с именем 'Впиши Имя Фамилию': {}", strictFiltered.size());

            if (!strictFiltered.isEmpty()) {
                return strictFiltered;
            }

            log.warn("Нет ботов с именем 'Впиши Имя Фамилию', используем всех доступных ботов");
            return baseBots;

        } else {
            // Для vigul = true
            log.info("Фильтрация для vigul=true");

            // Сначала ищем ботов с counter >= 3
            List<Bot> strictFiltered = baseBots.stream()
                    .filter(bot -> {
                        Integer counter = bot.getCounter();
                        if (counter == null) counter = 0;
                        return counter >= MAX_ACTIVE_REVIEWS_PER_BOT;
                    })
                    .collect(Collectors.toList());

            log.info("Ботов с counter >= 3: {}", strictFiltered.size());

            if (!strictFiltered.isEmpty()) {
                return strictFiltered;
            }

            // Если нет ботов с counter >= 3, ищем с counter 0-2
            List<Bot> fallbackFiltered = baseBots.stream()
                    .filter(bot -> {
                        Integer counter = bot.getCounter();
                        if (counter == null) counter = 0;
                        return counter >= 0 && counter <= 2;
                    })
                    .collect(Collectors.toList());

            log.info("Ботов с counter от 0 до 2: {}", fallbackFiltered.size());

            if (!fallbackFiltered.isEmpty()) {
                log.warn("Нет ботов с counter >= 3, используем ботов с counter от 0 до 2");
                return fallbackFiltered;
            }

            // Если совсем нет ботов с подходящим counter, используем всех
            log.warn("Нет ботов с подходящим counter, используем всех доступных ботов");
            return baseBots;
        }
    }

    /**
     * Метод для получения бота-заглушки
     */
    private Bot getStubBot() {
        try {
            Optional<Bot> stubBotOptional = Optional.ofNullable(botService.findBotById(STUB_BOT_ID));
            if (stubBotOptional.isPresent()) {
                return stubBotOptional.get();
            } else {
                log.warn("Бот-заглушка не найден в базе, создаем временного");
                return createFallbackStubBot();
            }
        } catch (Exception e) {
            log.error("Ошибка при получении бота-заглушки", e);
            return createFallbackStubBot();
        }
    }

    /**
     * Создание временного бота-заглушки на случай ошибки
     */
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

    /**
     * Проверка наличия ботов-заглушек
     */
    private void checkAndNotifyAboutStubBots(List<Review> reviews) {
        log.info("4. Проверяем наличие ботов-заглушек...");
        long stubBotCount = reviews.stream()
                .filter(review -> review.getBot() != null && STUB_BOT_ID.equals(review.getBot().getId()))
                .count();

        log.info("Найдено ботов-заглушек: {}", stubBotCount);

        if (stubBotCount > 0) {
            log.error("ВНИМАНИЕ! В заказе использовано {} ботов-заглушек из {} отзывов!",
                    stubBotCount, reviews.size());
            sendStubBotAlert(stubBotCount, reviews.size());
        } else {
            log.info("4. Ботов-заглушек не обнаружено");
        }
    }
    /**
     * Отправка оповещения о ботах-заглушках
     */
    private void sendStubBotAlert(long stubCount, int totalReviews) {
        try {
            String alertMessage = String.format(
                    "⚠️ ВНИМАНИЕ! Недостаточно ботов!\n" +
                            "В заказе использовано ботов-заглушек: %d из %d\n" +
                            "Требуется пополнить базу ботов!",
                    stubCount, totalReviews
            );

            log.error(alertMessage);

            // Отправляем через существующий TelegramService
            // telegramService.sendAlertToAdmins(alertMessage);

        } catch (Exception e) {
            log.error("Ошибка при отправке оповещения о ботах-заглушках", e);
        }
    }

    /**
     * Создание отзыва с назначенным ботом
     */
    private Review createReviewWithBot(OrderDTO orderDTO, OrderDetails orderDetails,
                                       Filial filial, Bot bot, boolean vigul) {
        return Review.builder()
                .category(convertCategoryDTOToCompany(orderDTO.getCompany().getCategoryCompany()))
                .subCategory(convertSubCompanyDTOToSubCompany(orderDTO.getCompany().getSubCategory()))
                .text("Текст отзыва")
                .answer("")
                .orderDetails(orderDetails)
                .bot(bot)
                .filial(filial)
                .publish(false)
                .worker(orderDetails.getOrder().getWorker())
                .product(orderDetails.getProduct())
                .price(orderDetails.getProduct().getPrice())
                .vigul(vigul) // Используем переданное значение vigul
                .build();
    }

    private void logBotUsageStatistics(Filial filial, int neededForOrder,
                                       List<Bot> availableBots) {
        log.info("=== СТАТИСТИКА ИСПОЛЬЗОВАНИЯ БОТОВ ===");
        log.info("Филиал: {}, город: {}",
                filial.getId(), filial.getCity().getTitle());
        log.info("Всего ботов в городе: {}",
                botService.getFindAllByFilialCityId(filial.getCity().getId()).size());
        log.info("Использовано в этом филиале: {}",
                getUsedBotIdsInFilial(filial).size());
        log.info("Занято в других филиалах: {}",
                getUsedBotIdsGlobally(filial).size());
        log.info("Доступно для заказа: {}", availableBots.size());
        log.info("Требуется для заказа: {}", neededForOrder);
        log.info("Дефицит: {}", Math.max(0, neededForOrder - availableBots.size()));
        log.info("==============================");
    }


    private Order saveOrder(OrderDTO orderDTO, Long productId) {
        Order order = toEntityOrderFromDTO(orderDTO, productId);
        return orderRepository.save(order);
    }

    private void updateOrder(Order order, OrderDetails orderDetails) {
        List<OrderDetails> detailsList = Optional.ofNullable(order.getDetails()).orElse(new ArrayList<>());
        detailsList.add(orderDetails);
        order.setDetails(detailsList);
        orderRepository.save(order);
    }

    private void updateCompanyCounter(Order order, Long companyId) {
        Company company = companyService.getCompaniesById(companyId);
        int updatedCounter = company.getCounterNoPay() + (order.getAmount() - company.getCounterNoPay());
        company.setCounterNoPay(updatedCounter);
        company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_WORK));
        companyService.save(company);
    }

    private void notifyWorker(Order order) {
        if (order.getWorker() != null && order.getWorker().getUser() != null) {
            Long chatId = order.getWorker().getUser().getTelegramChatId();
            if (chatId != null) {
                String msg = "У вас новый заказ для: " + order.getCompany().getTitle();
                telegramService.sendMessage(chatId, msg);
            }
        }
    }
    //    ================================================== CONVERTER =====================================================

    private Worker convertWorkerDTOToWorker(WorkerDTO workerDTO){ // Конвертер из DTO для работника
        return workerService.getWorkerById(workerDTO.getWorkerId());
    } // Конвертер из DTO для работника
    private Company convertCompanyDTOToCompany(CompanyDTO companyDTO){ // Конвертер из DTO для компании
        return companyService.getCompaniesById(companyDTO.getId());
    } // Конвертер из DTO для компании
    private Manager convertManagerDTOToManager(ManagerDTO managerDTO){// Конвертер из DTO для менеджера
        return managerService.getManagerById(managerDTO.getManagerId());
    } // Конвертер из DTO для менеджера
    private OrderStatus convertStatusDTOToStatus(OrderStatusDTO orderStatusDTO){// Конвертер из DTO для статуса заказа
        return orderStatusService.getOrderStatusByTitle(orderStatusDTO.getTitle());
    } // Конвертер из DTO для статуса заказа
    private Filial convertFilialDTOToFilial(FilialDTO filialDTO){// Конвертер из DTO для филиала
        return filialService.getFilial(filialDTO.getId());
    } // Конвертер из DTO для филиала
    private Order toEntityOrderFromDTO(OrderDTO orderDTO, Long productId){ // Конвертер из DTO для заказа
        Product product1 = productService.findById(productId);
        return Order.builder()
                .amount(orderDTO.getAmount())
                .complete(false)
                .worker(convertWorkerDTOToWorker(orderDTO.getWorker()))
                .company(convertCompanyDTOToCompany(orderDTO.getCompany()))
                .manager(convertManagerDTOToManager(orderDTO.getManager()))
                .filial(convertFilialDTOToFilial(orderDTO.getFilial()))
                .sum(product1.getPrice().multiply(BigDecimal.valueOf(orderDTO.getAmount())))
                .status(convertStatusDTOToStatus(orderDTO.getStatus()))
                .build();
    } // Конвертер из DTO для заказа
    private OrderDetails toEntityOrderDetailFromDTO(OrderDTO orderDTO, Order order, Long productId){ // Конвертер из DTO для деталей заказа
        Product product1 = productService.findById(productId);
        return OrderDetails.builder()
                .amount(orderDTO.getAmount())
                .price(product1.getPrice().multiply(BigDecimal.valueOf(orderDTO.getAmount())))
                .order(order)
                .product(product1)
                .comment("")
                .build();
    } // Конвертер из DTO для деталей заказа

    private Category convertCategoryDTOToCompany(CategoryDTO categoryDTO){ // Конвертер из DTO для категории
        return categoryService.getCategoryByIdCategory(categoryDTO.getId());
    } // Конвертер из DTO для категории
    private SubCategory convertSubCompanyDTOToSubCompany(SubCategoryDTO subCategoryDTO){// Конвертер из DTO для субкатегории
        return subCategoryService.getSubCategoryById(subCategoryDTO.getId());
    } // Конвертер из DTO для субкатегории

    public OrderDTO convertToOrderDTOToRepeat(Order order){ // Конвертер DTO для создания нового заказа после завершения предыдущего
        return OrderDTO.builder()
                .id(order.getId())
                .amount(order.getAmount())
                .worker(convertToWorkerDTO(order.getWorker()))
                .manager(convertToManagerDTO(order.getManager()))
                .company(convertToCompanyDTO(order.getCompany()))
                .filial(convertToFilialDTO(order.getFilial()))
                .commentsCompany(order.getCompany().getCommentsCompany())
                .status(convertToStatusDTO("Новый"))
                .build();
    } // Конвертер DTO для создания нового заказа после завершения предыдущего
    private WorkerDTO convertToWorkerDTO(Worker worker){// Конвертер DTO для работника
        return WorkerDTO.builder()
                .workerId(worker.getId())
                .user(worker.getUser())
                .build();
    } // Конвертер DTO для работника
    private ManagerDTO convertToManagerDTO(Manager manager){// Конвертер DTO для менеджера
        return ManagerDTO.builder()
                .managerId(manager.getId())
                .user(manager.getUser())
                .payText(manager.getPayText())
                .clientId(manager.getClientId())
                .build();
    } // Конвертер DTO для менеджера
    private CompanyDTO convertToCompanyDTO(Company company){ // Конвертер DTO для компании
        return CompanyDTO.builder()
                .id(company.getId())
                .title(company.getTitle())
                .telephone(company.getTelephone())
                .urlChat(company.getUrlChat())
                .manager(convertToManagerDTO(company.getManager()))
                .workers(convertToWorkerDTOList(company.getWorkers()))
                .filials(convertToFilialDTOList(company.getFilial()))
                .categoryCompany(company.getCategoryCompany() != null ? convertToCategoryDto(company.getCategoryCompany()) : null)
                .subCategory(company.getSubCategory() != null ? convertToSubCategoryDto(company.getSubCategory()) : null)
                .groupId(company.getGroupId())
                .build();
    } // Конвертер DTO для компании

    private OrderStatusDTO convertToStatusDTO(String status) {
        return OrderStatusDTO.builder()
                .title(status)
                .build();
    }
    private FilialDTO convertToFilialDTO(Filial filial){// Конвертер DTO для филиала
        return FilialDTO.builder()
                .id(filial.getId())
                .title(filial.getTitle())
                .url(filial.getUrl())
                .build();
    } // Конвертер DTO для филиала
    private Set<WorkerDTO> convertToWorkerDTOList(Set<Worker> workers){// Конвертер DTO для списка работников
        return workers.stream().map(this::convertToWorkerDTO).collect(Collectors.toSet());
    } // Конвертер DTO для списка работников
    private Set<FilialDTO> convertToFilialDTOList(Set<Filial> filials){ // Конвертер DTO для списка филиалов
        return filials.stream().map(this::convertToFilialDTO).collect(Collectors.toSet());
    } // Конвертер DTO для списка филиалов
    private CategoryDTO convertToCategoryDto(Category category) {// Конвертер DTO для категории
        CategoryDTO categoryDTO = new CategoryDTO();
        categoryDTO.setId(category.getId() != null ? category.getId() : null);
        categoryDTO.setCategoryTitle(category.getCategoryTitle() != null ? category.getCategoryTitle() : null);
        // Other fields if needed
        return categoryDTO;
    } // Конвертер DTO для категории
    private SubCategoryDTO convertToSubCategoryDto(SubCategory subCategory) { // Конвертер DTO для субкатегории
        SubCategoryDTO subCategoryDTO = new SubCategoryDTO();
        subCategoryDTO.setId(subCategory.getId() != null ? subCategory.getId() : 0L);
        subCategoryDTO.setSubCategoryTitle(subCategory.getSubCategoryTitle() != null ? subCategory.getSubCategoryTitle() : "Не выбрано");
        // Other fields if needed
        return subCategoryDTO;
    } // Конвертер DTO для субкатегории


}

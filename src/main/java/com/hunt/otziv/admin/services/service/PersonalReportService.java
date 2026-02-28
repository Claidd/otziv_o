package com.hunt.otziv.admin.services.service;

import com.hunt.otziv.admin.dto.presonal.UserData;
import com.hunt.otziv.admin.model.Quadruple;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.dto.RegistrationUserDTO;
import com.hunt.otziv.u_users.services.service.ImageService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.z_zp.services.PaymentCheckService;
import com.hunt.otziv.z_zp.services.ZpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.util.Pair;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class PersonalReportService {

    private final ZpService zpService;
    private final PaymentCheckService paymentCheckService;
    private final UserService userService;
    private final LeadService leadService;
    private final ReviewService reviewService;
    private final OrderService orderService;
    private final CompanyService companyService;
    private final ImageService imageService;

    private static final String ROLE_MANAGER = "ROLE_MANAGER";
    private static final String ROLE_WORKER = "ROLE_WORKER";

    private static final String STATUS_IN_WORK = "В работе";
    private static final String STATUS_NEW = "Новый";
    private static final String STATUS_TO_CHECK = "В проверку";
    private static final String STATUS_IN_CHECK = "На проверке";
    private static final String STATUS_CORRECTION = "Коррекция";
    private static final String STATUS_PUBLISHED = "Опубликовано";
    private static final String STATUS_BILL_SENT = "Выставлен счет";
    private static final String STATUS_REMINDER = "Напоминание";
    private static final String STATUS_NOT_PAID = "Не оплачено";

    private static final long DEFAULT_IMAGE_ID = 1L;

    public Map<String, UserData> getPersonalsAndCountToMap() {
        PersonalDataBundle bundle = loadPersonalDataBundle(LocalDate.now());
        return buildUserDataMap(bundle);
    }

    public String displayResult(Map<String, UserData> result) {
        StringBuilder resultBuilder = new StringBuilder();
        List<Map.Entry<String, UserData>> sortedEntries = sortUserDataEntries(result);

        long totalManagerRevenue = calculateTotalManagerRevenue(result);
        long totalSpecificManagersRevenue = calculateTotalSpecificManagersRevenue(
                result,
                Set.of("Звуков Андрей", "Анжелика Б.")
        );
        long totalZp = extractTotalZp(result);
        long totalNewCompanies = calculateTotalNewCompanies(result);

        resultBuilder.append("Выручка за месяц всей компании: ")
                .append(totalManagerRevenue)
                .append(" руб. ( ")
                .append(totalSpecificManagersRevenue)
                .append(" руб. )\n")
                .append("Общие затраты по ЗП: ")
                .append(totalZp)
                .append(" руб. \n")
                .append("Новых компаний за месяц: ")
                .append(totalNewCompanies)
                .append("\n\n");

        resultBuilder.append("Выручка менеджеров:\n");
        sortedEntries.stream()
                .filter(entry -> ROLE_MANAGER.equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    UserData userData = entry.getValue();
                    resultBuilder.append(entry.getKey())
                            .append(": ")
                            .append(userData.getTotalSum())
                            .append(" руб. Новых: ")
                            .append(userData.getNewCompanies())
                            .append("\n");
                });

        resultBuilder.append("\nМенеджеры:\n\n");
        sortedEntries.stream()
                .filter(entry -> ROLE_MANAGER.equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    UserData userData = entry.getValue();

                    String orderStatus = "Лиды: " + userData.getLeadsNew()
                            + " В проверку: " + userData.getOrderToCheck()
                            + " На проверке: " + userData.getOrderInCheck()
                            + " Опубликовано: " + userData.getOrderInPublished()
                            + " Выставлен счет: " + userData.getOrderInWaitingPay1()
                            + " Напоминание: " + userData.getOrderInWaitingPay2()
                            + " Не оплачено: " + userData.getOrderNoPay();

                    String orderStatsForWorkers = "Новых - " + userData.getNewOrders()
                            + " Коррекция - " + userData.getCorrectOrders()
                            + " Выгул - " + userData.getInVigul()
                            + " Публикация - " + userData.getInPublish();

                    resultBuilder.append(entry.getKey())
                            .append(": ")
                            .append(userData.getSalary())
                            .append(" руб. \n")
                            .append(orderStatus)
                            .append("\n")
                            .append(orderStatsForWorkers)
                            .append("\n\n");
                });

        resultBuilder.append("\nЗП Работников:\n");
        sortedEntries.stream()
                .filter(entry -> ROLE_WORKER.equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    UserData userData = entry.getValue();

                    String orderStats = "Новые - " + userData.getNewOrders()
                            + " В коррекции - " + userData.getCorrectOrders()
                            + " В выгуле - " + userData.getInVigul()
                            + " На публикации - " + userData.getInPublish();

                    resultBuilder.append(entry.getKey())
                            .append(": ")
                            .append(userData.getSalary())
                            .append(" руб.  \n")
                            .append(orderStats)
                            .append("\n\n");
                });

        return resultBuilder.toString();
    }

    public String displayResultToTelegramAdmin(Map<String, UserData> result) {
        StringBuilder resultBuilder = new StringBuilder();
        List<Map.Entry<String, UserData>> sortedEntries = sortUserDataEntries(result);

        long totalManagerRevenue = calculateTotalManagerRevenue(result);
        long totalSpecificManagersRevenue = calculateTotalSpecificManagersRevenue(result, Set.of("Анжелика Б."));
        long totalZp = extractTotalZp(result);
        long totalNewCompanies = calculateTotalNewCompanies(result);

        resultBuilder.append("*📊 Отчёт за месяц*\n\n")
                .append("*Общая выручка:* `").append(totalManagerRevenue).append(" руб.`\n")
                .append("*Выручка (Иван):* `").append(escapeMarkdown(String.valueOf(totalSpecificManagersRevenue))).append(" руб.`\n")
                .append("*Общие затраты на ЗП:* `").append(totalZp).append(" руб.`\n")
                .append("*Новых компаний:* `").append(totalNewCompanies).append("`\n\n");

        resultBuilder.append("*👤 Выручка менеджеров:*\n");
        sortedEntries.stream()
                .filter(entry -> ROLE_MANAGER.equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    UserData user = entry.getValue();
                    resultBuilder.append("• ")
                            .append(escapeMarkdown(entry.getKey()))
                            .append(": `")
                            .append(user.getTotalSum())
                            .append(" руб.`")
                            .append(" — Новых: `")
                            .append(user.getNewCompanies())
                            .append("`\n");
                });

        resultBuilder.append("\n*💼 Менеджеры и статусы:*\n");
        sortedEntries.stream()
                .filter(entry -> ROLE_MANAGER.equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    UserData user = entry.getValue();
                    String fio = escapeMarkdown(entry.getKey());

                    resultBuilder.append("*").append(fio).append("* — `").append(user.getSalary()).append(" руб.`\n");

                    resultBuilder.append("`Лиды:` ").append(user.getLeadsNew()).append("  ")
                            .append("`В проверку:` ").append(user.getOrderToCheck()).append("  ")
                            .append("`На проверке:` ").append(user.getOrderInCheck()).append("  ")
                            .append("`Опубликовано:` ").append(user.getOrderInPublished()).append("\n");

                    resultBuilder.append("`Счёт:` ").append(user.getOrderInWaitingPay1()).append("  ")
                            .append("`Напоминание:` ").append(user.getOrderInWaitingPay2()).append("  ")
                            .append("`Не оплачено:` ").append(user.getOrderNoPay()).append("\n");

                    resultBuilder.append("`Новых:` ").append(user.getNewOrders()).append("  ")
                            .append("`Коррекция:` ").append(user.getCorrectOrders()).append("  ")
                            .append("`Выгул:` ").append(user.getInVigul()).append("  ")
                            .append("`Публикация:` ").append(user.getInPublish()).append("\n\n");
                });

        return resultBuilder.toString();
    }

    public List<UserData> getPersonalsAndCountToScore(LocalDate localDate) {
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        Map<String, Quadruple<String, Long, Long, Long>> zps = zpService.getAllZpToMonth(firstDayOfMonth, lastDayOfMonth);
        Map<String, Pair<Long, Long>> pcs = paymentCheckService.getAllPaymentToMonth(firstDayOfMonth, lastDayOfMonth);
        Map<String, Long> newCompanies = companyService.getAllNewCompanies(firstDayOfMonth, lastDayOfMonth);
        Map<String, Pair<Long, Long>> newOrders = orderService.getNewOrderAll(STATUS_NEW, STATUS_CORRECTION);
        Map<String, Pair<Long, Long>> inPublishAndVigul = reviewService.getAllPublishAndVigul(firstDayOfMonth, localDate);
        Map<String, Pair<Long, Long>> imagesIds = imageService.getAllImages();
        Map<String, Pair<Long, Long>> leadsNewAndInWork = leadService.getAllLeadsToMonth(STATUS_IN_WORK, firstDayOfMonth, lastDayOfMonth);

        long zpTotal = zps.values().stream()
                .mapToLong(Quadruple::getSecond)
                .sum();

        List<UserData> result = new ArrayList<>();

        for (Map.Entry<String, Quadruple<String, Long, Long, Long>> entry : zps.entrySet()) {
            String fio = entry.getKey();
            Quadruple<String, Long, Long, Long> pair = entry.getValue();

            Long totalSum = getFirstOrDefault(pcs, fio, 0L);
            Long newCompanyCount = newCompanies.getOrDefault(fio, 0L);
            Long newOrderCount = getFirstOrDefault(newOrders, fio, 0L);
            Long correctOrders = getSecondOrDefault(newOrders, fio, 0L);
            Long inVigul = getFirstOrDefault(inPublishAndVigul, fio, 0L);
            Long inPublishCount = getSecondOrDefault(inPublishAndVigul, fio, 0L);
            Long imageId = getFirstOrDefault(imagesIds, fio, DEFAULT_IMAGE_ID);
            Long userId = getSecondOrDefault(imagesIds, fio, 0L);

            Long ordersCount = pair.getThird();
            Long reviewsCount = pair.getFourth();
            Long leadsNew = getFirstOrDefault(leadsNewAndInWork, fio, 0L);
            Long leadsInWork = getSecondOrDefault(leadsNewAndInWork, fio, 0L);
            Long percentInWork = safePercent(leadsInWork, leadsNew);

            String role = pair.getFirst();

            result.add(new UserData(
                    fio,
                    role,
                    pair.getSecond(),
                    totalSum,
                    zpTotal,
                    newCompanyCount,
                    newOrderCount,
                    correctOrders,
                    inVigul,
                    inPublishCount,
                    imageId,
                    userId,
                    ordersCount,
                    reviewsCount,
                    leadsNew,
                    leadsInWork,
                    percentInWork,
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L
            ));
        }

        return result;
    }

    @Transactional
    public Map<String, UserData> getPersonalsAndCountToMapToOwner(Long userId) {
        RegistrationUserDTO user = userService.findById(userId);
        if (user == null) {
            return Collections.emptyMap();
        }

        Map<String, UserData> fullMap = getPersonalsAndCountToMap();

        Set<String> allowedFio = user.getManagers().stream()
                .flatMap(manager -> {
                    Stream<String> workersFio = manager.getUser().getWorkers().stream()
                            .map(worker -> worker.getUser().getFio());
                    return Stream.concat(Stream.of(manager.getUser().getFio()), workersFio);
                })
                .collect(Collectors.toSet());

        return filterUserDataMap(fullMap, allowedFio);
    }

    @Transactional
    public Map<String, UserData> getPersonalsAndCountToMapToManager(Long userId) {
        RegistrationUserDTO user = userService.findById(userId);
        if (user == null) {
            return Collections.emptyMap();
        }

        Map<String, UserData> fullMap = getPersonalsAndCountToMap();

        Set<String> allowedFio = Stream.concat(
                Stream.of(user.getFio()),
                user.getWorkers().stream().map(worker -> worker.getUser().getFio())
        ).collect(Collectors.toSet());

        return filterUserDataMap(fullMap, allowedFio);
    }

    public String displayResultToManager(Map<String, UserData> result) {
        StringBuilder resultBuilder = new StringBuilder();
        List<Map.Entry<String, UserData>> sortedEntries = sortUserDataEntries(result);

        sortedEntries.stream()
                .filter(entry -> ROLE_MANAGER.equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    UserData userData = entry.getValue();

                    String orderStatus = "Лиды: " + userData.getLeadsNew()
                            + " В проверку: " + userData.getOrderToCheck()
                            + " На проверке: " + userData.getOrderInCheck()
                            + " Опубликовано: " + userData.getOrderInPublished()
                            + " Выставлен счет: " + userData.getOrderInWaitingPay1()
                            + " Напоминание: " + userData.getOrderInWaitingPay2()
                            + " Не оплачено: " + userData.getOrderNoPay();

                    String orderStatsForWorkers = "\nНовых - " + userData.getNewOrders()
                            + " Коррекция - " + userData.getCorrectOrders()
                            + " Выгул - " + userData.getInVigul()
                            + " Публикация - " + userData.getInPublish();

                    resultBuilder.append(entry.getKey())
                            .append(": ")
                            .append(userData.getSalary())
                            .append(" руб. ")
                            .append("\n\n")
                            .append(orderStatus)
                            .append("\n")
                            .append(orderStatsForWorkers)
                            .append("\n\n");
                });

        resultBuilder.append("\nСпециалисты:\n");
        sortedEntries.stream()
                .filter(entry -> ROLE_WORKER.equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    UserData userData = entry.getValue();

                    String orderStats = "Новые - " + userData.getNewOrders()
                            + " В коррекции - " + userData.getCorrectOrders()
                            + " В выгуле - " + userData.getInVigul()
                            + " На публикации - " + userData.getInPublish();

                    resultBuilder.append(entry.getKey())
                            .append("\n")
                            .append(orderStats)
                            .append("\n\n");
                });

        return resultBuilder.toString();
    }

    @Transactional
    public Map<String, UserData> getPersonalsAndCountToMapToWorker(Long userId) {
        RegistrationUserDTO user = userService.findById(userId);
        if (user == null) {
            return Collections.emptyMap();
        }

        Map<String, UserData> fullMap = getPersonalsAndCountToMap();

        Set<String> allowedFio = Stream.concat(
                Stream.of(user.getFio()),
                user.getWorkers().stream().map(worker -> worker.getUser().getFio())
        ).collect(Collectors.toSet());

        return filterUserDataMap(fullMap, allowedFio);
    }

    public String displayResultToWorker(Map<String, UserData> result) {
        StringBuilder resultBuilder = new StringBuilder();
        List<Map.Entry<String, UserData>> sortedEntries = sortUserDataEntries(result);

        sortedEntries.stream()
                .filter(entry -> ROLE_MANAGER.equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    UserData userData = entry.getValue();

                    String orderStatus = "Лиды: " + userData.getLeadsNew()
                            + " В проверку: " + userData.getOrderToCheck()
                            + " На проверке: " + userData.getOrderInCheck()
                            + " Опубликовано: " + userData.getOrderInPublished()
                            + " Выставлен счет: " + userData.getOrderInWaitingPay1()
                            + " Напоминание: " + userData.getOrderInWaitingPay2()
                            + " Не оплачено: " + userData.getOrderNoPay();

                    String orderStatsForWorkers = "\nНовых - " + userData.getNewOrders()
                            + " Коррекция - " + userData.getCorrectOrders()
                            + " Выгул - " + userData.getInVigul()
                            + " Публикация - " + userData.getInPublish();

                    resultBuilder.append(entry.getKey())
                            .append(": ")
                            .append(userData.getSalary())
                            .append(" руб. ")
                            .append("\n\n")
                            .append(orderStatus)
                            .append("\n")
                            .append(orderStatsForWorkers)
                            .append("\n\n");
                });

        sortedEntries.stream()
                .filter(entry -> ROLE_WORKER.equals(entry.getValue().getRole()))
                .forEach(entry -> {
                    UserData userData = entry.getValue();

                    String orderStats = "Новые - " + userData.getNewOrders()
                            + " В коррекции - " + userData.getCorrectOrders()
                            + " В выгуле - " + userData.getInVigul()
                            + " На публикации - " + userData.getInPublish();

                    resultBuilder.append(entry.getKey())
                            .append(": ")
                            .append(userData.getSalary())
                            .append(" руб.  ")
                            .append("\n\n")
                            .append("Статусы заказов:")
                            .append("\n")
                            .append(orderStats)
                            .append("\n\n");
                });

        return resultBuilder.toString();
    }

    private PersonalDataBundle loadPersonalDataBundle(LocalDate localDate) {
        LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
        LocalDate lastDayOfMonth = localDate.withDayOfMonth(localDate.lengthOfMonth());

        Map<String, Pair<String, Long>> zps = zpService.getAllZpToMonthToTelegram(firstDayOfMonth, lastDayOfMonth);
        Map<String, Pair<Long, Long>> pcs = paymentCheckService.getAllPaymentToMonth(firstDayOfMonth, lastDayOfMonth);
        Map<String, Long> newCompanies = companyService.getAllNewCompanies(firstDayOfMonth, lastDayOfMonth);
        Map<String, Pair<Long, Long>> newOrders = orderService.getNewOrderAll(STATUS_NEW, STATUS_CORRECTION);
        Map<String, Pair<Long, Long>> inPublishAndVigul = reviewService.getAllPublishAndVigul(firstDayOfMonth, localDate);
        Map<String, Map<String, Long>> allOrders = orderService.getAllOrdersToMonthByStatus(
                firstDayOfMonth,
                lastDayOfMonth,
                STATUS_NEW,
                STATUS_TO_CHECK,
                STATUS_IN_CHECK,
                STATUS_CORRECTION,
                STATUS_PUBLISHED,
                STATUS_BILL_SENT,
                STATUS_REMINDER,
                STATUS_NOT_PAID
        );
        Map<String, Long> leadsNewAndInWork = leadService.getAllLeadsToMonthToManager(STATUS_NEW, firstDayOfMonth, lastDayOfMonth);

        long zpTotal = zps.values().stream()
                .mapToLong(Pair::getSecond)
                .sum();

        return new PersonalDataBundle(
                zps,
                pcs,
                newCompanies,
                newOrders,
                inPublishAndVigul,
                allOrders,
                leadsNewAndInWork,
                zpTotal
        );
    }

    private Map<String, UserData> buildUserDataMap(PersonalDataBundle bundle) {
        Map<String, UserData> result = new HashMap<>();

        for (Map.Entry<String, Pair<String, Long>> entry : bundle.zps.entrySet()) {
            String fio = entry.getKey();
            Pair<String, Long> pair = entry.getValue();

            Long totalSum = getFirstOrDefault(bundle.pcs, fio, 0L);
            Long newCompanyCount = bundle.newCompanies.getOrDefault(fio, 0L);
            Long inVigul = getFirstOrDefault(bundle.inPublishAndVigul, fio, 0L);
            Long inPublishCount = getSecondOrDefault(bundle.inPublishAndVigul, fio, 0L);
            Long leadsNew = bundle.leadsNewAndInWork.getOrDefault(fio, 0L);

            String role = pair.getFirst();

            Map<String, Long> orders = bundle.allOrders.getOrDefault(fio, Collections.emptyMap());
            Long orderInNew = orders.getOrDefault(STATUS_NEW, 0L);
            Long orderToCheck = orders.getOrDefault(STATUS_TO_CHECK, 0L);
            Long orderInCheck = orders.getOrDefault(STATUS_IN_CHECK, 0L);
            Long orderInCorrect = orders.getOrDefault(STATUS_CORRECTION, 0L);
            Long orderInPublished = orders.getOrDefault(STATUS_PUBLISHED, 0L);
            Long orderInWaitingPay1 = orders.getOrDefault(STATUS_BILL_SENT, 0L);
            Long orderInWaitingPay2 = orders.getOrDefault(STATUS_REMINDER, 0L);
            Long orderNoPay = orders.getOrDefault(STATUS_NOT_PAID, 0L);

            result.put(fio, new UserData(
                    fio,
                    role,
                    pair.getSecond(),
                    totalSum,
                    bundle.zpTotal,
                    newCompanyCount,
                    orderInNew,
                    orderInCorrect,
                    inVigul,
                    inPublishCount,
                    DEFAULT_IMAGE_ID,
                    0L,
                    0L,
                    0L,
                    leadsNew,
                    null,
                    null,
                    orderInNew,
                    orderToCheck,
                    orderInCheck,
                    orderInCorrect,
                    orderInPublished,
                    orderInWaitingPay1,
                    orderInWaitingPay2,
                    orderNoPay
            ));
        }

        return result;
    }

    private List<Map.Entry<String, UserData>> sortUserDataEntries(Map<String, UserData> result) {
        return result.entrySet().stream()
                .sorted((entry1, entry2) -> {
                    UserData user1 = entry1.getValue();
                    UserData user2 = entry2.getValue();

                    boolean isManager1 = ROLE_MANAGER.equals(user1.getRole());
                    boolean isManager2 = ROLE_MANAGER.equals(user2.getRole());

                    if (isManager1 && isManager2) {
                        return Long.compare(user2.getTotalSum(), user1.getTotalSum());
                    }

                    if (!isManager1 && !isManager2) {
                        return Long.compare(user2.getSalary(), user1.getSalary());
                    }

                    return Boolean.compare(isManager2, isManager1);
                })
                .collect(Collectors.toList());
    }

    private long calculateTotalManagerRevenue(Map<String, UserData> result) {
        return result.values().stream()
                .filter(user -> ROLE_MANAGER.equals(user.getRole()))
                .mapToLong(UserData::getTotalSum)
                .sum();
    }

    private long calculateTotalSpecificManagersRevenue(Map<String, UserData> result, Set<String> managerNames) {
        return result.values().stream()
                .filter(user -> ROLE_MANAGER.equals(user.getRole()) && managerNames.contains(user.getFio()))
                .mapToLong(UserData::getTotalSum)
                .sum();
    }

    private long calculateTotalNewCompanies(Map<String, UserData> result) {
        return result.values().stream()
                .filter(user -> ROLE_MANAGER.equals(user.getRole()))
                .mapToLong(UserData::getNewCompanies)
                .sum();
    }

    private long extractTotalZp(Map<String, UserData> result) {
        return result.values().stream()
                .findFirst()
                .map(UserData::getZpTotal)
                .orElse(0L);
    }

    private Map<String, UserData> filterUserDataMap(Map<String, UserData> source, Set<String> allowedFio) {
        return source.entrySet().stream()
                .filter(entry -> allowedFio.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private String escapeMarkdown(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("`", "\\`");
    }

    private <K> Long getFirstOrDefault(Map<K, Pair<Long, Long>> map, K key, Long defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        Pair<Long, Long> pair = map.get(key);
        return pair != null && pair.getFirst() != null ? pair.getFirst() : defaultValue;
    }

    private <K> Long getSecondOrDefault(Map<K, Pair<Long, Long>> map, K key, Long defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        Pair<Long, Long> pair = map.get(key);
        return pair != null && pair.getSecond() != null ? pair.getSecond() : defaultValue;
    }

    private Long safePercent(Long numerator, Long denominator) {
        if (denominator == null || denominator == 0L) {
            return 0L;
        }
        long safeNumerator = numerator == null ? 0L : numerator;
        return (safeNumerator * 100) / denominator;
    }

    private static final class PersonalDataBundle {
        private final Map<String, Pair<String, Long>> zps;
        private final Map<String, Pair<Long, Long>> pcs;
        private final Map<String, Long> newCompanies;
        private final Map<String, Pair<Long, Long>> newOrders;
        private final Map<String, Pair<Long, Long>> inPublishAndVigul;
        private final Map<String, Map<String, Long>> allOrders;
        private final Map<String, Long> leadsNewAndInWork;
        private final long zpTotal;

        private PersonalDataBundle(
                Map<String, Pair<String, Long>> zps,
                Map<String, Pair<Long, Long>> pcs,
                Map<String, Long> newCompanies,
                Map<String, Pair<Long, Long>> newOrders,
                Map<String, Pair<Long, Long>> inPublishAndVigul,
                Map<String, Map<String, Long>> allOrders,
                Map<String, Long> leadsNewAndInWork,
                long zpTotal
        ) {
            this.zps = zps;
            this.pcs = pcs;
            this.newCompanies = newCompanies;
            this.newOrders = newOrders;
            this.inPublishAndVigul = inPublishAndVigul;
            this.allOrders = allOrders;
            this.leadsNewAndInWork = leadsNewAndInWork;
            this.zpTotal = zpTotal;
        }
    }
}

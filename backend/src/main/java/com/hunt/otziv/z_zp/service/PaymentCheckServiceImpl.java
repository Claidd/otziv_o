package com.hunt.otziv.z_zp.service;

import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.z_zp.dto.CheckDTO;
import com.hunt.otziv.z_zp.dto.PaymentCheckStatView;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.repository.PaymentCheckRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentCheckServiceImpl implements PaymentCheckService {

    private final PaymentCheckRepository paymentCheckRepository;
    private final CompanyService companyService;

    public List<PaymentCheck> findAll(){
        return paymentCheckRepository.findAll();
    }

    public List<PaymentCheck> findAllToDate(LocalDate localDate){ // Взять все чеки из БД
        Pair<LocalDate, LocalDate> period = currentAndPreviousYearPeriod(localDate);
        return paymentCheckRepository.findAllToDate(period.getFirst(), period.getSecond());
    } // Взять все чеки из БД

    public List<PaymentCheckStatView> findStatRowsToDate(LocalDate localDate) {
        Pair<LocalDate, LocalDate> period = currentAndPreviousYearPeriod(localDate);
        return paymentCheckRepository.findStatRowsToDate(period.getFirst(), period.getSecond()).stream()
                .map(PaymentCheckStatView.class::cast)
                .toList();
    }

    public List<PaymentCheck> findAllToDateByOwner(LocalDate localDate, Set<Manager> managerList){ // Взять все чеки из БД с определенных менеджеров
        List<Long> managerListLong = managerList.stream().map(Manager::getUser).map(User::getId).toList();
        if (managerListLong.isEmpty()) {
            return List.of();
        }
//        System.out.println("Чеки для менеджеров - " + managerList);
//        System.out.println(paymentCheckRepository.findAllToDateByManagers(localDate, localDate2, managerListLong));
        Pair<LocalDate, LocalDate> period = currentAndPreviousYearPeriod(localDate);
        return paymentCheckRepository.findAllToDateByManagers(period.getFirst(), period.getSecond(), managerListLong);
    } // Взять все чеки из БД с определенных менеджеров

    public List<PaymentCheckStatView> findStatRowsToDateByOwner(LocalDate localDate, Set<Manager> managerList) {
        List<Long> managerListLong = managerList.stream().map(Manager::getUser).map(User::getId).toList();
        if (managerListLong.isEmpty()) {
            return List.of();
        }
        Pair<LocalDate, LocalDate> period = currentAndPreviousYearPeriod(localDate);
        return paymentCheckRepository.findStatRowsToDateByManagers(period.getFirst(), period.getSecond(), managerListLong).stream()
                .map(PaymentCheckStatView.class::cast)
                .toList();
    }

    public List<PaymentCheck> findAllByOwner(Set<Manager> managerList) {
        List<Long> managerIds = managerList.stream().map(Manager::getUser).map(User::getId).toList();
        if (managerIds.isEmpty()) {
            return List.of();
        }
        return paymentCheckRepository.findAllByManagers(managerIds);
    }

    @Override
    public List<PaymentCheck> getAllWorkerPaymentToDate(Long managerId, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        return paymentCheckRepository.getAllWorkerPayments(managerId, firstDayOfMonth, lastDayOfMonth);
    }




    @Override
    public Map<String, Pair<Long, Long>> getAllPaymentToMonth(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        // Получаем карту с суммами чеков
        Map<String, Long> check = paymentCheckRepository.findAllToDateToMap(firstDayOfMonth, lastDayOfMonth)
                .stream()
                .collect(Collectors.toMap(
                        obj -> (String) obj[0],  // ФИО пользователя
                        obj -> ((BigDecimal) obj[1]).longValue(), // Сумма чеков
                        Long::sum, // Если у пользователя несколько чеков, складываем суммы
                        LinkedHashMap::new // Сохраняем порядок сортировки
                ));

        // Получаем карту с количеством новых компаний
        Map<String, Long> newCompanies = companyService.getAllNewCompanies2(firstDayOfMonth, lastDayOfMonth).stream()
                .collect(Collectors.toMap(
                        obj -> (String) obj[0],   // ФИО менеджера
                        obj -> (Long) obj[1]      // Количество компаний
                ));

        // Объединяем две карты в одну с использованием Pair<Long, Long>
        Map<String, Pair<Long, Long>> result = new LinkedHashMap<>();

        // Обрабатываем первую карту (с суммами чеков)
        for (Map.Entry<String, Long> entry : check.entrySet()) {
            String fio = entry.getKey();
            Long totalSum = entry.getValue();

            // Получаем количество новых компаний для этого ФИО из второй карты
            Long newCompaniesCount = newCompanies.getOrDefault(fio, 0L);

            // Добавляем в результат
            result.put(fio, Pair.of(totalSum, newCompaniesCount));
        }

        // Обрабатываем оставшиеся записи из второй карты (если такие есть)
        for (Map.Entry<String, Long> entry : newCompanies.entrySet()) {
            String fio = entry.getKey();

            // Если этого ФИО нет в первой карте, добавляем с суммой чеков 0
            result.putIfAbsent(fio, Pair.of(0L, entry.getValue()));
        }

        return result;
    }







    public List<CheckDTO> getAllCheckDTO(){
        return toDTOList(paymentCheckRepository.findAll());
    }

    @Transactional
    public boolean save(Order order){ // Сохранить Чек в БД
        BigDecimal sum = order != null && order.getSum() != null ? order.getSum() : BigDecimal.ZERO;
        return saveInternal(order, sum, null, null);
    } // Сохранить Чек в БД

    @Transactional
    public boolean save(Order order, BigDecimal sum){ // Сохранить Чек в БД
        return saveInternal(order, sum, null, null);
    }

    @Override
    @Transactional
    public boolean save(Order order, BigDecimal sum, int paidAmount) {
        if (paidAmount < 0) {
            throw new IllegalArgumentException("Количество оплаченных работ не может быть отрицательным");
        }
        return saveInternal(order, sum, paidAmount, PaymentCheckSourceContext.currentPaymentLinkId());
    }

    private boolean saveInternal(Order order, BigDecimal sum, Integer paidAmount, Long paymentLinkId) {
        try {
            if (order == null || order.getId() == null) {
                throw new IllegalArgumentException("Для чека нужен заказ с ID");
            }
            if (order.getStatus() == null
                    || order.getStatus().getId() == null
                    || !"Оплачено".equals(order.getStatus().getTitle())) {
                throw new IllegalStateException("Активный чек можно создать только для оплаченного заказа");
            }
            BigDecimal expected = sum == null ? BigDecimal.ZERO : sum;
            Long expectedManagerId = requiredManagerUserId(order);
            Long expectedWorkerId = requiredWorkerUserId(order);
            Long expectedCompanyId = requiredCompanyId(order);
            Long expectedStatusGuard = order.getStatus().getId();
            List<PaymentCheck> existing = paymentCheckRepository.findByOrderIdAndActiveTrue(order.getId());
            if (!existing.isEmpty()) {
                BigDecimal recorded = existing.stream()
                        .map(PaymentCheck::getSum)
                        .filter(java.util.Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                PaymentCheck onlyCheck = existing.size() == 1 ? existing.getFirst() : null;
                boolean sameFinancialFact = onlyCheck != null
                        && recorded.compareTo(expected) == 0
                        && java.util.Objects.equals(onlyCheck.getManagerId(), expectedManagerId)
                        && java.util.Objects.equals(onlyCheck.getWorkerId(), expectedWorkerId)
                        && java.util.Objects.equals(onlyCheck.getCompanyId(), expectedCompanyId)
                        && java.util.Objects.equals(onlyCheck.getPaymentStatusGuard(), expectedStatusGuard)
                        && java.util.Objects.equals(onlyCheck.getPaidAmount(), paidAmount)
                        && java.util.Objects.equals(onlyCheck.getPaymentLinkId(), paymentLinkId);
                if (sameFinancialFact) {
                    log.info("Активный чек заказа {} уже существует с теми же реквизитами; повтор не создается", order.getId());
                    return true;
                }
                throw new IllegalStateException(
                        "Активный чек заказа " + order.getId()
                                + " не совпадает с текущим финансовым фактом"
                );
            }
            saveCheckCompany(order, sum, paidAmount, paymentLinkId);
            return true;
        }
        catch (Exception e){
            throw new IllegalStateException("Не удалось сохранить чек оплаты заказа", e);
        }
    } // Сохранить Чек в БД

    @Transactional
    protected void saveCheckCompany(Order order){ // Сохранить Чек в БД
        BigDecimal sum = order != null && order.getSum() != null ? order.getSum() : BigDecimal.ZERO;
        saveCheckCompany(order, sum, null, null);
    } // Сохранить Чек в БД

    @Transactional
    protected void saveCheckCompany(Order order, BigDecimal sum){ // Сохранить Чек в БД
        saveCheckCompany(order, sum, null, null);
    }

    private void saveCheckCompany(Order order, BigDecimal sum, Integer paidAmount, Long paymentLinkId) {
        log.info("Зашли в создание чека");
        PaymentCheck paymentCheck = new PaymentCheck();
        paymentCheck.setTitle(order.getCompany().getTitle());
        paymentCheck.setCompanyId(requiredCompanyId(order));
        paymentCheck.setSum(sum);
        paymentCheck.setPaidAmount(paidAmount);
        paymentCheck.setPaymentLinkId(paymentLinkId);
        paymentCheck.setOrderId(order.getId());
        paymentCheck.setPaymentStatusGuard(order.getStatus().getId());
        paymentCheck.setManagerId(requiredManagerUserId(order));
        paymentCheck.setWorkerId(requiredWorkerUserId(order));
        paymentCheck.setActive(true);
//        System.out.println(paymentCheck);
        paymentCheckRepository.save(paymentCheck);
        log.info("Чек сохранен");
    } // Сохранить Чек в БД

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertActiveCheckBoundToPaymentLink(Long orderId, Long paymentLinkId) {
        if (orderId == null || paymentLinkId == null) {
            throw new IllegalArgumentException("Для привязки платежного цикла нужны ID заказа и ссылки");
        }
        List<PaymentCheck> checks = paymentCheckRepository.findByOrderIdAndActiveTrue(orderId);
        if (checks == null || checks.size() != 1) {
            throw new IllegalStateException(
                    "Не удалось привязать платежный цикл заказа " + orderId
                            + ": активных чеков " + (checks == null ? 0 : checks.size())
            );
        }
        PaymentCheck check = checks.getFirst();
        if (!java.util.Objects.equals(check.getPaymentLinkId(), paymentLinkId)) {
            throw new IllegalStateException(
                    "Активный чек заказа " + orderId
                            + " не относится к подтверждаемой платежной ссылке"
            );
        }
    }

    private Long requiredManagerUserId(Order order) {
        if (order == null
                || order.getManager() == null
                || order.getManager().getUser() == null
                || order.getManager().getUser().getId() == null) {
            throw new IllegalStateException("Для чека оплаты не определен менеджер заказа");
        }
        return order.getManager().getUser().getId();
    }

    private Long requiredWorkerUserId(Order order) {
        if (order == null
                || order.getWorker() == null
                || order.getWorker().getUser() == null
                || order.getWorker().getUser().getId() == null) {
            throw new IllegalStateException("Для чека оплаты не определен исполнитель заказа");
        }
        return order.getWorker().getUser().getId();
    }

    private Long requiredCompanyId(Order order) {
        if (order == null || order.getCompany() == null || order.getCompany().getId() == null) {
            throw new IllegalStateException("Для чека оплаты не определена компания заказа");
        }
        return order.getCompany().getId();
    }

    private List<CheckDTO> toDTOList(List<PaymentCheck> paymentCheckList) { // Метод для преобразования из сущности paymentCheck в checkDTO
        return paymentCheckList.stream().map(this::toDTO).collect(Collectors.toList());
    } // Метод для преобразования из сущности paymentCheck в checkDTO

    private CheckDTO toDTO(PaymentCheck paymentCheck) { // Метод для преобразования из сущности paymentCheck в checkDTO
        CheckDTO checkDTO = new CheckDTO();
        checkDTO.setId(paymentCheck.getId());
        checkDTO.setTitle(paymentCheck.getTitle());
        checkDTO.setCompanyId(paymentCheck.getCompanyId());
        checkDTO.setOrderId(paymentCheck.getOrderId());
        checkDTO.setManagerId(paymentCheck.getManagerId());
        checkDTO.setWorkerId(paymentCheck.getWorkerId());
        checkDTO.setCreated(paymentCheck.getCreated());
        checkDTO.setActive(paymentCheck.isActive());
        checkDTO.setSum(paymentCheck.getSum());
        return checkDTO;
    } // Метод для преобразования из сущности paymentCheck в checkDTO


    private PaymentCheck toEntity(CheckDTO checkDTO) { // Метод для преобразования из checkDTO в сущность Zp
        PaymentCheck paymentCheck = new PaymentCheck();
        paymentCheck.setTitle(checkDTO.getTitle());
        paymentCheck.setCompanyId(checkDTO.getCompanyId());
        paymentCheck.setOrderId(checkDTO.getOrderId());
        paymentCheck.setManagerId(checkDTO.getManagerId());
        paymentCheck.setWorkerId(checkDTO.getWorkerId());
        paymentCheck.setCreated(checkDTO.getCreated());
        paymentCheck.setActive(checkDTO.isActive());
        paymentCheck.setSum(checkDTO.getSum());
        return paymentCheck;
    } // Метод для преобразования из checkDTO в сущность Z

    private Pair<LocalDate, LocalDate> currentAndPreviousYearPeriod(LocalDate localDate) {
        LocalDate anchor = localDate == null ? LocalDate.now() : localDate;
        LocalDate start = anchor.minusYears(1).withDayOfYear(1);
        LocalDate endExclusive = anchor.plusDays(1);
        return Pair.of(start, endExclusive);
    }
}

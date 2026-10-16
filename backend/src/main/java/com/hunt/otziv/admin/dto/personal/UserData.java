package com.hunt.otziv.admin.dto.personal;

import com.hunt.otziv.u_users.model.Image;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserData {
    private String fio;        // Имя пользователя
    private String role;        // Роль пользователя
    private Long salary;        // Начислено за выполненные работы (legacy-имя поля API)
    private Long totalSum;      // Сумма чеков
    private Long zpTotal;      // Общая сумма начислений (legacy-имя поля API)
    private Long newCompanies;  // Количество новых компаний
    private Long newOrders;     // Количество новых заказов
    private Long correctOrders;     // Количество в коррекции
    private Long inVigul;     // Количество заказов в Выгуле
    private Long inPublish;     // Количество заказов в публикации
    private Long imageId;
    private Long userId;

    // Для менеджеров и рабочих
    private Long order1Month;
    private Long review1Month;


    // Для операторов и маркетологов
    private Long leadsNew;
    private Long leadsInWork;
    private Long percentInWork;

    // Счетчики заказов
    private Long orderInNew;
    private Long orderToCheck;
    private Long orderInCheck;
    private Long orderInCorrect;
    private Long orderInPublished;
    private Long orderInWaitingPay1;
    private Long orderInWaitingPay2;
    private Long orderNoPay;

    // Текущий прогресс специалиста за день, включая разбивку по рабочим подразделам.
    private DailyWorkProgressResponse dailyProgress;
    private Long badTasks;
    private Long recoveryTasks;

    // Exact monetary values for newer consumers. Legacy salary/zpTotal remain
    // whole-ruble numbers for existing clients.
    private Long salaryKopecks;
    private Long zpTotalKopecks;

    public UserData(
            String fio, String role, Long salary, Long totalSum, Long zpTotal,
            Long newCompanies, Long newOrders, Long correctOrders, Long inVigul,
            Long inPublish, Long imageId, Long userId, Long order1Month,
            Long review1Month, Long leadsNew, Long leadsInWork, Long percentInWork,
            Long orderInNew, Long orderToCheck, Long orderInCheck,
            Long orderInCorrect, Long orderInPublished, Long orderInWaitingPay1,
            Long orderInWaitingPay2, Long orderNoPay,
            DailyWorkProgressResponse dailyProgress, Long badTasks, Long recoveryTasks
    ) {
        this(fio, role, salary, totalSum, zpTotal, newCompanies, newOrders,
                correctOrders, inVigul, inPublish, imageId, userId, order1Month,
                review1Month, leadsNew, leadsInWork, percentInWork, orderInNew,
                orderToCheck, orderInCheck, orderInCorrect, orderInPublished,
                orderInWaitingPay1, orderInWaitingPay2, orderNoPay,
                dailyProgress, badTasks, recoveryTasks, null, null);
    }

}

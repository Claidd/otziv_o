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

}

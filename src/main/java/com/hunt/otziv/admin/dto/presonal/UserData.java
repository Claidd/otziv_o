package com.hunt.otziv.admin.dto.presonal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserData {
    private String role;        // Роль пользователя
    private Long salary;        // Зарплата
    private Long totalSum;      // Сумма чеков
    private Long newCompanies;  // Количество новых компаний
    private Long newOrders;     // Количество новых заказов
    private Long correctOrders;     // Количество в коррекции
    private Long inVigul;     // Количество заказов в Выгуле
    private Long inPublish;     // Количество заказов в публикации
}

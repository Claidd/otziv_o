package com.hunt.otziv.t_telegrambot.dto;

import com.hunt.otziv.admin.dto.presonal.UserData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkerData {
    private UserData userData;
    private Long telegramChatId;

    // Конструктор, геттеры и сеттеры
}

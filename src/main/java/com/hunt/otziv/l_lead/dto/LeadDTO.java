package com.hunt.otziv.l_lead.dto;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadDTO {

    private Long id;

    //    телефон нового лида, который откликнулся на рассылку
    @NotEmpty(message = "Номер не может быть пустым")
    private String telephoneLead;

    //    город по которому шла рассылка
    @NotEmpty(message = "Город не может быть пустым")
    private String cityLead;

    //    город по которому шла рассылка

    private String commentsLead;

    //    текущий статус лида ЕНАМ?????

    private String lidStatus;

    //    время создания пользователя

    private LocalDate createDate;

    //    дата и время обновления статуса

    private LocalDateTime updateStatus;

    //    дата и время нового отправления предложения

    private LocalDate dateNewTry;

    //    привязка юзера-оператора

    private Operator operator;
    private Manager manager;
    private Marketolog marketolog;

    private Long operatorId;

    private boolean offer;

//    ПОЯСНЕНИЕ:
//  Класс LeadDTO является простым Java-классом, который представляет DTO (Data Transfer Object) для класса Lead.
//    В DTO используются те же поля, что и в классе Lead, за исключением ассоциации с объектом User. Вместо этого,
//    в DTO используется поле operatorId, которое хранит идентификатор (id) пользователя (User), связанного с лидом.
//    Вы можете добавить необходимые конструкторы, методы и аннотации в соответствии с вашими требованиями.
//    Использование DTO позволяет отделить внутреннее представление сущности Lead от данных, передаваемых вне системы
//    (например, в HTTP-запросах или ответах). Это помогает избежать утечек данных и ненужных зависимостей между слоями
//    приложения.



}

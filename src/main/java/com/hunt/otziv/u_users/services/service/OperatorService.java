package com.hunt.otziv.u_users.services.service;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import org.springframework.data.jpa.repository.Query;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public interface OperatorService {

    // Взять опретора по id его юзера
    Operator getOperatorById (Long id);

    // взять опретора по id го юзера
    Operator getOperatorByUserId (Long id);

    // взять из БД всех опреторов
    List<Operator> getAllOperators();

    // удалить опретора по id оператора и юзера внутри записи
    void delete(Long userId, Long operatorId);

    // сохранить нового опретора
    void saveNewOperator(User user);

    // взять оператора по id перед его удалением
    Operator getOperatorByUserIdToDelete(Long id);

    // Удалить определенного опретора по юзеру, если такой есть в таблице
    void deleteOperator(User user);


    List<Operator> getAllOperatorsToManager(Manager manager);

    void save(Operator operator);

    Operator getOperatorByTelephoneId(Long telephoneId);

//    @Query("SELECT o FROM Operator o JOIN o.telephones t WHERE t.id = :telephoneId")
//    Operator getOperatorByTelephoneId(Long TelephoneId);
}

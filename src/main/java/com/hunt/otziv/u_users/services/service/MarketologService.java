package com.hunt.otziv.u_users.services.service;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public interface MarketologService {

    // Взять опретора по id его юзера
    Marketolog getMarketologById (Long id);

    // взять опретора по id го юзера
    Marketolog getMarketologByUserId (Long id);

    // взять из БД всех опреторов
    List<Marketolog> getAllMarketologs();

    // удалить опретора по id оператора и юзера внутри записи
    void delete(Long userId, Long marketologId);

    // сохранить нового опретора
    void saveNewMarketolog(User user);

    // взять оператора по id перед его удалением
    Marketolog getMarketologByUserIdToDelete(Long id);

    // Удалить определенного опретора по юзеру, если такой есть в таблице
    void deleteMarketolog(User user);
    List<Marketolog> getAllMarketologsToManager(Manager manager);


    List<Marketolog> getAllMarketologsToOwner(List<Manager> managers);
}

package com.hunt.otziv.u_users.services;

import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.MarketologRepository;
import com.hunt.otziv.u_users.repository.OperatorRepository;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.MarketologService;
import com.hunt.otziv.u_users.services.service.OperatorService;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@Slf4j
public class MarketologServiceImpl implements MarketologService {
    private final MarketologRepository marketologRepository;

    public MarketologServiceImpl(MarketologRepository marketologRepository) {
        this.marketologRepository = marketologRepository;
    }

    @Override
    public Marketolog getMarketologById(Long id) { // Взять маркетолога по Id
        return marketologRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("User not found with id: " + id));
    } // Взять маркетолога по Id

    @Override
    public Marketolog getMarketologByUserId(Long id) { // Взять маркетолога по Id юзера
        return marketologRepository.findByUserId(id).orElse(null);
    } // Взять маркетолога по Id юзера

    @Override
    public Set<Marketolog> getAllMarketologs() { // Взять всех маркетологов
        return marketologRepository.findAll();
    } // Взять всех маркетологов

    @Override
    public void delete(Long userId, Long marketologId) {
    }

    @Override
    public void saveNewMarketolog(User user) { // Сохранить нового маркетолога
        if (marketologRepository.findByUserId(user.getId()).isPresent()){
            log.info("Не добавили оператора так как уже в списке");
        }
        else {
            log.info("начали добавлять оператора так как нет в списке");
            Marketolog marketolog = new Marketolog();
            marketolog.setUser(user);
            marketologRepository.save(marketolog);
            log.info("Добавили оператора");
        }
    } // Сохранить нового маркетолога

    @Override
    public Marketolog getMarketologByUserIdToDelete(Long id) { // Найти маркетолога для удаления
        return marketologRepository.findByUserId(id).orElse(null);
    } // Найти маркетолога для удаления

    @Override
    public void deleteMarketolog(User user) { // Удалить маркетолога
        log.info("Вошли в проверку при удалении есть ли такой маркетолог при смене роли");
        Marketolog marketolog = getMarketologByUserIdToDelete(user.getId());
        log.info("Достали маркетолога");
        if (marketolog != null){
            marketologRepository.delete(marketolog);
            log.info("Удалили маркетолога");
        }
        else {
            log.info("Не удалили маркетолога так как такого нет в списке");
        }
    } // Удалить маркетолога

}

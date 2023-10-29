package com.hunt.otziv.u_users.services;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.OperatorRepository;
import com.hunt.otziv.u_users.services.service.OperatorService;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class OperatorServiceImpl implements OperatorService {
    private final OperatorRepository operatorRepository;

    public OperatorServiceImpl(OperatorRepository operatorRepository) {
        this.operatorRepository = operatorRepository;
    }

    @Override
    public Operator getOperatorById(Long id) { // Взять оператора по Id
        return operatorRepository.findById(id)
           .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + id));
    } // Взять оператора по Id

    @Override
    public Operator getOperatorByUserId(Long id) { // Взять оператора по Id юзера
        return operatorRepository.findByUserId(id).orElse(null);
    } // Взять оператора по Id юзера

    @Override
    public List<Operator> getAllOperators() {
        return operatorRepository.findAllOperators();
    } // Взять всех операторов

    @Override
    public void delete(Long userId, Long operatorId) {
    }

    @Override
    public void saveNewOperator(User user) { // Сохранить нового оператора
        if (operatorRepository.findByUserId(user.getId()).isPresent()){
            log.info("Не добавили оператора так как уже в списке");
        }
        else {
            log.info("начали добавлять оператора так как нет в списке");
            Operator operator = new Operator();
            operator.setUser(user);
            operatorRepository.save(operator);
            log.info("Добавили оператора");
        }
    } // Сохранить нового оператора

    @Override
    public Operator getOperatorByUserIdToDelete(Long id) { // Найти оператора для удаления
        return operatorRepository.findByUserId(id).orElse(null);
    } // Найти оператора для удаления

    @Override
    public void deleteOperator(User user) { // Удалить оператора
        log.info("Вошли в проверку при удалении есть ли такой оператор при смене роли");
        Operator operator = getOperatorByUserIdToDelete(user.getId());
        log.info("Достали опертора");
        if (operator != null){
            operatorRepository.delete(operator);
            log.info("Удалили оператора");
        }
        else {
            log.info("Не удалили оператора так как такого нет в списке");
        }
    } // Удалить оператора

    @Override
    public List<Operator> getAllOperatorsToManager(Manager manager) {
        return operatorRepository.findAllToManagerOperators(manager.getUser().getOperators());
    }

}

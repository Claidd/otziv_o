package com.hunt.otziv.u_users.services;

import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.OperatorRepository;
import com.hunt.otziv.u_users.services.service.OperatorService;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class OperatorServiceImpl implements OperatorService {
    private final OperatorRepository operatorRepository;

    public OperatorServiceImpl(OperatorRepository operatorRepository) {
        this.operatorRepository = operatorRepository;
    }

    @Override
    public Operator getOperatorById(Long id) {
        return operatorRepository.findById(id)
           .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + id));
    }

    @Override
    public Operator getOperatorByUserId(Long id) {
        return operatorRepository.findByUserId(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + id));
    }

    @Override
    public List<Operator> getAllOperators() {
        return operatorRepository.findAll();
    }

    @Override
    public void delete(Long userId, Long operatorId) {

    }

    @Override
    public void saveNewOperator(User user) {
//        System.out.println(operatorRepository.findById(user.getId()).isPresent());
//        System.out.println(operatorRepository.findByUserId(user.getId()).isPresent());
//        System.out.println(user.getId());
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
    }

    @Override
    public Operator getOperatorByUserIdToDelete(Long id) {
        return operatorRepository.findByUserId(id).orElse(null);
    }

    @Override
    public void deleteOperator(User user) {
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
    }
}

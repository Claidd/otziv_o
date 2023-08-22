package com.hunt.otziv.u_users.services.service;

import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.User;

import java.util.List;

public interface OperatorService {

    Operator getOperatorById (Long id);
    Operator getOperatorByUserId (Long id);
    List<Operator> getAllOperators();

    void delete(Long userId, Long operatorId);

    void saveNewOperator(User user);

    Operator getOperatorByUserIdToDelete(Long id);

    void deleteOperator(User user);
}

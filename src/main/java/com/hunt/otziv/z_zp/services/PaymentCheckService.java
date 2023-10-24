package com.hunt.otziv.z_zp.services;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.z_zp.dto.CheckDTO;
import com.hunt.otziv.z_zp.model.PaymentCheck;

import java.util.List;

public interface PaymentCheckService {

    boolean save(Order order);
    List<CheckDTO> getAllCheckDTO();
    List<PaymentCheck> findAll();
}

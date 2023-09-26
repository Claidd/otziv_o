package com.hunt.otziv.z_zp.services;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.z_zp.dto.ZpDTO;

import java.util.List;

public interface ZpService {

    boolean save(Order order);
    List<ZpDTO> getAllZpDTO();
    boolean saveLeadZp(Lead lead);
}

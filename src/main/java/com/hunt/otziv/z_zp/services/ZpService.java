package com.hunt.otziv.z_zp.services;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.z_zp.dto.ZpDTO;
import com.hunt.otziv.z_zp.model.Zp;

import java.util.List;

public interface ZpService {

    boolean save(Order order);
    List<ZpDTO> getAllZpDTO();
    boolean saveLeadZp(Lead lead);
    List<Zp> getAllWorkerZp(String login);
}

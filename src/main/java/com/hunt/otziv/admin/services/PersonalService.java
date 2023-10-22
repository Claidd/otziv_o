package com.hunt.otziv.admin.services;

import com.hunt.otziv.admin.dto.ManagersListDTO;
import com.hunt.otziv.admin.dto.MarketologsListDTO;
import com.hunt.otziv.admin.dto.OperatorsListDTO;
import com.hunt.otziv.admin.dto.WorkersListDTO;

import java.util.List;

public interface PersonalService {
    List<WorkersListDTO> gerWorkers();
    List<MarketologsListDTO> getMarketologs();
    List<ManagersListDTO> getManagers();
    List<OperatorsListDTO> gerOperators();

}

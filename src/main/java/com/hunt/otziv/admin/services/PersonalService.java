package com.hunt.otziv.admin.services;

import com.hunt.otziv.admin.dto.personal_stat.StatDTO;
import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.admin.dto.presonal.ManagersListDTO;
import com.hunt.otziv.admin.dto.presonal.MarketologsListDTO;
import com.hunt.otziv.admin.dto.presonal.OperatorsListDTO;
import com.hunt.otziv.admin.dto.presonal.WorkersListDTO;

import java.util.List;

public interface PersonalService {
    List<WorkersListDTO> gerWorkers();
    List<MarketologsListDTO> getMarketologs();
    List<ManagersListDTO> getManagers();
    List<OperatorsListDTO> gerOperators();
    StatDTO getStats();
    UserStatDTO getWorkerReviews(String login);

}

package com.hunt.otziv.admin.services;

import com.hunt.otziv.admin.dto.ManagersListDTO;
import com.hunt.otziv.admin.dto.MarketologsListDTO;
import com.hunt.otziv.admin.dto.OperatorsListDTO;
import com.hunt.otziv.admin.dto.WorkersListDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PersonalServiceImpl implements PersonalService {
    private final ManagerService managerService;
    private final MarketologService marketologService;
    private final WorkerService workerService;
    private final OperatorService operatorService;


    public List<ManagersListDTO> getManagers(){
        return managerService.getAllManagers().stream().map(this::toManagersListDTO).collect(Collectors.toList());
    }
    public List<MarketologsListDTO> getMarketologs(){
        return marketologService.getAllMarketologs().stream().map(this::toMarketologsListDTO).collect(Collectors.toList());
    }
    public List<WorkersListDTO> gerWorkers(){
        return workerService.getAllWorkers().stream().map(this::toWorkersListDTO).collect(Collectors.toList());
    }
    public List<OperatorsListDTO> gerOperators(){
        return operatorService.getAllOperators().stream().map(this::toOperatorsListDTO).collect(Collectors.toList());
    }

    private ManagersListDTO toManagersListDTO(Manager manager){
        Long imageId = manager.getUser().getImage() != null ? manager.getUser().getImage().getId() : 1L;
        return ManagersListDTO.builder()
                .id(manager.getId())
                .userId(manager.getUser().getId())
                .fio(manager.getUser().getFio())
                .imageId(imageId)
                .build();
    }

    private MarketologsListDTO toMarketologsListDTO(Marketolog marketolog){
        Long imageId = marketolog.getUser().getImage() != null ? marketolog.getUser().getImage().getId() : 1L;
        return MarketologsListDTO.builder()
                .id(marketolog.getId())
                .userId(marketolog.getUser().getId())
                .fio(marketolog.getUser().getFio())
                .imageId(imageId)
                .build();
    }

    private WorkersListDTO toWorkersListDTO(Worker worker){
        Long imageId = worker.getUser().getImage() != null ? worker.getUser().getImage().getId() : 1L;
        return WorkersListDTO.builder()
                .id(worker.getId())
                .userId(worker.getUser().getId())
                .fio(worker.getUser().getFio())
                .imageId(imageId)
                .build();
    }

    private OperatorsListDTO toOperatorsListDTO(Operator operator){
        Long imageId = operator.getUser().getImage() != null ? operator.getUser().getImage().getId() : 1L;
        return OperatorsListDTO.builder()
                .id(operator.getId())
                .userId(operator.getUser().getId())
                .fio(operator.getUser().getFio())
                .imageId(imageId)
                .build();
    }


}

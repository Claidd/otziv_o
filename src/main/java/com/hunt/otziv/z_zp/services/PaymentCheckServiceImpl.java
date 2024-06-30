package com.hunt.otziv.z_zp.services;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.z_zp.dto.CheckDTO;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.repository.PaymentCheckRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentCheckServiceImpl implements PaymentCheckService {

    private final PaymentCheckRepository paymentCheckRepository;

    public List<PaymentCheck> findAll(){
        return paymentCheckRepository.findAll();
    }

    public List<PaymentCheck> findAllToDate(LocalDate localDate){ // Взять все чеки из БД
        LocalDate localDate2 = localDate.minusYears(1);
        return paymentCheckRepository.findAllToDate(localDate, localDate2);
    } // Взять все чеки из БД

    public List<PaymentCheck> findAllToDateByOwner(LocalDate localDate, Set<Manager> managerList){ // Взять все чеки из БД с определенных менеджеров
        LocalDate localDate2 = localDate.minusYears(1);
        List<Long> managerListLong = managerList.stream().map(Manager::getUser).map(User::getId).toList();
//        System.out.println("Чеки для менеджеров - " + managerList);
//        System.out.println(paymentCheckRepository.findAllToDateByManagers(localDate, localDate2, managerListLong));
        return paymentCheckRepository.findAllToDateByManagers(localDate, localDate2, managerListLong);
    } // Взять все чеки из БД с определенных менеджеров

    public List<CheckDTO> getAllCheckDTO(){
        return toDTOList(paymentCheckRepository.findAll());
    }

    @Transactional
    public boolean save(Order order){ // Сохранить Чек в БД
        try {
            saveCheckCompany(order);
            return true;
        }
        catch (Exception e){
            return false;
        }
    } // Сохранить Чек в БД

    @Transactional
    protected void saveCheckCompany(Order order){ // Сохранить Чек в БД
        log.info("Зашли в создание чека");
        System.out.println(order.getSum());
        PaymentCheck paymentCheck = new PaymentCheck();
        paymentCheck.setTitle(order.getCompany().getTitle());
        paymentCheck.setCompanyId(order.getCompany().getId());
        paymentCheck.setSum(order.getSum());
        paymentCheck.setOrderId(order.getId());
        paymentCheck.setManagerId(order.getManager().getUser().getId());
        paymentCheck.setWorkerId(order.getManager().getUser().getId());
        paymentCheck.setActive(true);
//        System.out.println(paymentCheck);
        paymentCheckRepository.save(paymentCheck);
        log.info("Чек сохранен");
    } // Сохранить Чек в БД

    private List<CheckDTO> toDTOList(List<PaymentCheck> paymentCheckList) { // Метод для преобразования из сущности paymentCheck в checkDTO
        return paymentCheckList.stream().map(this::toDTO).collect(Collectors.toList());
    } // Метод для преобразования из сущности paymentCheck в checkDTO

    private CheckDTO toDTO(PaymentCheck paymentCheck) { // Метод для преобразования из сущности paymentCheck в checkDTO
        CheckDTO checkDTO = new CheckDTO();
        checkDTO.setId(paymentCheck.getId());
        checkDTO.setTitle(paymentCheck.getTitle());
        checkDTO.setCompanyId(paymentCheck.getCompanyId());
        checkDTO.setOrderId(paymentCheck.getOrderId());
        checkDTO.setManagerId(paymentCheck.getManagerId());
        checkDTO.setWorkerId(paymentCheck.getWorkerId());
        checkDTO.setCreated(paymentCheck.getCreated());
        checkDTO.setActive(paymentCheck.isActive());
        checkDTO.setSum(paymentCheck.getSum());
        return checkDTO;
    } // Метод для преобразования из сущности paymentCheck в checkDTO


    private PaymentCheck toEntity(CheckDTO checkDTO) { // Метод для преобразования из checkDTO в сущность Zp
        PaymentCheck paymentCheck = new PaymentCheck();
        paymentCheck.setTitle(checkDTO.getTitle());
        paymentCheck.setCompanyId(checkDTO.getCompanyId());
        paymentCheck.setOrderId(checkDTO.getOrderId());
        paymentCheck.setManagerId(checkDTO.getManagerId());
        paymentCheck.setWorkerId(checkDTO.getWorkerId());
        paymentCheck.setCreated(checkDTO.getCreated());
        paymentCheck.setActive(checkDTO.isActive());
        paymentCheck.setSum(checkDTO.getSum());
        return paymentCheck;
    } // Метод для преобразования из checkDTO в сущность Z
}

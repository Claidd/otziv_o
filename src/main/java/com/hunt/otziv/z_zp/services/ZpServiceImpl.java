package com.hunt.otziv.z_zp.services;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import com.hunt.otziv.z_zp.dto.ZpDTO;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ZpServiceImpl implements ZpService{

    private final ZpRepository zpRepository;
    private final UserService userService;

    public List<Zp> getAllWorkerZp(String login){
        LocalDate localDate = LocalDate.now();
        Long userId = userService.findByUserName(login).orElseThrow().getId();
        return zpRepository.getAllWorkerZp(userId, localDate);
    }

    public List<Zp> getAllWorkerZpToDate(String login, LocalDate localDate){
        Long userId = userService.findByUserName(login).orElseThrow().getId();
        return zpRepository.getAllWorkerZp(userId, localDate);
    }

    public List<Zp> findAll(){
        return zpRepository.findAll();
    }

    public List<Zp> findAllToDate(LocalDate localDate){
        LocalDate localDate2 = localDate.minusYears(1);
        return zpRepository.findAllToDate(localDate, localDate2);
    }

    public List<ZpDTO> getAllZpDTO(){
        return toDTOList(zpRepository.findAll());
    }

    @Transactional
    public boolean save(Order order) { // Сохранить ЗП и Чек в БД
        try {
            saveZpManager(order);
            saveZpWorker(order);
            return true;
        } catch (Exception e) {
            log.error("Ошибка при сохранении ЗП и Чека в БД", e);
            throw new RuntimeException("Ошибка при сохранении ЗП и Чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
        }
    }// Сохранить ЗП и Чек в БД

    @Transactional
    public boolean saveLeadZp(Lead lead){ // Сохранить ЗП за Лида
        try {
            saveZpMarketolog(lead);
            saveZpOperator(lead);
            return true;
        }
        catch (Exception e){
            throw new RuntimeException("Ошибка при сохранении ЗП и Чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
        }
    } // Сохранить ЗП за Лида

    @Transactional
    protected void saveZpManager(Order order){ // Сохранить ЗП Менеджера в БД
        try {
            Zp managerZp = new Zp();
            managerZp.setFio(order.getManager().getUser().getFio());
            managerZp.setSum(order.getSum().multiply(order.getManager().getUser().getCoefficient()));
            managerZp.setOrderId(order.getId());
            managerZp.setUserId(order.getManager().getUser().getId());
            managerZp.setProfessionId(order.getManager().getId());
            managerZp.setAmount(order.getAmount());
            managerZp.setActive(true);
            zpRepository.save(managerZp);
        } catch (Exception e){
            throw new RuntimeException("Ошибка при сохранении ЗП и Чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
        }

    } // Сохранить ЗП Менеджера в БД
    @Transactional
    protected void saveZpWorker(Order order){ // Сохранить ЗП Работника в БД
        try {
            Zp workerZp = new Zp();
            workerZp.setFio(order.getWorker().getUser().getFio());
            workerZp.setSum(order.getSum().multiply(order.getWorker().getUser().getCoefficient()));
            workerZp.setOrderId(order.getId());
            workerZp.setUserId(order.getWorker().getUser().getId());
            workerZp.setProfessionId(order.getWorker().getId());
            workerZp.setAmount(order.getAmount());
            workerZp.setActive(true);
            zpRepository.save(workerZp);
        } catch (Exception e){
            throw new RuntimeException("Ошибка при сохранении ЗП и Чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
        }

    } // Сохранить ЗП Работника в БД
    @Transactional
    protected void saveZpMarketolog(Lead lead){ // Сохранить ЗП Маркетолога в БД
        try {
            Zp marketologZp = new Zp();
            marketologZp.setFio(lead.getMarketolog().getUser().getFio());
            marketologZp.setSum(new BigDecimal("1000.00").multiply(lead.getMarketolog().getUser().getCoefficient()));
            marketologZp.setUserId(lead.getMarketolog().getUser().getId());
            marketologZp.setOrderId(0L);
            marketologZp.setProfessionId(lead.getMarketolog().getId());
            marketologZp.setAmount(1);
            marketologZp.setActive(true);
            zpRepository.save(marketologZp);
        } catch (Exception e){
            throw new RuntimeException("Ошибка при сохранении ЗП и Чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
        }

    } // Сохранить ЗП Маркетолога в БД

    @Transactional
    protected void saveZpOperator(Lead lead){ // Сохранить ЗП Оператора в БД
        try {
            Zp operatorZp = new Zp();
            operatorZp.setFio(lead.getOperator().getUser().getFio());
            operatorZp.setSum(new BigDecimal("1000.00").multiply(lead.getOperator().getUser().getCoefficient()));
            operatorZp.setUserId(lead.getOperator().getUser().getId());
            operatorZp.setProfessionId(lead.getOperator().getId());
            operatorZp.setOrderId(0L);
            operatorZp.setAmount(1);
            operatorZp.setActive(true);
            zpRepository.save(operatorZp);
        } catch (Exception e){
            throw new RuntimeException("Ошибка при сохранении ЗП и Чека в БД", e); // выбрасываем исключение, чтобы откатить транзакцию
        }

    } // Сохранить ЗП Оператора в БД

    private List<ZpDTO> toDTOList(List<Zp> zpList) { // Метод для преобразования из сущности Zp в ZpDTO
        return zpList.stream().map(this::toDTO).collect(Collectors.toList());
    } // Метод для преобразования из сущности Zp в ZpDTO

    private ZpDTO toDTO(Zp zp) { // Метод для преобразования из сущности Zp в ZpDTO
        ZpDTO zpDTO = new ZpDTO();
        zpDTO.setId(zp.getId());
        zpDTO.setFio(zp.getFio());
        zpDTO.setUserId(zp.getUserId());
        zpDTO.setProfessionId(zp.getProfessionId());
        zpDTO.setOrderId(zp.getOrderId());
        zpDTO.setCreated(zp.getCreated());
        zpDTO.setActive(zp.isActive());
        zpDTO.setAmount(zp.getAmount());
        zpDTO.setSum(zp.getSum());
        return zpDTO;
    } // Метод для преобразования из сущности Zp в ZpDTO


    private Zp toEntity(ZpDTO zpDTO) { // Метод для преобразования из ZpDTO в сущность Zp
        Zp zp = new Zp();
        zp.setFio(zpDTO.getFio());
        zp.setUserId(zpDTO.getUserId());
        zp.setOrderId(zpDTO.getOrderId());
        zp.setProfessionId(zpDTO.getProfessionId());
        zp.setCreated(zpDTO.getCreated());
        zp.setActive(zpDTO.isActive());
        zp.setAmount(zpDTO.getAmount());
        zp.setSum(zpDTO.getSum());
        return zp;
    } // Метод для преобразования из ZpDTO в сущность Zp
}

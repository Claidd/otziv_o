package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.model.LeadStatus;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.MarketologService;
import com.hunt.otziv.u_users.services.service.OperatorService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.z_zp.services.ZpService;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LeadServiceImpl implements LeadService{

    private final LeadsRepository leadsRepository;
    private final UserRepository userRepository;
    private final ManagerService managerService;
    private final OperatorService operatorService;
    private final MarketologService marketologService;
    private final ZpService zpService;
    private final UserService userService;

    public LeadServiceImpl(LeadsRepository leadsRepository, UserRepository userRepository, ManagerService managerService, OperatorService operatorService, MarketologService marketologService, ZpService zpService, UserService userService) {
        this.leadsRepository = leadsRepository;
        this.userRepository = userRepository;
        this.managerService = managerService;
        this.operatorService = operatorService;
        this.marketologService = marketologService;
        this.zpService = zpService;
        this.userService = userService;
    }

    //    =============================== СОХРАНИТЬ ЮЗЕРА - НАЧАЛО =========================================
    // Создание нового пользователя "Лида" - начало
    public Lead save(LeadDTO leadDTO, String username){
        log.info("3. Заходим в создание нового лида и проверяем совпадение паролей");
        User user = findByUserName(username).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", username)
        ));
        Lead lead = Lead.builder()
                .telephoneLead(changeNumberPhone(leadDTO.getTelephoneLead()))
                .cityLead(leadDTO.getCityLead())
                .commentsLead(leadDTO.getCommentsLead())
                .lidStatus(LeadStatus.NEW.title)
                .operator(operatorService.getOperatorByUserId(user.getId()) != null ? operatorService.getOperatorByUserId(user.getId()) : user.getOperators().iterator().next())
                .marketolog(marketologService.getMarketologByUserId(user.getId()) != null ? marketologService.getMarketologByUserId(user.getId()) : user.getMarketologs().iterator().next())
                .manager(user.getManagers().iterator().hasNext() ? user.getManagers().iterator().next() : null)
                .build();
        log.info("5. Юзер успешно создан");
//        this.save(user);
        Lead lead1 = leadsRepository.save(lead);
        zpService.saveLeadZp(lead1);
        return lead1;
    }
    // Создание нового пользователя "Клиент" - конец
    //    =============================== СОХРАНИТЬ ЮЗЕРА - КОНЕЦ =========================================

    //    =============================== ОБНОВИТЬ ЮЗЕРА - НАЧАЛО =========================================
    // Обновить профиль юзера - начало
    @Override
    @Transactional
    public void updateProfile(LeadDTO leadDTO, Long id) {
        log.info("Вошли в обновление лида и ищем лида по id");
        /*Ищем пользоваеля, если пользователь не найден, то выбрасываем сообщение с ошибкой*/
        Lead saveLead = findByIdAndToUpdate(id).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель с номером '%s' не найден", leadDTO.getTelephoneLead())
        ));
        log.info("Достали лида по ид из дто");
        boolean isChanged = false;

        /*Проверяем не равен ли телефон предыдущему, если нет, то меняем флаг на тру*/
        if (!Objects.equals(leadDTO.getTelephoneLead(), saveLead.getTelephoneLead())){
//            saveUser.setRoles(List.of(roleService.getUserRole(role)));
            saveLead.setTelephoneLead(leadDTO.getTelephoneLead());
            isChanged = true;
            log.info("Обновили телефон");
        }
        /*Проверяем, не равен ли пароль предыдущему */
//        if (userDTO.getPassword() != null && !userDTO.getPassword().isEmpty()){
//            saveUser.setPassword(passwordEncoder.encode(userDTO.getPassword()));
//            isChanged = true;
//        }
        /*Проверяем не равен ли мейл предыдущему, если нет, то меняем флаг на тру*/
        if (!Objects.equals(leadDTO.getCityLead(), saveLead.getCityLead())){
            saveLead.setCityLead(leadDTO.getCityLead());
            isChanged = true;
            log.info("Обновили город");
        }
        /*Проверяем не равен ли мейл предыдущему, если нет, то меняем флаг на тру*/
        if (!Objects.equals(leadDTO.getCommentsLead(), saveLead.getCommentsLead())){
            saveLead.setCommentsLead(leadDTO.getCommentsLead());
            isChanged = true;
            log.info("Обновили комментарий");
        }
        /*Проверяем не равен ли апдейт время предыдущему, если нет, то меняем флаг на тру*/
        if (!Objects.equals(leadDTO.getUpdateStatus(), saveLead.getUpdateStatus())){
            saveLead.setUpdateStatus(leadDTO.getUpdateStatus());
            isChanged = true;
            log.info("Обновили дату изменения");
        }
        /*Проверяем не равен ли апдейт оператора, если нет, то меняем флаг на тру*/
        if (!Objects.equals(leadDTO.getOperator(), saveLead.getOperator())){
            System.out.println(leadDTO.getOperator());
            System.out.println(saveLead.getOperator());
            saveLead.setOperator(leadDTO.getOperator());
            isChanged = true;
            log.info("Обновили оператора");
        }
        /*Проверяем не равен ли апдейт оператора, если нет, то меняем флаг на тру*/
        if (!Objects.equals(leadDTO.getMarketolog(), saveLead.getMarketolog())){
            System.out.println(leadDTO.getMarketolog());
            System.out.println(saveLead.getMarketolog());
            saveLead.setMarketolog(leadDTO.getMarketolog());
            isChanged = true;
            log.info("Обновили маркетолога");
        }
        /*Проверяем не равен ли апдейт менеджера, если нет, то меняем флаг на тру*/
        if (!Objects.equals(leadDTO.getManager(), saveLead.getManager())){
            System.out.println(leadDTO.getManager());
            System.out.println(saveLead.getManager());
            saveLead.setManager(leadDTO.getManager());
            isChanged = true;
            log.info("Обновили менеджера");
        }
        /*если какое-то изменение было и флаг сменился на тру, то только тогда мы изменяем запись в БД
         * А если нет, то и обращаться к базе данны и грузить ее мы не будем*/
        if  (isChanged){
            log.info("Начали сохранять обновленного лида в БД");
            leadsRepository.save(saveLead);
            log.info("Сохранили обновленного лида в БД");
        }
        else {
            log.info("Изменений не было, лид в БД не изменена");
        }
    }

    public Optional<User> findByFio(String operator) {
        return userRepository.findByFio(operator);
    }
    // Обновить профиль юзера - конец
    //    =============================== ОБНОВИТЬ ЮЗЕРА - КОНЕЦ =========================================




    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ - НАЧАЛО =========================================
    // Взять всех юзеров - начало
    @Override
    public List<LeadDTO> getAllLeads(String status, String keywords, Principal principal) {
        log.info("Берем все лиды");
        String userRole = gerRole(principal);
        System.out.println(userRole);
        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            if (!keywords.isEmpty()){
                return leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCase(status, keywords).stream()
                        .map(this::toDto)
                        .filter(lead -> lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
                        .sorted(Comparator.comparing(LeadDTO::getCreateDate).reversed())
                        .collect(Collectors.toList());
            }
            else return leadsRepository.findAllByLidStatus(status).stream()
                    .map(this::toDto)
                    .filter(lead -> lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
                    .sorted(Comparator.comparing(LeadDTO::getCreateDate).reversed())
                    .collect(Collectors.toList());
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            Manager manager = managerService.getManagerByUserId(userService.findByUserName(principal.getName()).orElseThrow().getId());
            if (!keywords.isEmpty()){
                return leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManager(status, keywords, manager).stream()
                        .map(this::toDto)
                        .filter(lead -> lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
                        .sorted(Comparator.comparing(LeadDTO::getCreateDate).reversed())
                        .collect(Collectors.toList());
            }
            else return leadsRepository.findAllByLidStatusAndManager(status, manager).stream()
                    .map(this::toDto)
                    .filter(lead -> lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
                    .sorted(Comparator.comparing(LeadDTO::getCreateDate).reversed())
                    .collect(Collectors.toList());
        }
        if ("ROLE_MARKETOLOG".equals(userRole)){
            log.info("Зашли список всех заказов для Маркетолога");
            Marketolog marketolog = marketologService.getMarketologById(userService.findByUserName(principal.getName()).orElseThrow().getId());
            if (!keywords.isEmpty()){
                return leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndMarketolog(status, keywords, marketolog).stream()
                        .map(this::toDto)
                        .filter(lead -> lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
                        .sorted(Comparator.comparing(LeadDTO::getCreateDate).reversed())
                        .collect(Collectors.toList());
            }
            else return leadsRepository.findAllByLidStatusAndMarketolog(status, marketolog).stream()
                    .map(this::toDto)
                    .filter(lead -> lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
                    .sorted(Comparator.comparing(LeadDTO::getCreateDate).reversed())
                    .collect(Collectors.toList());
        }
        else {
           return new ArrayList<LeadDTO>();
        }
    }
    // Взять всех юзеров - конец
    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ - КОНЕЦ =========================================

    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ ПО ДАТЕ В НАПОМИНАНИИ - НАЧАЛО =========================================
    // Взять всех юзеров - начало
    @Override
    public List<LeadDTO> getAllLeadsToDateReSend(String status, String keywords, Principal principal) {
        log.info("Берем все лиды");
        String userRole = gerRole(principal);
        System.out.println(userRole);
        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            if (!keywords.isEmpty()){
                return leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCase(status, keywords).stream()
                        .map(this::toDto)
                        .filter(lead -> lead.getDateNewTry().isEqual(LocalDate.now()) || lead.getDateNewTry().isBefore(LocalDate.now()))
                        .sorted(Comparator.comparing(LeadDTO::getDateNewTry))
                        .collect(Collectors.toList());
            }
            else return leadsRepository.findAllByLidStatus(status).stream()
                    .map(this::toDto)
                    .filter(lead -> lead.getDateNewTry().isEqual(LocalDate.now()) || lead.getDateNewTry().isBefore(LocalDate.now()))
                    .sorted(Comparator.comparing(LeadDTO::getDateNewTry))
                    .collect(Collectors.toList());
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для менеджера");
            Manager manager = managerService.getManagerByUserId(userService.findByUserName(principal.getName()).orElseThrow().getId());
            if (!keywords.isEmpty()){
                return leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManager(status, keywords, manager).stream()
                        .map(this::toDto)
                        .filter(lead -> lead.getDateNewTry().isEqual(LocalDate.now()) || lead.getDateNewTry().isBefore(LocalDate.now()))
                        .sorted(Comparator.comparing(LeadDTO::getDateNewTry))
                        .collect(Collectors.toList());
            }
            else return leadsRepository.findAllByLidStatusAndManager(status, manager).stream()
                    .map(this::toDto)
                    .filter(lead -> lead.getDateNewTry().isEqual(LocalDate.now()) || lead.getDateNewTry().isBefore(LocalDate.now()))
                    .sorted(Comparator.comparing(LeadDTO::getDateNewTry))
                    .collect(Collectors.toList());
        }
        return new ArrayList<LeadDTO>();
    }
    // Взять всех юзеров - конец
    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ - КОНЕЦ =========================================

    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ БЕЗ СТАТУСА - НАЧАЛО =========================================
    // Взять всех юзеров - начало
    @Override
    public List<LeadDTO> getAllLeadsNoStatus(String keywords, Principal principal) {
        log.info("Берем все лиды");
        String userRole = gerRole(principal);
        System.out.println(userRole);
        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            if (!keywords.isEmpty()){
                return leadsRepository.findByTelephoneLeadContainingIgnoreCase(keywords).stream()
                        .map(this::toDto)
                        .filter(lead -> lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
                        .sorted(Comparator.comparing(LeadDTO::getCreateDate))
                        .collect(Collectors.toList());
            }
            else return leadsRepository.findAll().stream()
                    .map(this::toDto)
                    .filter(lead -> lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
                    .sorted(Comparator.comparing(LeadDTO::getCreateDate))
                    .collect(Collectors.toList());
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для менеджера");
            Manager manager = managerService.getManagerByUserId(userService.findByUserName(principal.getName()).orElseThrow().getId());
            if (!keywords.isEmpty()){
                return leadsRepository.findByTelephoneLeadContainingIgnoreCaseAndManager(keywords, manager).stream()
                        .map(this::toDto)
                        .filter(lead -> lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
                        .sorted(Comparator.comparing(LeadDTO::getCreateDate))
                        .collect(Collectors.toList());
            }
            else return leadsRepository.findAllByManager(manager).stream()
                    .map(this::toDto)
                    .filter(lead -> lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
                    .sorted(Comparator.comparing(LeadDTO::getCreateDate))
                    .collect(Collectors.toList());
        }
        return new ArrayList<LeadDTO>();
    }
    // Взять всех юзеров - конец

    private String gerRole(Principal principal){
        // Получите текущий объект аутентификации
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Получите имя текущего пользователя (пользователя, не роль)
        String username = principal.getName();
        // Получите роль пользователя (предположим, что она хранится в поле "role" в объекте User)
        return ((UserDetails) authentication.getPrincipal()).getAuthorities().iterator().next().getAuthority();
    } // Берем роль пользователя
    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ - КОНЕЦ =========================================


    //    =============================== В DTO - НАЧАЛО =========================================
    // Метод конвертации из класса Lead в класс LeadDTO
    public LeadDTO convertFromLead(Lead lead) {
        log.info("Перевод лида в дто");
        LeadDTO leadDTO = new LeadDTO();
        leadDTO.setId(lead.getId());
        leadDTO.setTelephoneLead(lead.getTelephoneLead());
        leadDTO.setCityLead(lead.getCityLead());
        leadDTO.setCommentsLead(lead.getCommentsLead());
        leadDTO.setLidStatus(lead.getLidStatus());
        leadDTO.setCreateDate(lead.getCreateDate());
        leadDTO.setUpdateStatus(lead.getUpdateStatus());
        leadDTO.setDateNewTry(lead.getDateNewTry());
        // Обратите внимание, что здесь мы присваиваем идентификатор пользователя вместо всего объекта User
        // Если нужно больше данных о пользователе, то можно добавить соответствующие поля в LeadDTO
        leadDTO.setOperator(lead.getOperator() != null ? lead.getOperator() : null);
        leadDTO.setManager(lead.getManager() != null ? lead.getManager() : null);
        leadDTO.setMarketolog(lead.getMarketolog() != null ? lead.getMarketolog() : null);
        return leadDTO;
    }
    //    =============================== В DTO - КОНЕЦ =========================================



    //    =============================== СМЕНА СТАТУСОВ - НАЧАЛО =========================================

    // меняем статус с нового на отправленное - начало
    @Override
    @Transactional
    public void changeStatusLeadOnSend(Long leadId) {
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("Отправленный");
        lead.setUpdateStatus(LocalDate.now());
        lead.setDateNewTry(LocalDate.now().plusDays(1));
        leadsRepository.save(lead);
    }
    // меняем статус с нового на отправленное - конец

    // меняем статус с отправленное на напоминание - начало
    @Override
    @Transactional
    public void changeStatusLeadOnReSend(Long leadId) {
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("Напоминание");
        lead.setUpdateStatus(LocalDate.now());
        lead.setDateNewTry(LocalDate.now().plusDays(2));
        leadsRepository.save(lead);
    }
    // меняем статус с отправленное на напоминание - конец

    // меняем статус с напоминание на К рассылке - начало
    @Override
    @Transactional
    public void changeStatusLeadOnArchive(Long leadId) {
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("К рассылке");
        lead.setUpdateStatus(LocalDate.now());
        lead.setDateNewTry(LocalDate.now().plusDays(90));
        leadsRepository.save(lead);
    }
    // меняем статус с напоминание на К рассылке - конец

    // меняем статус с К рассылке на В работе - начало
    @Override
    @Transactional
    public void changeStatusLeadOnInWork(Long leadId) {
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("В работе");
        lead.setUpdateStatus(LocalDate.now());
        lead.setDateNewTry(LocalDate.now());
        leadsRepository.save(lead);
    }
    // меняем статус с К рассылке на В работе - конец


    // меняем статус с любого на Новый - начало
    @Override
    @Transactional
    public void changeStatusLeadOnNew(Long leadId) {
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("Новый");
        lead.setUpdateStatus(LocalDate.now());
        leadsRepository.save(lead);
    }
    // меняем статус с любого на Новый - конец

//    =============================== СМЕНА СТАТУСОВ - КОНЕЦ =========================================

    // Метод поиска юзера по имени в БД
    public Optional<Lead> findByLeadId(Long leadId){
        return leadsRepository.findById(leadId);
    }
    // Метод поиска юзера по имени в БД - конец


    // Взять одного юзера - конец
    @Override
    public LeadDTO findById(Long id) {
        log.info("Начинается поиск пользователя по id - начало");
        Lead lead = leadsRepository.findById(id).orElseThrow();
        log.info("Начинается поиск пользователя по id - конец");
        return toDto(lead);
    }
    // Взять одного юзера - конец

    // Взять одного юзера - конец
    @Override
    public Optional<Lead> findByIdAndToUpdate(Long id) {
        log.info("Начинается поиск пользователя по id - начало");
        return leadsRepository.findById(id);
    }
    // Взять одного юзера - конец

    public Optional<User> findByUserName(String username){
        return userRepository.findByUsername(username);
    }

    // Перевод юзера в дто - начало
    private LeadDTO toDto(Lead lead){
        log.info("Перевод юзера в дто");
        return LeadDTO.builder()
                .id(lead.getId())
                .telephoneLead(lead.getTelephoneLead())
                .cityLead(lead.getCityLead())
                .lidStatus(lead.getLidStatus())
                .commentsLead(lead.getCommentsLead())
                .createDate(lead.getCreateDate())
                .updateStatus(lead.getUpdateStatus())
                .dateNewTry(lead.getDateNewTry())
                .operator(lead.getOperator())
                .manager(lead.getManager())
                .marketolog(lead.getMarketolog())
//                .operatorId(lead.getOperator().getId())
                .build();
    }
    // Перевод юзера в дто - конец


    // Вспомогательный метод для корректировки номера телефона
    public String changeNumberPhone(String phone){
        String[] a;
        a = phone.split("9");
        a[0] = "+79";
        String b = a[0] + a[1];
        System.out.println(b);
        return b;
//        userDto.getPhoneNumber().replaceFirst("8", "+7")
    }

}

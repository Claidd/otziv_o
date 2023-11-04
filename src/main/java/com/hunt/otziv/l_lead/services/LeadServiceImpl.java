package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
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
import org.springframework.data.domain.*;
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

    public Lead save(LeadDTO leadDTO, String username){ // Создание нового пользователя "Лида" - начало
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
    } // Создание нового пользователя "Клиент" - конец

    //    =============================== СОХРАНИТЬ ЮЗЕРА - КОНЕЦ =========================================

    //    =============================== ОБНОВИТЬ ЮЗЕРА - НАЧАЛО =========================================

    @Override
    @Transactional
    public void updateProfile(LeadDTO leadDTO, Long id) {  // Обновить профиль юзера - начало
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
    }   // Обновить профиль юзера - конец

    public Optional<User> findByFio(String operator) {
        return userRepository.findByFio(operator);
    } // Взять лида по ФИО

    //    =============================== ОБНОВИТЬ ЮЗЕРА - КОНЕЦ =========================================




    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ - НАЧАЛО =========================================

//    @Override
//    public Page<Lead> getAllLeads(String status, String keywords, Principal principal, int pageNumber, int pageSize) {
//        log.info("Берем все лиды");
//        String userRole = gerRole(principal);
//        System.out.println(userRole);
//        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createDate").descending());
//        if ("ROLE_ADMIN".equals(userRole)) {
//            log.info("Зашли список всех заказов для админа");
//            if (!keywords.isEmpty()) {
//                return leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCase(status, keywords, pageable);
//
//            } else {
//                return leadsRepository.findAllByLidStatus(status, pageable);
//            }
//        } else if ("ROLE_MANAGER".equals(userRole)) {
//            log.info("Зашли список всех заказов для Менеджера");
//            Manager manager = managerService.getManagerByUserId(userService.findByUserName(principal.getName()).orElseThrow().getId());
//            if (!keywords.isEmpty()) {
//                return leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManager(status, keywords, manager, pageable);
//            } else {
//                return leadsRepository.findAllByLidStatusAndManager(status, manager, pageable);
//            }
//        } else if ("ROLE_MARKETOLOG".equals(userRole)) {
//            log.info("Зашли список всех заказов для Маркетолога");
//            Marketolog marketolog = marketologService.getMarketologById(userService.findByUserName(principal.getName()).orElseThrow().getId());
//            if (!keywords.isEmpty()) {
//                return leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndMarketolog(status, keywords, marketolog, pageable);
//            } else {
//                return leadsRepository.findAllByLidStatusAndMarketolog(status, marketolog, pageable);
//            }
//        } else {
//            return Page.empty();
//        }
//    }
//
//
//    @Override
//    public List<LeadDTO> getAllLeadsNoStatus(String keywords, Principal principal) {
//        return null;
//    }


    @Override
    public Page<LeadDTO> getAllLeads(String status, String keywords, Principal principal, int pageNumber, int pageSize) { // Взять всех лидов
        log.info("Берем все лиды");
        String userRole = gerRole(principal);
        System.out.println(userRole);
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createDate").descending());
        Page<Lead> leadsPage;
        List<LeadDTO> leadDTOs = null;
        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            if (!keywords.isEmpty()){
                leadsPage =  leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCase(status, keywords,pageable);
            }
            else leadsPage = leadsRepository.findAllByLidStatus(status,pageable);
            leadDTOs = leadsPage.getContent()
                    .stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());
            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для Менеджера");
            Manager manager = managerService.getManagerByUserId(userService.findByUserName(principal.getName()).orElseThrow().getId());
            if (!keywords.isEmpty()){
                leadsPage =leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManager(status, keywords, manager,pageable);
            }
            else leadsPage =leadsRepository.findAllByLidStatusAndManager(status, manager,pageable);
            leadDTOs = leadsPage.getContent()
                    .stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());
            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
        }
        if ("ROLE_MARKETOLOG".equals(userRole)){
            log.info("Зашли список всех заказов для Маркетолога");
            Marketolog marketolog = marketologService.getMarketologById(userService.findByUserName(principal.getName()).orElseThrow().getId());
            if (!keywords.isEmpty()){
                leadsPage =leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndMarketolog(status, keywords, marketolog,pageable);
            }
            else leadsPage =leadsRepository.findAllByLidStatusAndMarketolog(status, marketolog,pageable);
            leadDTOs = leadsPage.getContent()
                    .stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());
            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
        }
        return Page.empty();
    } // Взять всех лидов

    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ - КОНЕЦ =========================================

    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ ПО ДАТЕ В НАПОМИНАНИИ - НАЧАЛО =========================================

    @Override
    public Page<LeadDTO> getAllLeadsToDateReSend(String status, String keywords, Principal principal, int pageNumber, int pageSize) { // Взять всех лидов - к рассылке
        log.info("Берем все лиды");
        String userRole = gerRole(principal);
        System.out.println(userRole);
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createDate").descending());
        Page<Lead> leadsPage;
        List<LeadDTO> leadDTOs = null;
        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            if (!keywords.isEmpty()){
                leadsPage = leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCase(status, keywords,pageable);
            }
            else leadsPage = leadsRepository.findAllByLidStatus(status,pageable);
            leadDTOs = leadsPage.getContent()
                    .stream()
                    .map(this::toDto)
                    .filter(lead -> lead.getDateNewTry().isEqual(LocalDate.now()) || lead.getDateNewTry().isBefore(LocalDate.now()))
                    .sorted(Comparator.comparing(LeadDTO::getDateNewTry))
                    .collect(Collectors.toList());
            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для менеджера");
            Manager manager = managerService.getManagerByUserId(userService.findByUserName(principal.getName()).orElseThrow().getId());
            if (!keywords.isEmpty()){
                leadsPage = leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManager(status, keywords, manager,pageable);
            }
            else leadsPage = leadsRepository.findAllByLidStatusAndManager(status, manager,pageable);
            leadDTOs = leadsPage.getContent()
                    .stream()
                    .map(this::toDto)
                    .filter(lead -> lead.getDateNewTry().isEqual(LocalDate.now()) || lead.getDateNewTry().isBefore(LocalDate.now()))
                    .sorted(Comparator.comparing(LeadDTO::getDateNewTry))
                    .collect(Collectors.toList());
            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
        }
        return Page.empty();
    } // Взять всех лидов - к рассылке

//
//    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ - КОНЕЦ =========================================
//
//    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ БЕЗ СТАТУСА - НАЧАЛО =========================================
//
    @Override
    public Page<LeadDTO> getAllLeadsNoStatus(String keywords, Principal principal, int pageNumber, int pageSize) { // Взять всех лидов без статуса - конец
        log.info("Берем все лиды");
        String userRole = gerRole(principal);
        System.out.println(userRole);
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createDate").descending());
        Page<Lead> leadsPage;
        List<LeadDTO> leadDTOs = null;
        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех заказов для админа");
            if (!keywords.isEmpty()){
                leadsPage = leadsRepository.findByTelephoneLeadContainingIgnoreCase(keywords,pageable);
            }
            else leadsPage = leadsRepository.findAll(pageable);
            leadDTOs = leadsPage.getContent()
                    .stream()
                    .map(this::toDto)
                    .filter(lead -> lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
                    .sorted(Comparator.comparing(LeadDTO::getCreateDate))
                    .collect(Collectors.toList());
            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех заказов для менеджера");
            Manager manager = managerService.getManagerByUserId(userService.findByUserName(principal.getName()).orElseThrow().getId());
            if (!keywords.isEmpty()){
                leadsPage = leadsRepository.findByTelephoneLeadContainingIgnoreCaseAndManager(keywords, manager,pageable);
            }
            else leadsPage = leadsRepository.findAllByManager(manager,pageable);
            leadDTOs = leadsPage.getContent()
                    .stream()
                    .map(this::toDto)
                    .filter(lead -> lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
                    .sorted(Comparator.comparing(LeadDTO::getCreateDate))
                    .collect(Collectors.toList());
            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
        }
        return Page.empty();
    } // Взять всех лидов без статуса - конец

    private String gerRole(Principal principal){ // Берем роль пользователя
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

    @Override
    @Transactional
    public void changeStatusLeadOnReSend(Long leadId) { // меняем статус с отправленное на напоминание - начало
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("Напоминание");
        lead.setUpdateStatus(LocalDate.now());
        lead.setDateNewTry(LocalDate.now().plusDays(2));
        leadsRepository.save(lead);
    } // меняем статус с отправленное на напоминание - конец

    @Override
    @Transactional
    public void changeStatusLeadOnArchive(Long leadId) { // меняем статус с напоминание на К рассылке - начало
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("К рассылке");
        lead.setUpdateStatus(LocalDate.now());
        lead.setDateNewTry(LocalDate.now().plusDays(90));
        leadsRepository.save(lead);
    } // меняем статус с напоминание на К рассылке - конец

    @Override
    @Transactional
    public void changeStatusLeadOnInWork(Long leadId) { // меняем статус с К рассылке на В работе - начало
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("В работе");
        lead.setUpdateStatus(LocalDate.now());
        lead.setDateNewTry(LocalDate.now());
        leadsRepository.save(lead);
    } // меняем статус с К рассылке на В работе - конец

    @Override
    @Transactional
    public void changeStatusLeadOnNew(Long leadId) { // меняем статус с любого на Новый - начало
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("Новый");
        lead.setUpdateStatus(LocalDate.now());
        leadsRepository.save(lead);
    } // меняем статус с любого на Новый - конец

    @Override
    public List<Lead> findAllByLidListStatus(String username) {
        Manager manager = managerService.getManagerByUserId(userService.findByUserName(username).orElseThrow().getId());
        return leadsRepository.findAllByLidListStatus("Новый", manager);
    }

    @Override
    public Long findAllByLidListStatusNew(Marketolog marketolog) {
        LocalDate localDate = LocalDate.now();
        return leadsRepository.findAllByLidListStatusToMarketolog("Новый", marketolog, localDate);
    }

    @Override
    public Long findAllByLidListStatusInWork(Marketolog marketolog) {
        LocalDate localDate = LocalDate.now();
        return leadsRepository.findAllByLidListStatusToMarketolog("В работе", marketolog, localDate);
    }
    @Override
    public Long findAllByLidListStatusNewToDate(Marketolog marketolog, LocalDate localDate) {
        return leadsRepository.findAllByLidListStatusToMarketolog("Новый", marketolog, localDate);
    }

    @Override
    public Long findAllByLidListStatusInWorkToDate(Marketolog marketolog, LocalDate localDate) {
        return leadsRepository.findAllByLidListStatusToMarketolog("В работе", marketolog, localDate);
    }


    @Override
    public Long findAllByLidListStatusNew(Operator operator) {
        LocalDate localDate = LocalDate.now();
        return leadsRepository.findAllByLidListStatusToOperator("Новый", operator, localDate);
    }

    @Override
    public Long findAllByLidListStatusInWork(Operator operator) {
        LocalDate localDate = LocalDate.now();
        return leadsRepository.findAllByLidListStatusToOperator("В работе", operator, localDate);
    }

    @Override
    public Long findAllByLidListStatusNewToDate(Operator operator, LocalDate localDate) {
        return leadsRepository.findAllByLidListStatusToOperator("Новый", operator, localDate);
    }

    @Override
    public Long findAllByLidListStatusInWorkToDate(Operator operator, LocalDate localDate) {
        return leadsRepository.findAllByLidListStatusToOperator("В работе", operator, localDate);
    }

//    =============================== СМЕНА СТАТУСОВ - КОНЕЦ =========================================

    public Optional<Lead> findByLeadId(Long leadId){ // Метод поиска юзера по имени в БД
        return leadsRepository.findById(leadId);
    } // Метод поиска юзера по имени в БД - конец

    @Override
    public LeadDTO findById(Long leadId) { // Взять одного лида дто по id
        log.info("Начинается поиск пользователя по id - начало");
        Lead lead = leadsRepository.findById(leadId).orElseThrow();
        log.info("Начинается поиск пользователя по id - конец");
        return toDto(lead);
    } // Взять одного лида дто - конец

    @Override
    public Optional<Lead> findByIdAndToUpdate(Long id) { // Взять одного юзера - конец
        log.info("Начинается поиск пользователя по id - начало");
        return leadsRepository.findById(id);
    } // Взять одного юзера - конец

    public Optional<User> findByUserName(String username){ // Взять одного юзера по имени
        return userRepository.findByUsername(username);
    } // Взять одного юзера по имени

    // Перевод юзера в дто - начало
    private LeadDTO toDto(Lead lead){ // Перевод лида в дто

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
                .build();
    } // Перевод юзера в дто - конец

    public String changeNumberPhone(String phone){ // Вспомогательный метод для корректировки номера телефона
        String[] a = phone.split("9", 2);
        if (a.length > 1) {
            a[0] = "+79";
            return a[0] + a[1];
        } else {
            return phone;
        }
    } // Вспомогательный метод для корректировки номера телефона

    public List<Long> getAllLeadsByDate(LocalDate localDate){
        return leadsRepository.findIdListByDate(localDate);
    }

    public List<Long> getAllLeadsByDateAndStatus(LocalDate localDate, String status){
        return leadsRepository.findIdListByDate(localDate, status);
    }

    public List<Long> getAllLeadsByDate2Month(LocalDate localDate){
        LocalDate localDate1 = localDate.minusMonths(1);
        return leadsRepository.findIdListByDate(localDate1);
    }

    public List<Long> getAllLeadsByDateAndStatus2Month(LocalDate localDate, String status){
        LocalDate localDate1 = localDate.minusMonths(1);
        return leadsRepository.findIdListByDate(localDate1, status);
    }
}

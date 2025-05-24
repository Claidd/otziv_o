package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.event.LeadEventPublisher;
import com.hunt.otziv.l_lead.mapper.LeadMapper;
import com.hunt.otziv.l_lead.model.Telephone;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.l_lead.services.serv.TelephoneService;
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
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import com.hunt.otziv.z_zp.services.ZpService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.data.util.Pair;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LeadServiceImpl implements LeadService {

    private final LeadsRepository leadsRepository;
    private final UserRepository userRepository;
    private final ManagerService managerService;
    private final OperatorService operatorService;
    private final MarketologService marketologService;
    private final ZpService zpService;
    private final UserService userService;
    private final TelephoneService telephoneService;
    private final LeadMapper leadMapper;
    private final LeadEventPublisher leadEventPublisher;
    private final WhatsAppService whatsAppService;

    public LeadServiceImpl(LeadsRepository leadsRepository, UserRepository userRepository, ManagerService managerService, OperatorService operatorService, MarketologService marketologService, ZpService zpService, UserService userService, TelephoneService telephoneService, LeadMapper leadMapper, LeadEventPublisher leadEventPublisher, WhatsAppService whatsAppService) {
        this.leadsRepository = leadsRepository;
        this.userRepository = userRepository;
        this.managerService = managerService;
        this.operatorService = operatorService;
        this.marketologService = marketologService;
        this.zpService = zpService;
        this.userService = userService;
        this.telephoneService = telephoneService;
        this.leadMapper = leadMapper;
        this.leadEventPublisher = leadEventPublisher;
        this.whatsAppService = whatsAppService;
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
//        zpService.saveLeadZp(lead1);
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
//        if (!Objects.equals(leadDTO.getUpdateStatus(), saveLead.getUpdateStatus())){
//            saveLead.setUpdateStatus(leadDTO.getUpdateStatus());
//            isChanged = true;
//            log.info("Обновили дату изменения");
//        }
        /*Проверяем не равен ли апдейт оператора, если нет, то меняем флаг на тру*/
        if (!Objects.equals(leadDTO.getOperator(), saveLead.getOperator())){
//            System.out.println(leadDTO.getOperator());
//            System.out.println(saveLead.getOperator());
            saveLead.setOperator(leadDTO.getOperator());
            isChanged = true;
            log.info("Обновили оператора");
        }
        /*Проверяем не равен ли апдейт оператора, если нет, то меняем флаг на тру*/
        if (!Objects.equals(leadDTO.getMarketolog(), saveLead.getMarketolog())){
//            System.out.println(leadDTO.getMarketolog());
//            System.out.println(saveLead.getMarketolog());
            saveLead.setMarketolog(leadDTO.getMarketolog());
            isChanged = true;
            log.info("Обновили маркетолога");
        }
        /*Проверяем не равен ли апдейт менеджера, если нет, то меняем флаг на тру*/
        if (!Objects.equals(leadDTO.getManager(), saveLead.getManager())){
//            System.out.println(leadDTO.getManager());
//            System.out.println(saveLead.getManager());
            saveLead.setManager(leadDTO.getManager());
            isChanged = true;
            log.info("Обновили менеджера");
        }

        if (!Objects.equals(leadDTO.getLidStatus(), saveLead.getLidStatus())) {
            saveLead.setLidStatus(leadDTO.getLidStatus());
            isChanged = true;
            log.info("Обновили статус");
        }

        /*если какое-то изменение было и флаг сменился на тру, то только тогда мы изменяем запись в БД
         * А если нет, то и обращаться к базе данны и грузить ее мы не будем*/
        if  (isChanged){
            log.info("Начали сохранять обновленного лида в БД");
            leadsRepository.save(saveLead);
            log.info("Сохранили обновленного лида в БД Теперь запускаем отправку на сервер");
            leadEventPublisher.publishUpdate(saveLead);
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


    @Override
    public Page<LeadDTO> getAllLeads(String status, String keywords, Principal principal, int pageNumber, int pageSize) { // Взять всех лидов
        log.info("Берем все лиды");
        String userRole = getRole(principal);
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createDate").descending());
        Page<Lead> leadsPage;
        List<LeadDTO> leadDTOs = null;
        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех лидов для админа");
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
            log.info("Зашли список всех лидов для Менеджера");
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
            log.info("Зашли список всех лидов для Маркетолога");
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
        if ("ROLE_OWNER".equals(userRole)){
            log.info("Зашли список всех лидов для Владельца");
            List<Manager> managerList = Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getManagers().stream().toList();
            if (!keywords.isEmpty()){
                leadsPage =leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManagerToOwner(status, keywords, managerList, pageable);
            }
            else leadsPage =leadsRepository.findAllByLidStatusAndManagerToOwner(status, managerList, pageable);
            leadDTOs = leadsPage.getContent()
                    .stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());
            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
        }
        return Page.empty();
    } // Взять всех лидов


    @Override
    public Page<LeadDTO> getAllLeadsToWork(String status, String keywords, Principal principal, int pageNumber, int pageSize) { // Взять всех лидов
        log.info("Берем все лиды");
        String userRole = getRole(principal);
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createDate").descending());
        Page<Lead> leadsPage;
        List<LeadDTO> leadDTOs = null;
        if ("ROLE_ADMIN".equals(userRole)){
            log.info("Зашли список всех лидов для админа");
            if (!keywords.isEmpty()){
//                leadsPage =  leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCase(status, keywords,pageable);
                    leadsPage = leadsRepository.findByTelephoneLeadContainingIgnoreCase(keywords,pageable);
            }
            else leadsPage = leadsRepository.findAllByLidStatus(status,pageable);
            leadDTOs = leadsPage.getContent()
                    .stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());
            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
        }
        if ("ROLE_MANAGER".equals(userRole)){
            log.info("Зашли список всех лидов для Менеджера");
            Manager manager = managerService.getManagerByUserId(userService.findByUserName(principal.getName()).orElseThrow().getId());
            if (!keywords.isEmpty()){
//                leadsPage =leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManager(status, keywords, manager,pageable);
                leadsPage = leadsRepository.findByTelephoneLeadContainingIgnoreCaseAndManager(keywords, manager,pageable);
            }
            else leadsPage =leadsRepository.findAllByLidStatusAndManager(status, manager,pageable);
            leadDTOs = leadsPage.getContent()
                    .stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());
            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
        }
        if ("ROLE_MARKETOLOG".equals(userRole)){
            log.info("Зашли список всех лидов для Маркетолога");
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
        if ("ROLE_OWNER".equals(userRole)){
            log.info("Зашли список всех лидов для Владельца");
            List<Manager> managerList = Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getManagers().stream().toList();
            if (!keywords.isEmpty()){
//                leadsPage =leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManagerToOwner(status, keywords, managerList, pageable);
                leadsPage = leadsRepository.findByTelephoneLeadContainingIgnoreCaseAndManagerToOwner(keywords, managerList, pageable);
            }
            else leadsPage =leadsRepository.findAllByLidStatusAndManagerToOwner(status, managerList, pageable);
            leadDTOs = leadsPage.getContent()
                    .stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());
            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
        }
        return Page.empty();
    } // Взять всех лидов

    @Override
    public Page<LeadDTO> getAllLeadsToOperator(Long telephoneId, String status, String keywords, Principal principal, int pageNumber, int pageSize) {
        log.info("Берем один лид для оператора");

        Optional<Lead> lead;

        if (keywords != null && !keywords.isEmpty()) {
            lead = leadsRepository.findTopByLidStatusAndTelephoneIdAndKeywordOrderByCreateDateDesc(telephoneId, "%" + keywords + "%");
        } else {
            lead = leadsRepository.findTopByLidStatusAndTelephoneIdOrderByCreateDateDesc(telephoneId, status);
        }

        List<LeadDTO> leadDTOs = lead.map(this::toDto).map(List::of).orElse(List.of());

        return new PageImpl<>(leadDTOs, PageRequest.of(0, 1), leadDTOs.size());
    }




    @Override
    public Page<LeadDTO> getAllLeadsToOperatorAll(Long operatorId, String keywords, Principal principal, int pageNumber, int pageSize) {
        Operator operator = operatorService.getOperatorById(operatorId);
        log.info("🔍 Получаем все лиды оператора ID {} по ключу '{}'", operatorId, keywords);

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createDate").descending());

        String keywordPattern = "%" + keywords.trim().toLowerCase() + "%";

        Page<Lead> leadsPage = leadsRepository.getAllLeadsToOperatorAll(operator, keywordPattern, pageable);

        List<LeadDTO> leadDTOs = leadsPage.getContent()
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());

        return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
    }


    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ - КОНЕЦ =========================================

    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ ПО ДАТЕ В НАПОМИНАНИИ - НАЧАЛО =========================================

    @Override
    public Page<LeadDTO> getAllLeadsToDateReSend(String status, String keywords, Principal principal, int pageNumber, int pageSize) { // Взять всех лидов - к рассылке
        log.info("Берем все лиды");
        String userRole = getRole(principal);
        System.out.println(userRole);
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createDate").descending());
        Page<Lead> leadsPage;
        List<LeadDTO> leadDTOs = null;
        if ("ROLE_ADMIN".equals(userRole) || "ROLE_OWNER".equals(userRole)){
            log.info("Зашли список всех лидов для админа");
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
            log.info("Зашли список всех лидов для менеджера");
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
//        if ("ROLE_OWNER".equals(userRole)){
//            log.info("Зашли список всех лидов для Владельца");
//            List<Manager> managerList = Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getManagers().stream().toList();
//            if (!keywords.isEmpty()){
//                leadsPage = leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManagerToOwner(status, keywords, managerList,pageable);
//            }
//            else leadsPage = leadsRepository.findAllByLidStatusAndManagerToOwner(status, managerList, pageable);
//            leadDTOs = leadsPage.getContent()
//                    .stream()
//                    .map(this::toDto)
//                    .filter(lead -> lead.getDateNewTry().isEqual(LocalDate.now()) || lead.getDateNewTry().isBefore(LocalDate.now()))
//                    .sorted(Comparator.comparing(LeadDTO::getDateNewTry))
//                    .collect(Collectors.toList());
//            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
//        }
        return Page.empty();
    } // Взять всех лидов - к рассылке

    //
//    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ - КОНЕЦ =========================================
//
//    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ БЕЗ СТАТУСА - НАЧАЛО =========================================
//
    @Override
    public Page<LeadDTO> getAllLeadsNoStatus(String keywords, Principal principal, int pageNumber, int pageSize) { // Взять всех лидов без статуса - конец
        String userRole = getRole(principal);
        log.info("Берем все лиды" + userRole);
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createDate").descending());
        Page<Lead> leadsPage;
        List<LeadDTO> leadDTOs = null;
        if ("ROLE_ADMIN".equals(userRole) || "ROLE_OWNER".equals(userRole) ){
            log.info("Зашли список всех лидов для админа");
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
            log.info("Зашли список всех лидов для менеджера");
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
//        if ("ROLE_OWNER".equals(userRole)){
//            log.info("Зашли список всех лидов для менеджера");
//            List<Manager> managerList = Objects.requireNonNull(userService.findByUserName(principal.getName()).orElse(null)).getManagers().stream().toList();
//            if (!keywords.isEmpty()){
//                leadsPage = leadsRepository.findByTelephoneLeadContainingIgnoreCaseAndManagerToOwner(keywords, managerList, pageable);
//            }
//            else leadsPage = leadsRepository.findAllByManagerToOwner(managerList, pageable);
//            leadDTOs = leadsPage.getContent()
//                    .stream()
//                    .map(this::toDto)
//                    .filter(lead -> lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
//                    .sorted(Comparator.comparing(LeadDTO::getCreateDate))
//                    .collect(Collectors.toList());
//            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
//        }
        return Page.empty();
    } // Взять всех лидов без статуса - конец

    private String getRole(Principal principal){ // Берем роль пользователя
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
    public void changeStatusLeadOnSendAndTelephone(Long leadId) {
        Lead lead = findByLeadId(leadId)
                .orElseThrow(() -> new EntityNotFoundException("Лид с id " + leadId + " не найден"));

        Telephone telephone = Optional.ofNullable(lead.getTelephone())
                .orElseThrow(() -> new IllegalStateException("У лида нет привязанного телефона"));

        int updatedSentCount = telephone.getAmountSent() + 1;

        if (updatedSentCount >= telephone.getAmountAllowed()) {
            telephone.setTimer(LocalDateTime.now().plusMinutes(telephone.getBlockTime()));
            telephone.setAmountSent(0); // сброс счётчика
        } else {
            telephone.setAmountSent(updatedSentCount);
        }

        telephoneService.saveTelephone(telephone);

        lead.setLidStatus("К рассылке");
        lead.setUpdateStatus(LocalDateTime.now());
        lead.setDateNewTry(LocalDate.now().plusDays(720));

        leadsRepository.save(lead);
        leadEventPublisher.publishUpdate(lead);
    }


    // меняем статус с нового на отправленное - конец


    // меняем статус с нового на отправленное - начало
    @Override
    @Transactional
    public void changeStatusLeadOnSend(Long leadId) {
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("Отправленный");
        lead.setUpdateStatus(LocalDateTime.now());
        lead.setDateNewTry(LocalDate.now().plusDays(1));
        leadsRepository.save(lead);
        leadEventPublisher.publishUpdate(lead);
    }
    // меняем статус с нового на отправленное - конец


    // 120363399937937645@g.us    - Анжелика
    //     - Вика

    @Override
    @Transactional
    public void changeStatusLeadToWork(Long leadId, String newComment) {
        log.info("🚀 Начинаем обработку перевода лида {} в статус TO_WORK", leadId);

        Lead lead = findByLeadId(leadId).orElseThrow(() -> {
            log.error("❌ Лид с ID {} не найден в системе", leadId);
            return new UsernameNotFoundException(String.format("Пользователь с ID '%s' не найден", leadId));
        });

        // Обновляем комментарий, если он изменился
        if (newComment != null && !newComment.equals(lead.getCommentsLead())) {
            log.info("📝 Обновляем комментарий лида: {} → {}", lead.getCommentsLead(), newComment);
            lead.setCommentsLead(newComment);
        }

        Operator operator = lead.getOperator();
        log.info("🔄 Назначаем менеджера на основе счётчика оператора (ID: {}, Count: {})", operator.getId(), operator.getCount());
        assignManagerBasedOnOperatorCount(lead, operator);

        lead.setLidStatus(LeadStatus.TO_WORK.title);
        leadsRepository.save(lead);
        log.info("✅ Статус лида {} установлен в '{}'", lead.getId(), LeadStatus.TO_WORK.title);

        pushToWhatsApp(lead); //  Отправляем уведомление в Ватсапп

        toggleOperatorManagerCount(operator); //  меняем счетчик у оператора
        leadEventPublisher.publishUpdate(lead); //  Отправляем уведомление в Ватсаппотправляем изменения на сервер
        log.info("✅ Обработка лида {} завершена", leadId);
    }

    private void assignManagerBasedOnOperatorCount(Lead lead, Operator operator) {
        Long managerId = switch (operator.getCount()) {
            case 0 -> 2L;
            case 1 -> 3L;
            default -> throw new IllegalStateException("Неизвестное значение счётчика оператора: " + operator.getCount());
        };
        lead.setManager(managerService.getManagerById(managerId));
        log.info("👤 Менеджер с ID {} назначен лиду {}", managerId, lead.getId());
    }

    private void toggleOperatorManagerCount(Operator operator) {
        int oldCount = operator.getCount();
        int updatedCount = (oldCount == 0) ? 1 : 0;
        operator.setCount(updatedCount);
        operatorService.save(operator);
        log.info("🔁 Счётчик оператора {} изменён: {} → {}", operator.getId(), oldCount, updatedCount);
    }

    private void pushToWhatsApp(Lead lead) {
        Long managerId = lead.getManager().getId();
        String groupId = switch (managerId.intValue()) {
            case 2 -> ""; // Можно заменить на реальную группу
            case 3 -> "120363399937937645@g.us";
            default -> null;
        };

        String clientId = lead.getManager().getClientId();

        if (clientId == null || clientId.isBlank()) {
            log.warn("❌ Неизвестный клиент (clientId = null) для менеджера ID: {} — сообщение в WhatsApp не отправлено", managerId);
            return;
        }

        if (groupId != null && !groupId.isEmpty()) {
            String message = String.format("📨 Новая фирма:\n📞 %s\n🌆 %s\n💬 %s",
                    lead.getTelephoneLead(), lead.getCityLead(), lead.getCommentsLead());

            log.info("🚀 Попытка отправить сообщение в группу через {} на {}", clientId, groupId);
            whatsAppService.sendMessageToGroup(clientId, groupId, message);
            log.info("📲 Сообщение отправлено в WhatsApp-группу {} от менеджера {}", groupId, managerId);
        } else {
            log.warn("⚠️ WhatsApp-группа не указана для менеджера ID: {} — сообщение не отправлено", managerId);
        }
    }






    @Override
    public void changeCountToOperator(Long leadId) {
        Lead lead = leadsRepository.findById(leadId).orElseThrow();
        Operator operator = lead.getTelephone().getTelephoneOperator();
//        Operator operator = operatorService.getOperatorByTelephoneId(lead.getTelephone().getId());
        System.out.println(operator);
        int count = operator.getCount();
        if (count == 0){
            operator.setCount(1);
        }
        if (count >= 1){
            operator.setCount(0);
        }
        operatorService.save(operator);
        log.info("поменяли счетчик выбора менеджера");
    }

    @Override
    @Transactional
    public void changeStatusLeadOnReSend(Long leadId) { // меняем статус с отправленное на напоминание - начало
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("Напоминание");
        lead.setUpdateStatus(LocalDateTime.now());
        lead.setDateNewTry(LocalDate.now().plusDays(2));
        leadsRepository.save(lead);
        leadEventPublisher.publishUpdate(lead);
    } // меняем статус с отправленное на напоминание - конец

    @Override
    @Transactional
    public void changeStatusLeadOnArchive(Long leadId) { // меняем статус с напоминание на К рассылке - начало
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("К рассылке");
        lead.setUpdateStatus(LocalDateTime.now());
        lead.setDateNewTry(LocalDate.now().plusDays(90));
        leadsRepository.save(lead);
        leadEventPublisher.publishUpdate(lead);
    } // меняем статус с напоминание на К рассылке - конец

    @Override
    @Transactional
    public void changeStatusLeadOnInWork(Long leadId) { // меняем статус с К рассылке на В работе - начало
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("В работе");
        lead.setUpdateStatus(LocalDateTime.now());
        lead.setDateNewTry(LocalDate.now());
        leadsRepository.save(lead);
        leadEventPublisher.publishUpdate(lead);
    } // меняем статус с К рассылке на В работе - конец

    @Override
    @Transactional
    public void changeStatusLeadOnNew(Long leadId) { // меняем статус с любого на Новый - начало
        Lead lead = findByLeadId(leadId).orElseThrow(() -> new UsernameNotFoundException(
                String.format("Пользоваттель '%s' не найден", leadId)
        ));
        lead.setLidStatus("Новый");
        lead.setUpdateStatus(LocalDateTime.now());
        leadsRepository.save(lead);
        leadEventPublisher.publishUpdate(lead);
    } // меняем статус с любого на Новый - конец


    @Override
    public List<Lead> findAllByLidListStatus(String username) {
        Manager manager = managerService.getManagerByUserId(userService.findByUserName(username).orElseThrow().getId());
        return leadsRepository.findAllByLidListStatus("Новый", manager);
    }

    @Override
    public Long findAllByLidListNew(Marketolog marketolog) {
        LocalDate localDate = LocalDate.now();
        return leadsRepository.findAllByLidListToMarketolog(marketolog, localDate);
    }

    @Override
    public Long findAllByLidListStatusInWork(Marketolog marketolog) {
        LocalDate localDate = LocalDate.now();
        return leadsRepository.findAllByLidListStatusToMarketolog("В работе", marketolog, localDate);
    }

    @Override
    public Long findAllByLidListNewToDate(Marketolog marketolog, LocalDate localDate) {
        return leadsRepository.findAllByLidListToMarketolog(marketolog, localDate);
    }

    @Override
    public Long findAllByLidListStatusInWorkToDate(Marketolog marketolog, LocalDate localDate) {
        return leadsRepository.findAllByLidListStatusToMarketolog("В работе", marketolog, localDate);
    }



    @Override
    public Long findAllByLidListNew(Operator operator) {
        LocalDate localDate = LocalDate.now();
        return leadsRepository.findAllByLidListToOperator(operator, localDate);
    }

    @Override
    public Long findAllByLidListStatusInWork(Operator operator) {
        LocalDate localDate = LocalDate.now();
        return leadsRepository.findAllByLidListStatusToOperator("В работе", operator, localDate);
    }

    @Override
    public Long findAllByLidListNewToDate(Operator operator, LocalDate localDate) {
        return leadsRepository.findAllByLidListToOperator(operator, localDate);
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
    public Optional<Lead> findByIdOptional(Long leadId) { // Взять одного лида дто по id
        return leadsRepository.findById(leadId);
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
    private LeadDTO toDto(Lead lead) {// Перевод юзера в дто - конец

        if (lead.getTelephone() != null && lead.getTelephone().getTelephoneOperator() != null) {
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
                    .operatorId(lead.getTelephone().getTelephoneOperator().getId())
                    .build();
        }
        else {
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
        }
    }// Перевод юзера в дто - конец

    public String changeNumberPhone(String phone){ // Вспомогательный метод для корректировки номера телефона
        String[] a = phone.split("9", 2);
        if (a.length > 1) {
            a[0] = "+79";
            String tel = a[0] + a[1];
            String tel2 = tel.replace("-","");
            String tel3 = tel2.replace("(", "");
            String tel4 = tel3.replace(")","");
            return tel4.replace(" ", "");
        } else {
            return phone;
        }
    } // Вспомогательный метод для корректировки номера телефона

    public List<Long> getAllLeadsByDate(LocalDate localDate){
        return leadsRepository.findIdListByDate(localDate);
    }

    public List<Long> getAllLeadsByDateToOwner(LocalDate localDate, Set<Manager> managerList){
        return leadsRepository.findIdListByDateToOwner(localDate, managerList);
    }



    public List<Long> getAllLeadsByDateAndStatus(LocalDate localDate, String status){
        return leadsRepository.findIdListByDate(localDate, status);
    }

    public List<Long> getAllLeadsByDateAndStatusToOwner(LocalDate localDate, String status, Set<Manager> managerList){
        return leadsRepository.findIdListByDateToOwner(localDate, status, managerList);
    }

    public List<Long> getAllLeadsByDate2Month(LocalDate localDate){
        LocalDate localDate1 = localDate.minusMonths(1);
        return leadsRepository.findIdListByDate(localDate1);
    }

    public List<Long> getAllLeadsByDateAndStatus2Month(LocalDate localDate, String status){
        LocalDate localDate1 = localDate.minusMonths(1);
        return leadsRepository.findIdListByDate(localDate1, status);
    }


    @Override
    public List<Long> getAllLeadsByDateAndStatusToOwnerForTelegram(LocalDate localDate, String status, Set<Manager> managerList) {
        return leadsRepository.findIdListByDateToOwner(localDate, status, managerList);
    }


    @Override
    public Map<String, Pair<Long, Long>> getAllLeadsToMonth(String statusInWork, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        // Получаем результат из базы данных (например, используя @Query)
        List<Object[]> results = leadsRepository.getAllLeadsToMonth(statusInWork, firstDayOfMonth, lastDayOfMonth);

        Map<String, Pair<Long, Long>> resultMap = new HashMap<>();

        for (Object[] row : results) {
            String operatorFio = (String) row[0];
            Long allLeadsOperator = (Long) row[1];
            Long statusInWorkOperator = (Long) row[2];

            String marketologFio = (String) row[3];
            Long allLeadsMarketolog = (Long) row[4];
            Long statusInWorkMarketolog = (Long) row[5];

            // Обрабатываем оператора
            resultMap.put(operatorFio, Pair.of(allLeadsOperator, statusInWorkOperator));

            // Обрабатываем маркетолога
            resultMap.put(marketologFio, Pair.of(allLeadsMarketolog, statusInWorkMarketolog));
        }
//        System.out.println(resultMap);
        return resultMap;
    }

    @Override
    public Map<String, Long> getAllLeadsToMonthToManager(String status, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        // Получаем результат из базы данных (например, используя @Query)
        List<Object[]> results = leadsRepository.getAllLeadsToMonthToManager(status);

        Map<String, Long> resultMap = new HashMap<>();

        for (Object[] row : results) {
            String managerFio = (String) row[0];
            Long allLeadsManager = (Long) row[1];

            // Обрабатываем менеджера
            resultMap.put(managerFio, allLeadsManager);
        }
//        System.out.println(resultMap);
        return resultMap;
    }



    @Override
    public Optional<Lead> getByTelephoneLead(String telephoneNumber) {
        return leadsRepository.findByTelephoneLead(telephoneNumber);
    }

    @Override
    public void saveLead(Lead lead) {
        leadsRepository.save(lead);
    }

    @Override
    public int countNewLeadsByClient(Long telephoneId, String status) {
        return leadsRepository.countByTelephone_IdAndCreateDateLessThanEqualAndLidStatus(telephoneId, LocalDate.now() , status);
    }

    @Override
    public LeadDtoTransfer findByIdToTransfer(Long leadId) {
        return leadMapper.toDtoTransfer(leadsRepository.findById(leadId).orElseThrow());
    }


    public List<Lead> findModifiedSince(LocalDateTime since) {
        return leadsRepository.findByUpdateStatusAfter(since);
    }

    @Override
    @Transactional
    public void saveOrUpdateByTelephoneLead(Lead incomingLead) {
        log.info("📨 saveOrUpdateByTelephoneLead: {}", incomingLead.getTelephoneLead());

        Optional<Lead> existing = leadsRepository.findByTelephoneLead(incomingLead.getTelephoneLead());

        if (existing.isPresent()) {
            Lead lead = existing.get();

            lead.setTelephoneLead(incomingLead.getTelephoneLead());
            lead.setCityLead(incomingLead.getCityLead());
            lead.setCommentsLead(incomingLead.getCommentsLead());
            lead.setLidStatus(incomingLead.getLidStatus());
            lead.setCreateDate(incomingLead.getCreateDate());
            lead.setUpdateStatus(incomingLead.getUpdateStatus());
            lead.setDateNewTry(incomingLead.getDateNewTry());

            lead.setOperator(incomingLead.getOperator());
            lead.setManager(incomingLead.getManager());
            lead.setMarketolog(incomingLead.getMarketolog());
            lead.setTelephone(incomingLead.getTelephone());

            leadsRepository.save(lead);
            log.info("🔁 Обновили существующего лида: {}", lead.getTelephoneLead());

        } else {
            leadsRepository.save(incomingLead);
            log.info("🆕 Добавили нового лида: {}", incomingLead.getTelephoneLead());
        }
    }



}

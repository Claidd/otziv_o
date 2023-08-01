package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.a_login.dto.RegistrationUserDTO;
import com.hunt.otziv.a_login.model.Role;
import com.hunt.otziv.a_login.model.User;
import com.hunt.otziv.a_login.repository.UserRepository;
import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.model.LeadStatus;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LeadServiceImpl implements LeadService{

    private final LeadsRepository leadsRepository;
    private final UserRepository userRepository;

    public LeadServiceImpl(LeadsRepository leadsRepository, UserRepository userRepository) {
        this.leadsRepository = leadsRepository;
        this.userRepository = userRepository;
    }

    //    =============================== СОХРАНИТЬ ЮЗЕРА - НАЧАЛО =========================================
    // Создание нового пользователя "Лида" - начало
    public Lead save(LeadDTO leadDTO, String username){
        log.info("3. Заходим в создание нового юзера и проверяем совпадение паролей");

        Lead lead = Lead.builder()
                .telephoneLead(changeNumberPhone(leadDTO.getTelephoneLead()))
                .cityLead(leadDTO.getCityLead())
                .commentsLead(leadDTO.getCommentsLead())
                .lidStatus(LeadStatus.NEW.title)
                .operator(userRepository.findByUsername(username).orElseThrow())
                .manager(userRepository.findByUsername(username).orElseThrow())
                .build();
        log.info("5. Юзер успешно создан");
//        this.save(user);
        return leadsRepository.save(lead);
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
        if (!Objects.equals(leadDTO.getOperator(), saveLead.getOperator().getFio())){
            System.out.println(leadDTO.getOperator());
            System.out.println(saveLead.getOperator().getFio());
            saveLead.setOperator(findByFio(leadDTO.getOperator()).orElseThrow());
            isChanged = true;
            log.info("Обновили оператора");
        }
        /*Проверяем не равен ли апдейт менеджера, если нет, то меняем флаг на тру*/
        if (!Objects.equals(leadDTO.getManager(), saveLead.getManager().getFio())){
            System.out.println(leadDTO.getManager());
            System.out.println(saveLead.getManager().getFio());
            saveLead.setManager(findByFio(leadDTO.getManager()).orElseThrow());
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
    public List<LeadDTO> getAllLeads(String status, String keywords) {
        log.info("Берем все юзеров");
        if (!keywords.isEmpty()){
            return leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCase(status, keywords).stream()
                    .map(this::toDto)
                    .filter(lead -> lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
                    .sorted(Comparator.comparing(LeadDTO::getCreateDate))
                    .collect(Collectors.toList());
        }
        else return leadsRepository.findAllByLidStatus(status).stream()
                .map(this::toDto)
                .filter(lead -> lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
                .sorted(Comparator.comparing(LeadDTO::getCreateDate))
                .collect(Collectors.toList());
    }
    // Взять всех юзеров - конец
    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ - КОНЕЦ =========================================

    //    =============================== ВЗЯТЬ ВСЕХ ЮЗЕРОВ ПО ДАТЕ В НАПОМИНАНИИ - НАЧАЛО =========================================
    // Взять всех юзеров - начало
    @Override
    public List<LeadDTO> getAllLeadsToDateReSend(String status, String keywords) {
        log.info("Берем все юзеров");
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
    // Взять всех юзеров - конец
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
        leadDTO.setOperator(lead.getOperator() != null ? lead.getOperator().getFio() : null);
        leadDTO.setManager(lead.getManager() != null ? lead.getManager().getFio() : null);
//        leadDTO.setOperatorId(lead.getOperator() != null ? lead.getOperator().getId() : null);
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
                .operator(lead.getOperator().getFio())
                .manager(lead.getManager().getFio())
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

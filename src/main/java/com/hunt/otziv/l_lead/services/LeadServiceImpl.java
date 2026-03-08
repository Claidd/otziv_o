package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.dto.LeadMonthStats;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LeadServiceImpl implements LeadService {

    private static final String STATUS_IN_WORK = "В работе";
    private static final String STATUS_NEW = "Новый";
    private static final String STATUS_SENT = "Отправленный";
    private static final String STATUS_RESEND = "Напоминание";
    private static final String STATUS_QUEUE = "К рассылке";

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

    public LeadServiceImpl(LeadsRepository leadsRepository,
                           UserRepository userRepository,
                           ManagerService managerService,
                           OperatorService operatorService,
                           MarketologService marketologService,
                           ZpService zpService,
                           UserService userService,
                           TelephoneService telephoneService,
                           LeadMapper leadMapper,
                           LeadEventPublisher leadEventPublisher,
                           WhatsAppService whatsAppService) {
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

    @Override
    public Lead save(LeadDTO leadDTO, String username) {
        log.info("Заходим в создание нового лида");

        User user = findByUserName(username).orElseThrow(() ->
                new UsernameNotFoundException(String.format("Пользователь '%s' не найден", username))
        );

        Operator operator = operatorService.getOperatorByUserId(user.getId());
        if (operator == null && user.getOperators() != null) {
            operator = user.getOperators().stream().findFirst().orElse(null);
        }

        Marketolog marketolog = marketologService.getMarketologByUserId(user.getId());
        if (marketolog == null && user.getMarketologs() != null) {
            marketolog = user.getMarketologs().stream().findFirst().orElse(null);
        }

        Manager manager = user.getManagers() != null
                ? user.getManagers().stream().findFirst().orElse(null)
                : null;

        Lead lead = Lead.builder()
                .telephoneLead(changeNumberPhone(leadDTO.getTelephoneLead()))
                .cityLead(leadDTO.getCityLead())
                .commentsLead(leadDTO.getCommentsLead())
                .lidStatus(LeadStatus.NEW.title)
                .operator(operator)
                .marketolog(marketolog)
                .manager(manager)
                .build();

        Lead savedLead = leadsRepository.save(lead);
        log.info("Лид успешно создан: id={}", savedLead.getId());

        return savedLead;
    }

    @Override
    @Transactional
    public void updateProfile(LeadDTO leadDTO, Long id) {
        log.info("Вошли в обновление лида id={}", id);

        Lead saveLead = findByIdAndToUpdate(id).orElseThrow(() ->
                new UsernameNotFoundException(String.format("Пользователь с номером '%s' не найден", leadDTO.getTelephoneLead()))
        );

        boolean isChanged = false;

        if (!Objects.equals(leadDTO.getTelephoneLead(), saveLead.getTelephoneLead())) {
            saveLead.setTelephoneLead(leadDTO.getTelephoneLead());
            isChanged = true;
            log.info("Обновили телефон");
        }

        if (!Objects.equals(leadDTO.getCityLead(), saveLead.getCityLead())) {
            saveLead.setCityLead(leadDTO.getCityLead());
            isChanged = true;
            log.info("Обновили город");
        }

        if (!Objects.equals(leadDTO.getCommentsLead(), saveLead.getCommentsLead())) {
            saveLead.setCommentsLead(leadDTO.getCommentsLead());
            isChanged = true;
            log.info("Обновили комментарий");
        }

        if (!Objects.equals(leadDTO.getOperator(), saveLead.getOperator())) {
            saveLead.setOperator(leadDTO.getOperator());
            isChanged = true;
            log.info("Обновили оператора");
        }

        if (!Objects.equals(leadDTO.getMarketolog(), saveLead.getMarketolog())) {
            saveLead.setMarketolog(leadDTO.getMarketolog());
            isChanged = true;
            log.info("Обновили маркетолога");
        }

        if (!Objects.equals(leadDTO.getManager(), saveLead.getManager())) {
            saveLead.setManager(leadDTO.getManager());
            isChanged = true;
            log.info("Обновили менеджера");
        }

        if (!Objects.equals(leadDTO.getLidStatus(), saveLead.getLidStatus())) {
            saveLead.setLidStatus(leadDTO.getLidStatus());
            isChanged = true;
            log.info("Обновили статус");
        }

        if (isChanged) {
            leadsRepository.save(saveLead);
            leadEventPublisher.publishUpdate(saveLead);
            log.info("Лид обновлен и событие отправлено");
        } else {
            log.info("Изменений не было, лид не обновлялся");
        }
    }

    public Optional<User> findByFio(String operator) {
        return userRepository.findByFio(operator);
    }

    @Override
    @Transactional
    public void markOfferSentAndPublish(Long leadId) {
        Lead lead = leadsRepository.findById(leadId)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + leadId));

        lead.setOffer(true);
        lead.setUpdateStatus(LocalDateTime.now());
        leadsRepository.save(lead);

        leadEventPublisher.publishUpdate(lead);
    }


    @Override
    public Map<Long, Long> getManagerLeadsInWorkCount(Set<Manager> managerList, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        if (managerList == null || managerList.isEmpty()) {
            return Map.of();
        }

        return leadsRepository.aggregateManagerLeadsInWork(managerList, STATUS_IN_WORK, firstDayOfMonth, lastDayOfMonth)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]
                ));
    }

    @Override
    public long countNewLeadsForManagerUserId( Long userId) {

        return leadsRepository.countNewLeadsForManagerUserId(userId);
    }

    @Override
    public LeadMonthStats getLeadMonthStatsForManagers(Set<Manager> managerList, String inWorkStatus, LocalDate currentDate) {
        if (managerList == null || managerList.isEmpty() || currentDate == null) {
            return new LeadMonthStats(0L, 0L, 0L, 0L);
        }

        LocalDate currentMonthStart = currentDate.withDayOfMonth(1);
        LocalDate currentMonthEnd = currentDate.withDayOfMonth(currentDate.lengthOfMonth());

        LocalDate previousMonthDate = currentDate.minusMonths(1);
        LocalDate previousMonthStart = previousMonthDate.withDayOfMonth(1);
        LocalDate previousMonthEnd = previousMonthDate.withDayOfMonth(previousMonthDate.lengthOfMonth());

        List<Object[]> rows = leadsRepository.aggregateLeadStatsForTwoMonths(
                managerList,
                inWorkStatus,
                previousMonthStart,
                currentMonthEnd
        );

        long currentAll = 0L;
        long currentInWork = 0L;
        long previousAll = 0L;
        long previousInWork = 0L;

        int currentYear = currentMonthStart.getYear();
        int currentMonth = currentMonthStart.getMonthValue();

        int previousYear = previousMonthStart.getYear();
        int previousMonth = previousMonthStart.getMonthValue();

        for (Object[] row : rows) {
            int year = ((Number) row[0]).intValue();
            int month = ((Number) row[1]).intValue();
            long total = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            long inWork = row[3] != null ? ((Number) row[3]).longValue() : 0L;

            if (year == currentYear && month == currentMonth) {
                currentAll = total;
                currentInWork = inWork;
            } else if (year == previousYear && month == previousMonth) {
                previousAll = total;
                previousInWork = inWork;
            }
        }

        return new LeadMonthStats(
                currentAll,
                currentInWork,
                previousAll,
                previousInWork
        );
    }

    @Override
    @Transactional
    public LeadMonthStats getLeadMonthStatsForManagerIds(List<Long> managerIds, String statusInWork, LocalDate localDate) {
        if (managerIds == null || managerIds.isEmpty() || localDate == null) {
            return new LeadMonthStats(0L, 0L, 0L, 0L);
        }

        LocalDate currentMonthStart = localDate.withDayOfMonth(1);
        LocalDate previousMonthStart = currentMonthStart.minusMonths(1);
        LocalDate currentMonthEnd = currentMonthStart.withDayOfMonth(currentMonthStart.lengthOfMonth());

        List<Object[]> rows = leadsRepository.aggregateLeadMonthStatsForManagerIds(
                managerIds,
                statusInWork,
                previousMonthStart,
                currentMonthEnd
        );

        long currentMonthAll = 0L;
        long currentMonthInWork = 0L;
        long previousMonthAll = 0L;
        long previousMonthInWork = 0L;

        for (Object[] row : rows) {
            Integer year = (Integer) row[0];
            Integer month = (Integer) row[1];
            Long allCount = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            Long inWorkCount = row[3] != null ? ((Number) row[3]).longValue() : 0L;

            if (year == localDate.getYear() && month == localDate.getMonthValue()) {
                currentMonthAll = allCount;
                currentMonthInWork = inWorkCount;
            } else if (year == previousMonthStart.getYear() && month == previousMonthStart.getMonthValue()) {
                previousMonthAll = allCount;
                previousMonthInWork = inWorkCount;
            }
        }

        return new LeadMonthStats(
                currentMonthAll,
                currentMonthInWork,
                previousMonthAll,
                previousMonthInWork
        );
    }

    @Override
    public Page<LeadDTO> getAllLeads(String status, String keywords, Principal principal, int pageNumber, int pageSize) {
        log.info("Берем все лиды");

        String userRole = getRole();
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createDate").descending());

        if ("ROLE_ADMIN".equals(userRole)) {
            Page<Lead> leadsPage = hasText(keywords)
                    ? leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCase(status, keywords, pageable)
                    : leadsRepository.findAllByLidStatus(status, pageable);
            return toLeadDtoPage(leadsPage, pageable);
        }

        if ("ROLE_MANAGER".equals(userRole)) {
            User currentUser = requireCurrentUser(principal);
            Manager manager = managerService.getManagerByUserId(currentUser.getId());
            if (manager == null) {
                return Page.empty(pageable);
            }

            Page<Lead> leadsPage = hasText(keywords)
                    ? leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManager(status, keywords, manager, pageable)
                    : leadsRepository.findAllByLidStatusAndManager(status, manager, pageable);

            return toLeadDtoPage(leadsPage, pageable);
        }

        if ("ROLE_MARKETOLOG".equals(userRole)) {
            User currentUser = requireCurrentUser(principal);
            Marketolog marketolog = marketologService.getMarketologByUserId(currentUser.getId());
            if (marketolog == null) {
                return Page.empty(pageable);
            }

            Page<Lead> leadsPage = hasText(keywords)
                    ? leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndMarketolog(status, keywords, marketolog, pageable)
                    : leadsRepository.findAllByLidStatusAndMarketolog(status, marketolog, pageable);

            return toLeadDtoPage(leadsPage, pageable);
        }

        if ("ROLE_OWNER".equals(userRole)) {
            User currentUser = requireCurrentUser(principal);
            List<Manager> managerList = currentUser.getManagers() == null
                    ? List.of()
                    : currentUser.getManagers().stream().toList();

            if (managerList.isEmpty()) {
                return Page.empty(pageable);
            }

            Page<Lead> leadsPage = hasText(keywords)
                    ? leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManagerToOwner(status, keywords, managerList, pageable)
                    : leadsRepository.findAllByLidStatusAndManagerToOwner(status, managerList, pageable);

            return toLeadDtoPage(leadsPage, pageable);
        }

        return Page.empty(pageable);
    }

    @Override
    public Page<LeadDTO> getAllLeadsToWork(String status, String keywords, Principal principal, int pageNumber, int pageSize) {
        log.info("Берем все лиды");

        String userRole = getRole();
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createDate").descending());

        if ("ROLE_ADMIN".equals(userRole)) {
            Page<Lead> leadsPage = hasText(keywords)
                    ? leadsRepository.findByTelephoneLeadContainingIgnoreCase(keywords, pageable)
                    : leadsRepository.findAllByLidStatus(status, pageable);
            return toLeadDtoPage(leadsPage, pageable);
        }

        if ("ROLE_MANAGER".equals(userRole)) {
            User currentUser = requireCurrentUser(principal);
            Manager manager = managerService.getManagerByUserId(currentUser.getId());
            if (manager == null) {
                return Page.empty(pageable);
            }

            Page<Lead> leadsPage = hasText(keywords)
                    ? leadsRepository.findByTelephoneLeadContainingIgnoreCaseAndManager(keywords, manager, pageable)
                    : leadsRepository.findAllByLidStatusAndManager(status, manager, pageable);

            return toLeadDtoPage(leadsPage, pageable);
        }

        if ("ROLE_MARKETOLOG".equals(userRole)) {
            User currentUser = requireCurrentUser(principal);
            Marketolog marketolog = marketologService.getMarketologByUserId(currentUser.getId());
            if (marketolog == null) {
                return Page.empty(pageable);
            }

            Page<Lead> leadsPage = hasText(keywords)
                    ? leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndMarketolog(status, keywords, marketolog, pageable)
                    : leadsRepository.findAllByLidStatusAndMarketolog(status, marketolog, pageable);

            return toLeadDtoPage(leadsPage, pageable);
        }

        if ("ROLE_OWNER".equals(userRole)) {
            User currentUser = requireCurrentUser(principal);
            List<Manager> managerList = currentUser.getManagers() == null
                    ? List.of()
                    : currentUser.getManagers().stream().toList();

            if (managerList.isEmpty()) {
                return Page.empty(pageable);
            }

            Page<Lead> leadsPage = hasText(keywords)
                    ? leadsRepository.findByTelephoneLeadContainingIgnoreCaseAndManagerToOwner(keywords, managerList, pageable)
                    : leadsRepository.findAllByLidStatusAndManagerToOwner(status, managerList, pageable);

            return toLeadDtoPage(leadsPage, pageable);
        }

        return Page.empty(pageable);
    }

    @Override
    public Page<LeadDTO> getAllLeadsToOperator(Long telephoneId, String status, String keywords, Principal principal, int pageNumber, int pageSize) {
        Optional<Lead> lead;

        if (hasText(keywords)) {
            lead = leadsRepository.findTopByLidStatusAndTelephoneIdAndKeywordOrderByCreateDateDesc(
                    telephoneId, keywords
            );
        } else {
            lead = leadsRepository.findTopByLidStatusAndTelephoneIdOrderByCreateDateDesc(
                    telephoneId, status
            );
        }

        List<LeadDTO> leadDTOs = lead.map(this::toDto).map(List::of).orElse(List.of());
        return new PageImpl<>(leadDTOs, PageRequest.of(0, 1), leadDTOs.size());
    }


    @Override
    public Page<LeadDTO> getAllLeadsToDateReSend(String status, String keywords, Principal principal, int pageNumber, int pageSize) {
        String userRole = getRole();
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createDate").descending());

        if ("ROLE_ADMIN".equals(userRole) || "ROLE_OWNER".equals(userRole)) {
            Page<Lead> leadsPage = hasText(keywords)
                    ? leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCase(status, keywords, pageable)
                    : leadsRepository.findAllByLidStatus(status, pageable);

            List<LeadDTO> leadDTOs = leadsPage.getContent().stream()
                    .map(this::toDto)
                    .filter(Objects::nonNull)
                    .filter(lead -> lead.getDateNewTry() != null
                            && !lead.getDateNewTry().isAfter(LocalDate.now()))
                    .sorted(Comparator.comparing(LeadDTO::getDateNewTry))
                    .collect(Collectors.toList());

            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
        }

        if ("ROLE_MANAGER".equals(userRole)) {
            User currentUser = requireCurrentUser(principal);
            Manager manager = managerService.getManagerByUserId(currentUser.getId());
            if (manager == null) {
                return Page.empty(pageable);
            }

            Page<Lead> leadsPage = hasText(keywords)
                    ? leadsRepository.findByLidStatusAndTelephoneLeadContainingIgnoreCaseAndManager(status, keywords, manager, pageable)
                    : leadsRepository.findAllByLidStatusAndManager(status, manager, pageable);

            List<LeadDTO> leadDTOs = leadsPage.getContent().stream()
                    .map(this::toDto)
                    .filter(Objects::nonNull)
                    .filter(lead -> lead.getDateNewTry() != null
                            && !lead.getDateNewTry().isAfter(LocalDate.now()))
                    .sorted(Comparator.comparing(LeadDTO::getDateNewTry))
                    .collect(Collectors.toList());

            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
        }

        return Page.empty(pageable);
    }

    @Override
    public Page<LeadDTO> getAllLeadsNoStatus(String keywords, Principal principal, int pageNumber, int pageSize) {
        String userRole = getRole();
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createDate").descending());

        if ("ROLE_ADMIN".equals(userRole) || "ROLE_OWNER".equals(userRole)) {
            Page<Lead> leadsPage = hasText(keywords)
                    ? leadsRepository.findByTelephoneLeadContainingIgnoreCase(keywords, pageable)
                    : leadsRepository.findAll(pageable);

            List<LeadDTO> leadDTOs = leadsPage.getContent().stream()
                    .map(this::toDto)
                    .filter(Objects::nonNull)
                    .filter(lead -> lead.getCreateDate() != null && lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
                    .sorted(Comparator.comparing(LeadDTO::getCreateDate))
                    .collect(Collectors.toList());

            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
        }

        if ("ROLE_MANAGER".equals(userRole)) {
            User currentUser = requireCurrentUser(principal);
            Manager manager = managerService.getManagerByUserId(currentUser.getId());
            if (manager == null) {
                return Page.empty(pageable);
            }

            Page<Lead> leadsPage = hasText(keywords)
                    ? leadsRepository.findByTelephoneLeadContainingIgnoreCaseAndManager(keywords, manager, pageable)
                    : leadsRepository.findAllByManager(manager, pageable);

            List<LeadDTO> leadDTOs = leadsPage.getContent().stream()
                    .map(this::toDto)
                    .filter(Objects::nonNull)
                    .filter(lead -> lead.getCreateDate() != null && lead.getCreateDate().isBefore(LocalDate.now().plusDays(1)))
                    .sorted(Comparator.comparing(LeadDTO::getCreateDate))
                    .collect(Collectors.toList());

            return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
        }

        return Page.empty(pageable);
    }

    public LeadDTO convertFromLead(Lead lead) {
        LeadDTO leadDTO = new LeadDTO();
        leadDTO.setId(lead.getId());
        leadDTO.setTelephoneLead(lead.getTelephoneLead());
        leadDTO.setCityLead(lead.getCityLead());
        leadDTO.setCommentsLead(lead.getCommentsLead());
        leadDTO.setLidStatus(lead.getLidStatus());
        leadDTO.setCreateDate(lead.getCreateDate());
        leadDTO.setUpdateStatus(lead.getUpdateStatus());
        leadDTO.setDateNewTry(lead.getDateNewTry());
        leadDTO.setOperator(lead.getOperator());
        leadDTO.setManager(lead.getManager());
        leadDTO.setMarketolog(lead.getMarketolog());
        return leadDTO;
    }

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
            telephone.setAmountSent(0);
        } else {
            telephone.setAmountSent(updatedSentCount);
        }

        telephoneService.saveTelephone(telephone);

        lead.setLidStatus(STATUS_QUEUE);
        lead.setUpdateStatus(LocalDateTime.now());
        lead.setDateNewTry(LocalDate.now().plusDays(720));

        leadsRepository.save(lead);
        leadEventPublisher.publishUpdate(lead);
    }

    @Override
    @Transactional
    public void changeStatusLeadOnSend(Long leadId) {
        Lead lead = findByLeadId(leadId).orElseThrow(() ->
                new UsernameNotFoundException(String.format("Пользователь '%s' не найден", leadId))
        );

        lead.setLidStatus(STATUS_SENT);
        lead.setUpdateStatus(LocalDateTime.now());
        lead.setDateNewTry(LocalDate.now().plusDays(1));

        leadsRepository.save(lead);
        leadEventPublisher.publishUpdate(lead);
    }

    @Override
    @Transactional
    public void changeStatusLeadToWork(Long leadId, String newComment) {
        Lead lead = findByLeadId(leadId).orElseThrow(() ->
                new UsernameNotFoundException(String.format("Пользователь с ID '%s' не найден", leadId))
        );

        if (newComment != null && !newComment.equals(lead.getCommentsLead())) {
            lead.setCommentsLead(newComment);
        }

        Operator operator = Optional.ofNullable(lead.getOperator())
                .orElseThrow(() -> new IllegalStateException("У лида не назначен оператор"));

        assignManagerBasedOnOperatorCount(lead, operator);

        lead.setLidStatus(LeadStatus.TO_WORK.title);
        leadsRepository.save(lead);

        pushToWhatsApp(lead);
        toggleOperatorManagerCount(operator);
        leadEventPublisher.publishUpdate(lead);
    }

    private void assignManagerBasedOnOperatorCount(Lead lead, Operator operator) {
        Long managerId = switch (operator.getCount()) {
            case 0 -> 2L;
            case 1 -> 3L;
            default -> throw new IllegalStateException("Неизвестное значение счетчика оператора: " + operator.getCount());
        };

        Manager manager = managerService.getManagerById(managerId);
        if (manager == null) {
            throw new IllegalStateException("Менеджер с id=" + managerId + " не найден");
        }

        lead.setManager(manager);
    }

    private void toggleOperatorManagerCount(Operator operator) {
        int oldCount = operator.getCount();
        operator.setCount(oldCount == 0 ? 1 : 0);
        operatorService.save(operator);
    }

    private void pushToWhatsApp(Lead lead) {
        if (lead.getManager() == null) {
            return;
        }

        Long managerId = lead.getManager().getId();
        String groupId = switch (managerId.intValue()) {
            case 2 -> "";
            case 3 -> "120363399937937645@g.us";
            default -> null;
        };

        String clientId = lead.getManager().getClientId();

        if (clientId == null || clientId.isBlank()) {
            return;
        }

        if (groupId != null && !groupId.isEmpty()) {
            String message = String.format("📨 Новая фирма:\n📞 %s\n🌆 %s\n💬 %s",
                    lead.getTelephoneLead(), lead.getCityLead(), lead.getCommentsLead());

            whatsAppService.sendMessageToGroup(clientId, groupId, message);
        }
    }

    @Override
    public void changeCountToOperator(Long leadId) {
        Lead lead = leadsRepository.findById(leadId).orElseThrow();

        Telephone telephone = Optional.ofNullable(lead.getTelephone())
                .orElseThrow(() -> new IllegalStateException("У лида нет телефона"));

        Operator operator = Optional.ofNullable(telephone.getTelephoneOperator())
                .orElseThrow(() -> new IllegalStateException("У телефона нет оператора"));

        operator.setCount(operator.getCount() == 0 ? 1 : 0);
        operatorService.save(operator);
    }

    @Override
    @Transactional
    public void changeStatusLeadOnReSend(Long leadId) {
        Lead lead = findByLeadId(leadId).orElseThrow(() ->
                new UsernameNotFoundException(String.format("Пользователь '%s' не найден", leadId))
        );

        lead.setLidStatus(STATUS_RESEND);
        lead.setUpdateStatus(LocalDateTime.now());
        lead.setDateNewTry(LocalDate.now().plusDays(2));

        leadsRepository.save(lead);
        leadEventPublisher.publishUpdate(lead);
    }

    @Override
    @Transactional
    public void changeStatusLeadOnArchive(Long leadId) {
        Lead lead = findByLeadId(leadId).orElseThrow(() ->
                new UsernameNotFoundException(String.format("Пользователь '%s' не найден", leadId))
        );

        lead.setLidStatus(STATUS_QUEUE);
        lead.setUpdateStatus(LocalDateTime.now());
        lead.setDateNewTry(LocalDate.now().plusDays(90));

        leadsRepository.save(lead);
        leadEventPublisher.publishUpdate(lead);
    }

    @Override
    @Transactional
    public void changeStatusLeadOnInWork(Long leadId) {
        Lead lead = findByLeadId(leadId).orElseThrow(() ->
                new UsernameNotFoundException(String.format("Пользователь '%s' не найден", leadId))
        );

        lead.setLidStatus(STATUS_IN_WORK);
        lead.setUpdateStatus(LocalDateTime.now());
        lead.setDateNewTry(LocalDate.now());

        leadsRepository.save(lead);
        leadEventPublisher.publishUpdate(lead);
    }

    @Override
    @Transactional
    public void changeStatusLeadOnNew(Long leadId) {
        Lead lead = findByLeadId(leadId).orElseThrow(() ->
                new UsernameNotFoundException(String.format("Пользователь '%s' не найден", leadId))
        );

        lead.setLidStatus(STATUS_NEW);
        lead.setUpdateStatus(LocalDateTime.now());

        leadsRepository.save(lead);
        leadEventPublisher.publishUpdate(lead);
    }


    public Optional<Lead> findByLeadId(Long leadId) {
        return leadsRepository.findById(leadId);
    }

    @Override
    public LeadDTO findById(Long leadId) {
        return toDto(leadsRepository.findByIdWithRelations(leadId).orElseThrow());
    }

    @Override
    public Optional<Lead> findByIdOptional(Long leadId) {
        return leadsRepository.findById(leadId);
    }

    @Override
    public Optional<Lead> findByIdAndToUpdate(Long id) {
        return leadsRepository.findById(id);
    }

    public Optional<User> findByUserName(String username) {
        return userRepository.findByUsername(username);
    }

    private LeadDTO toDto(Lead lead) {
        if (lead == null) {
            return null;
        }

        LeadDTO.LeadDTOBuilder builder = LeadDTO.builder()
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
                .marketolog(lead.getMarketolog());

        if (lead.getTelephone() != null && lead.getTelephone().getTelephoneOperator() != null) {
            builder.operatorId(lead.getTelephone().getTelephoneOperator().getId());
        }

        return builder.build();
    }

    public String changeNumberPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }

        String[] a = phone.split("9", 2);
        if (a.length > 1) {
            a[0] = "+79";
            String tel = a[0] + a[1];
            return tel.replace("-", "")
                    .replace("(", "")
                    .replace(")", "")
                    .replace(" ", "");
        }

        return phone;
    }

    @Override
    public List<Long> getAllLeadsByDate(LocalDate localDate) {
        return leadsRepository.findIdListByDate(localDate);
    }

    @Override
    public List<Long> getAllLeadsByDateToOwner(LocalDate localDate, Set<Manager> managerList) {
        if (managerList == null || managerList.isEmpty()) {
            return Collections.emptyList();
        }
        return leadsRepository.findIdListByDateToOwner(localDate, managerList);
    }

    @Override
    public List<Long> getAllLeadsByDateAndStatus(LocalDate localDate, String status) {
        return leadsRepository.findIdListByDate(localDate, status);
    }

    @Override
    public List<Long> getAllLeadsByDateAndStatusToOwner(LocalDate localDate, String status, Set<Manager> managerList) {
        if (managerList == null || managerList.isEmpty()) {
            return Collections.emptyList();
        }
        return leadsRepository.findIdListByDateToOwner(localDate, status, managerList);
    }

    public List<Long> getAllLeadsByDate2Month(LocalDate localDate) {
        return leadsRepository.findIdListByDate(localDate.minusMonths(1));
    }

    public List<Long> getAllLeadsByDateAndStatus2Month(LocalDate localDate, String status) {
        return leadsRepository.findIdListByDate(localDate.minusMonths(1), status);
    }

    @Override
    public Map<Long, Long> countNewLeadsByOperatorIdsToDate(List<Long> operatorIds, LocalDate localDate) {
        if (operatorIds == null || operatorIds.isEmpty()) {
            return Map.of();
        }

        return leadsRepository.countAllByOperatorIdsInMonth(operatorIds, localDate)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]
                ));
    }

    @Override
    public Map<Long, Long> countInWorkLeadsByOperatorIdsToDate(List<Long> operatorIds, LocalDate localDate) {
        if (operatorIds == null || operatorIds.isEmpty()) {
            return Map.of();
        }

        return leadsRepository.countAllByOperatorIdsAndStatusInMonth(operatorIds, STATUS_IN_WORK, localDate)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]
                ));
    }

    @Override
    public Map<Long, Long> countNewLeadsByMarketologIdsToDate(List<Long> marketologIds, LocalDate localDate) {
        if (marketologIds == null || marketologIds.isEmpty()) {
            return Map.of();
        }

        return leadsRepository.countAllByMarketologIdsInMonth(marketologIds, localDate)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]
                ));
    }

    @Override
    public Map<Long, Long> countInWorkLeadsByMarketologIdsToDate(List<Long> marketologIds, LocalDate localDate) {
        if (marketologIds == null || marketologIds.isEmpty()) {
            return Map.of();
        }

        return leadsRepository.countAllByMarketologIdsAndStatusInMonth(marketologIds, STATUS_IN_WORK, localDate)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]
                ));
    }

    @Override
    public Map<String, org.springframework.data.util.Pair<Long, Long>> getAllLeadsToMonth(String statusInWork, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        List<Object[]> results = leadsRepository.getAllLeadsToMonth(statusInWork, firstDayOfMonth, lastDayOfMonth);
        Map<String, org.springframework.data.util.Pair<Long, Long>> resultMap = new HashMap<>();

        for (Object[] row : results) {
            String operatorFio = (String) row[0];
            Long allLeadsOperator = (Long) row[1];
            Long statusInWorkOperator = (Long) row[2];

            String marketologFio = (String) row[3];
            Long allLeadsMarketolog = (Long) row[4];
            Long statusInWorkMarketolog = (Long) row[5];

            if (operatorFio != null) {
                resultMap.put(operatorFio, org.springframework.data.util.Pair.of(
                        allLeadsOperator != null ? allLeadsOperator : 0L,
                        statusInWorkOperator != null ? statusInWorkOperator : 0L
                ));
            }

            if (marketologFio != null) {
                resultMap.put(marketologFio, org.springframework.data.util.Pair.of(
                        allLeadsMarketolog != null ? allLeadsMarketolog : 0L,
                        statusInWorkMarketolog != null ? statusInWorkMarketolog : 0L
                ));
            }
        }

        return resultMap;
    }

    @Override
    public Map<String, Long> getAllLeadsToMonthToManager(String status, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        List<Object[]> results = leadsRepository.getAllLeadsToMonthToManager(status, firstDayOfMonth, lastDayOfMonth);
        Map<String, Long> resultMap = new HashMap<>();

        for (Object[] row : results) {
            String managerFio = (String) row[0];
            Long allLeadsManager = row[1] != null ? ((Number) row[1]).longValue() : 0L;

            if (managerFio != null) {
                resultMap.put(managerFio, allLeadsManager);
            }
        }

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
        return leadsRepository.countByTelephone_IdAndCreateDateLessThanEqualAndLidStatus(
                telephoneId, LocalDate.now(), status
        );
    }

    @Override
    public LeadDtoTransfer findByIdToTransfer(Long leadId) {
        return leadMapper.toDtoTransfer(leadsRepository.findById(leadId).orElseThrow());
    }

    @Override
    public List<Lead> findModifiedSince(LocalDateTime since) {
        return leadsRepository.findByUpdateStatusAfter(since);
    }

    @Override
    @Transactional
    public void saveOrUpdateByTelephoneLead(Lead incomingLead) {
        log.info("saveOrUpdateByTelephoneLead: {}", incomingLead.getTelephoneLead());

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
        } else {
            leadsRepository.save(incomingLead);
        }
    }

    private Page<LeadDTO> toLeadDtoPage(Page<Lead> leadsPage, Pageable pageable) {
        List<LeadDTO> leadDTOs = leadsPage.getContent()
                .stream()
                .map(this::toDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return new PageImpl<>(leadDTOs, pageable, leadsPage.getTotalElements());
    }

    private User requireCurrentUser(Principal principal) {
        if (principal == null) {
            throw new UsernameNotFoundException("Principal == null");
        }

        return userService.findByUserName(principal.getName())
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден: " + principal.getName()));
    }

    private String getRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "";
        }

        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElse("");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String safeTrimLower(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
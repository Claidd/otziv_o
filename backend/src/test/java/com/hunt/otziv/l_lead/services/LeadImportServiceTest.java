package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.model.LeadImportTelephonePool;
import com.hunt.otziv.l_lead.model.Telephone;
import com.hunt.otziv.l_lead.repository.LeadImportTelephonePoolRepository;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.MarketologRepository;
import com.hunt.otziv.u_users.repository.OperatorRepository;
import com.hunt.otziv.uploads.service.FileUploadGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeadImportServiceTest {

    @Mock
    private LeadsRepository leadsRepository;
    @Mock
    private OperatorRepository operatorRepository;
    @Mock
    private ManagerRepository managerRepository;
    @Mock
    private MarketologRepository marketologRepository;
    @Mock
    private TelephoneRepository telephoneRepository;
    @Mock
    private LeadImportTelephonePoolRepository telephonePoolRepository;

    private LeadImportService service;

    @BeforeEach
    void setUp() {
        service = new LeadImportService(
                leadsRepository,
                operatorRepository,
                managerRepository,
                marketologRepository,
                telephoneRepository,
                telephonePoolRepository,
                new FileUploadGuard(
                        5 * 1024 * 1024,
                        20_000_000,
                        8000,
                        8000,
                        5 * 1024 * 1024,
                        5000
                )
        );
    }

    @Test
    void importLeadsDetectsBaseStoreHeaderAndMapsBusinessFields() {
        when(leadsRepository.findExistingTelephoneLeads(anyCollection())).thenReturn(List.of());
        when(leadsRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String csv = """
                BASE STORE;Базы данных компаний РФ;;;;;;;;;;;
                ;;;;;;;;;;;;
                Наименование;Телефоны;Мобильные;WhatsApp;Емейлы;Сайты;VK;TG;Отрасли;Тип;Регион;Город;Адрес
                Монолит недвижимость;88003334738;+79183008003;+79183008003;expert@monolit.test;monolith-realty.test;https://vk.com/monolith_realty;https://t.me/monolitrealty;Агентства недвижимости;Оформление;Краснодарский;Краснодар;Уральская, 75/1 лит Б
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "base.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );

        LeadImportService.LeadImportResult result = service.importLeads(file);

        assertEquals(1, result.totalRows());
        assertEquals(1, result.added());
        assertEquals(0, result.skippedWithoutPhones());
        assertEquals(0, result.skippedInvalid());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Lead>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(leadsRepository).saveAll(captor.capture());
        Lead lead = StreamSupport.stream(captor.getValue().spliterator(), false)
                .findFirst()
                .orElseThrow();

        assertEquals("79183008003", lead.getTelephoneLead());
        assertEquals("Монолит недвижимость", lead.getCompanyName());
        assertEquals("88003334738", lead.getPhones());
        assertEquals("+79183008003", lead.getMobilePhones());
        assertEquals("+79183008003", lead.getWhatsappPhones());
        assertEquals("expert@monolit.test", lead.getEmails());
        assertEquals("monolith-realty.test", lead.getWebsites());
        assertEquals("https://vk.com/monolith_realty", lead.getVkUrl());
        assertEquals("https://t.me/monolitrealty", lead.getTelegramUrl());
        assertEquals("Агентства недвижимости", lead.getIndustries());
        assertEquals("Оформление", lead.getCompanyType());
        assertEquals("Краснодарский", lead.getRegion());
        assertEquals("Краснодар", lead.getCityLead());
        assertEquals("Уральская, 75/1 лит Б", lead.getAddress());
    }

    @Test
    void importLeadsDistributesRowsAcrossSelectedManagersAndTelephonePools() {
        when(leadsRepository.findExistingTelephoneLeads(anyCollection())).thenReturn(List.of());
        when(leadsRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Manager manager2 = Manager.builder().id(2L).build();
        Manager manager3 = Manager.builder().id(3L).build();
        when(managerRepository.findById(2L)).thenReturn(Optional.of(manager2));
        when(managerRepository.findById(3L)).thenReturn(Optional.of(manager3));
        when(telephonePoolRepository.findActiveByManagerIds(anyCollection())).thenReturn(List.of(
                pool(manager2, 1L, 1),
                pool(manager2, 2L, 2),
                pool(manager3, 16L, 1),
                pool(manager3, 17L, 2)
        ));

        String csv = """
                Наименование;Телефоны;Мобильные;WhatsApp;Город
                Первый;+78612002001;+79182002001;+79992002001;Краснодар
                Второй;+78612002002;+79182002002;;Сочи
                Третий;+78612002003;+79182002003;;Анапа
                Четвертый;+78612002004;+79182002004;;Геленджик
                Пятый;+78612002005;+79182002005;;Майкоп
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "base.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );

        LeadImportService.LeadImportResult result = service.importLeads(
                file,
                new LeadImportService.LeadImportOptions(List.of(2L, 3L), null, null)
        );

        assertEquals(5, result.totalRows());
        assertEquals(5, result.added());
        assertEquals(0, result.skippedWithoutPhones());
        assertEquals(2, result.managerAssignments().size());
        assertEquals(3, result.managerAssignments().get(0).added());
        assertEquals(2, result.managerAssignments().get(1).added());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Lead>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(leadsRepository).saveAll(captor.capture());
        List<Lead> leads = StreamSupport.stream(captor.getValue().spliterator(), false).toList();

        assertEquals("79992002001", leads.get(0).getTelephoneLead());
        assertEquals(2L, leads.get(0).getManager().getId());
        assertEquals(1L, leads.get(0).getTelephone().getId());
        assertEquals(3L, leads.get(1).getManager().getId());
        assertEquals(16L, leads.get(1).getTelephone().getId());
        assertEquals(2L, leads.get(2).getManager().getId());
        assertEquals(2L, leads.get(2).getTelephone().getId());
        assertEquals(3L, leads.get(3).getManager().getId());
        assertEquals(17L, leads.get(3).getTelephone().getId());
        assertEquals(2L, leads.get(4).getManager().getId());
        assertEquals(1L, leads.get(4).getTelephone().getId());
        assertEquals(LocalDate.now(), leads.get(0).getCreateDate());
        assertEquals(LocalDate.now(), leads.get(0).getDateNewTry());
    }

    @Test
    void importLeadsReportsRowsWithoutUsablePhonesSeparatelyFromErrors() {
        when(leadsRepository.findExistingTelephoneLeads(anyCollection())).thenReturn(List.of());
        when(leadsRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String csv = """
                Наименование;Телефоны;Мобильные;WhatsApp;Город
                С телефоном;;+79182002001;;Краснодар
                Без телефона;;;;Сочи
                Маска;+749#329#601;;;Ковров
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "base.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );

        LeadImportService.LeadImportResult result = service.importLeads(file);

        assertEquals(3, result.totalRows());
        assertEquals(1, result.added());
        assertEquals(2, result.skippedWithoutPhones());
        assertEquals(0, result.skippedInvalid());
        assertEquals(List.of(), result.errors());
    }

    @Test
    void importLeadsKeepsCommaScientificPhoneAsOneValue() {
        when(leadsRepository.findExistingTelephoneLeads(anyCollection())).thenReturn(List.of());
        when(leadsRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String csv = """
                telephone_lead;city_lead
                7,91E+10;Краснодар
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "leads.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );

        LeadImportService.LeadImportResult result = service.importLeads(file);

        assertEquals(1, result.totalRows());
        assertEquals(1, result.added());
        assertEquals(0, result.skippedWithoutPhones());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Lead>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(leadsRepository).saveAll(captor.capture());
        Lead lead = StreamSupport.stream(captor.getValue().spliterator(), false)
                .findFirst()
                .orElseThrow();

        assertEquals("79100000000", lead.getTelephoneLead());
    }

    private LeadImportTelephonePool pool(Manager manager, Long telephoneId, int priority) {
        return LeadImportTelephonePool.builder()
                .manager(manager)
                .telephone(Telephone.builder().id(telephoneId).build())
                .active(true)
                .priorityOrder(priority)
                .build();
    }
}

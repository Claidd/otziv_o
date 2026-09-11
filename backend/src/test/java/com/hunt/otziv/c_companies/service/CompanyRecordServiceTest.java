package com.hunt.otziv.c_companies.service;

import com.hunt.otziv.c_companies.api.CompanyRecordOperations;
import com.hunt.otziv.c_companies.api.CompanyStatisticsOperations;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyInfo;
import com.hunt.otziv.c_companies.repository.CompanyInfoRepository;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CompanyRecordServiceTest {
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final CompanyInfoRepository infos = mock(CompanyInfoRepository.class);
    private final CompanyRecordService records = new CompanyRecordService(companies, infos);

    @Test
    void lookupReturnsTheRepositoryEntityAndPreservesMissingCompanyError() {
        Company company = new Company();
        company.setId(12L);
        when(companies.findById(12L)).thenReturn(Optional.of(company));
        when(companies.findById(13L)).thenReturn(Optional.empty());

        assertThat(records.getCompaniesById(12L)).isSameAs(company);
        assertThatThrownBy(() -> records.getCompaniesById(13L))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("Компания '13' не найден");
        verifyNoInteractions(infos);
    }

    @Test
    void saveUsesManagedCompanyThenPersistsInfoBackReferenceAndRetainsManagedInfo() {
        Company submitted = new Company();
        Company managed = new Company();
        managed.setId(12L);
        CompanyInfo submittedInfo = new CompanyInfo();
        CompanyInfo managedInfo = new CompanyInfo();
        managedInfo.setId(22L);
        managed.setInfo(submittedInfo);
        when(companies.save(submitted)).thenReturn(managed);
        when(infos.save(submittedInfo)).thenAnswer(invocation -> {
            assertThat(submittedInfo.getCompany()).isSameAs(managed);
            return managedInfo;
        });

        records.save(submitted);

        var writes = inOrder(companies, infos);
        writes.verify(companies).save(submitted);
        writes.verify(infos).save(submittedInfo);
        writes.verifyNoMoreInteractions();
        assertThat(managed.getInfo()).isSameAs(managedInfo);
        assertThat(submitted.getInfo()).isNull();
    }

    @Test
    void saveWithoutInfoDoesNotCreateAnInfoRecord() {
        Company company = new Company();
        when(companies.save(company)).thenReturn(company);

        records.save(company);

        verify(companies).save(company);
        verifyNoInteractions(infos);
    }

    @Test
    void failedCompanyWriteNeverAttemptsInfoWrite() {
        Company company = new Company();
        company.setInfo(new CompanyInfo());
        IllegalStateException failure = new IllegalStateException("company write failed");
        when(companies.save(company)).thenThrow(failure);

        assertThatThrownBy(() -> records.save(company)).isSameAs(failure);

        verifyNoInteractions(infos);
    }

    @Test
    void failedInfoWritePropagatesToTheOwningTransaction() {
        Company company = new Company();
        CompanyInfo info = new CompanyInfo();
        company.setInfo(info);
        when(companies.save(company)).thenReturn(company);
        IllegalStateException failure = new IllegalStateException("info write failed");
        when(infos.save(info)).thenThrow(failure);

        assertThatThrownBy(() -> records.save(company)).isSameAs(failure);

        assertThat(info.getCompany()).isSameAs(company);
    }

    @Test
    void createPathHelperRetainsNullCompatibility() {
        records.persistTransientCompanyInfo(null);
        records.persistTransientCompanyInfo(new Company());
        verifyNoInteractions(companies, infos);
    }

    @Test
    void statisticsPreservesTheQueryBoundsAndRepositoryRowOrder() {
        LocalDate first = LocalDate.of(2026, 8, 1);
        LocalDate last = LocalDate.of(2026, 8, 31);
        List<Object[]> rows = List.of(new Object[]{"manager-z", 3L}, new Object[]{"manager-a", 8L});
        when(companies.getAllNewCompanies(first, last)).thenReturn(rows);

        assertThat(new CompanyStatisticsService(companies).countNewCompaniesByManager(first, last))
                .isSameAs(rows);

        verify(companies).getAllNewCompanies(first, last);
        verifyNoMoreInteractions(companies);
    }

    @Test
    void companyRecordAndStatisticsApisStartWithoutTheCompanyFacadeOrOrderWorkflows() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(CompanyRepository.class, () -> companies);
            context.registerBean(CompanyInfoRepository.class, () -> infos);
            context.register(CompanyRecordService.class, CompanyStatisticsService.class);
            context.refresh();

            assertThat(context.getBean(CompanyRecordOperations.class)).isInstanceOf(CompanyRecordService.class);
            assertThat(context.getBean(CompanyStatisticsOperations.class)).isInstanceOf(CompanyStatisticsService.class);
            assertThat(context.getBeansOfType(CompanyService.class)).isEmpty();
        }
    }
}

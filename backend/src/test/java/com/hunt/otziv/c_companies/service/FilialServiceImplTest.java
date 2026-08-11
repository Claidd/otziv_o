package com.hunt.otziv.c_companies.service;

import com.hunt.otziv.c_cities.repository.CityRepository;
import com.hunt.otziv.c_companies.dto.FilialDeletionPreview;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.FilialRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FilialServiceImplTest {

    private FilialRepository filialRepository;
    private OrderRepository orderRepository;
    private ReviewRepository reviewRepository;
    private FilialServiceImpl service;

    @BeforeEach
    void setUp() {
        filialRepository = mock(FilialRepository.class);
        orderRepository = mock(OrderRepository.class);
        reviewRepository = mock(ReviewRepository.class);
        service = new FilialServiceImpl(
                filialRepository,
                mock(CityRepository.class),
                orderRepository,
                reviewRepository
        );
    }

    @Test
    void archivesFilialWhenOrdersExist() {
        Filial filial = filial(20L, 10L);
        when(filialRepository.findById(20L)).thenReturn(Optional.of(filial));
        when(orderRepository.countByFilial_Id(20L)).thenReturn(3L);

        FilialDeletionPreview result = service.deleteOrArchive(10L, 20L);

        assertTrue(result.willArchive());
        assertTrue(filial.isArchived());
        assertNotNull(filial.getArchivedAt());
        verify(filialRepository).save(filial);
        verify(filialRepository, never()).delete(filial);
    }

    @Test
    void archivesFilialWhenOnlyDirectReviewsExist() {
        Filial filial = filial(20L, 10L);
        when(filialRepository.findById(20L)).thenReturn(Optional.of(filial));
        when(reviewRepository.countByFilial_Id(20L)).thenReturn(2L);

        FilialDeletionPreview result = service.deleteOrArchive(10L, 20L);

        assertTrue(result.willArchive());
        assertTrue(filial.isArchived());
        verify(filialRepository).save(filial);
        verify(filialRepository, never()).delete(filial);
    }

    @Test
    void physicallyDeletesUnusedFilial() {
        Filial filial = filial(20L, 10L);
        when(filialRepository.findById(20L)).thenReturn(Optional.of(filial));

        FilialDeletionPreview result = service.deleteOrArchive(10L, 20L);

        assertFalse(result.willArchive());
        verify(filialRepository).delete(filial);
        verify(filialRepository, never()).save(filial);
    }

    @Test
    void restoresArchivedFilial() {
        Filial filial = filial(20L, 10L);
        filial.setArchived(true);
        filial.setArchivedAt(java.time.LocalDateTime.now());
        when(filialRepository.findById(20L)).thenReturn(Optional.of(filial));

        service.restoreFilial(10L, 20L);

        assertFalse(filial.isArchived());
        verify(filialRepository).save(filial);
    }

    private Filial filial(Long filialId, Long companyId) {
        return Filial.builder()
                .id(filialId)
                .company(Company.builder().id(companyId).build())
                .title("Филиал")
                .url("https://example.test")
                .build();
    }
}

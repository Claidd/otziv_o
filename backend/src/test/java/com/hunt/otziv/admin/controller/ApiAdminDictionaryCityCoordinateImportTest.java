package com.hunt.otziv.admin.controller;

import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.repository.CityRepository;
import com.hunt.otziv.c_cities.service.CityDistanceService;
import com.hunt.otziv.uploads.service.FileUploadGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dictionaries page shows the first row errors of a coordinate import to the owner: they must read as Russian
 * sentences, without Java exception text or the HTTP status prefix of a ResponseStatusException.
 */
class ApiAdminDictionaryCityCoordinateImportTest {

    private final CityRepository cityRepository = mock(CityRepository.class);
    private final CityDistanceService cityDistanceService = mock(CityDistanceService.class);
    private final FileUploadGuard fileUploadGuard = mock(FileUploadGuard.class);
    private final ApiAdminDictionaryController controller = mock(ApiAdminDictionaryController.class, CALLS_REAL_METHODS);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "cityRepository", cityRepository);
        ReflectionTestUtils.setField(controller, "cityDistanceService", cityDistanceService);
        ReflectionTestUtils.setField(controller, "fileUploadGuard", fileUploadGuard);
        when(fileUploadGuard.requireSupportedImportFile(any())).thenReturn("csv");
        when(cityDistanceService.rebuildForCityIdsFrom(150)).thenReturn(new CityDistanceService.RebuildResult(1, 0, 0));
    }

    @Test
    void aByteOrderMarkDoesNotCostTheFirstRow() {
        City city = new City();
        when(cityRepository.findById(5L)).thenReturn(city);
        MockMultipartFile file = new MockMultipartFile("file", "coordinates.csv", "text/csv",
                "﻿5;52.28;104.30\n".getBytes(StandardCharsets.UTF_8));

        ApiAdminDictionaryController.CityCoordinateImportResponse response = controller.importCityCoordinates(file);

        assertThat(response.updated()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
    }

    @Test
    void rowErrorsReadAsSentencesAndGoodRowsAreStillSaved() {
        City city = new City();
        when(cityRepository.findById(5L)).thenReturn(city);
        MockMultipartFile file = new MockMultipartFile("file", "coordinates.csv", "text/csv",
                "city_id;latitude;longitude\n5;52,28;104,30\nabc;1;2\n6;95;10\n7;1\n".getBytes(StandardCharsets.UTF_8));

        ApiAdminDictionaryController.CityCoordinateImportResponse response = controller.importCityCoordinates(file);

        assertThat(response.updated()).isEqualTo(1);
        assertThat(response.skipped()).isEqualTo(3);
        assertThat(response.errors()).containsExactly(
                "Строка 3: ID города, широта и долгота должны быть числами",
                "Строка 4: Широта: значение должно быть от -90 до 90",
                "Строка 5: нужны ID города, широта и долгота");
        assertThat(city.getLatitude()).isEqualByComparingTo(new BigDecimal("52.28"));
        assertThat(city.getLongitude()).isEqualByComparingTo(new BigDecimal("104.30"));
        verify(cityRepository).save(city);
    }
}

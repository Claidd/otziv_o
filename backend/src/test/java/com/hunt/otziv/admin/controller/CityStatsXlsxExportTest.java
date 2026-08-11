package com.hunt.otziv.admin.controller;

import com.hunt.otziv.r_review.dto.CityWithUnpublishedReviewsDTO;
import com.hunt.otziv.r_review.service.ReviewCityService;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CityStatsXlsxExportTest {

    @Mock
    private ReviewCityService reviewCityService;

    private List<CityWithUnpublishedReviewsDTO> cities;

    @BeforeEach
    void setUp() {
        cities = IntStream.rangeClosed(1, 250)
                .mapToObj(index -> new CityWithUnpublishedReviewsDTO(
                        (long) index,
                        "Город " + index,
                        (long) index + 10,
                        (long) index,
                        index + 2
                ))
                .toList();
        when(reviewCityService.getAllCitiesWithUnpublishedReviewsNoPagination(isNull(), isNull(), isNull()))
                .thenReturn(cities);
    }

    @Test
    void apiExportRemainsReadableAfterRowsHaveBeenStreamed() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiAdminCityStatsController(reviewCityService).exportAll(null, null, null, response);

        assertWorkbook(response);
    }

    @Test
    void legacyExportUsesTheSameStreamingWorkbookContract() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AdminCityController(reviewCityService).exportAllCitiesToExcel(null, null, null, response);

        assertWorkbook(response);
    }

    private void assertWorkbook(MockHttpServletResponse response) throws Exception {
        assertEquals(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                response.getContentType()
        );

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            Sheet sheet = workbook.getSheet("Статистика по городам");
            assertEquals("№", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Город 1", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("Город 250", sheet.getRow(250).getCell(1).getStringCellValue());

            Row totals = sheet.getRow(251);
            assertEquals("ИТОГО:", totals.getCell(0).getStringCellValue());
            assertEquals("250 городов", totals.getCell(1).getStringCellValue());
            assertEquals(cities.stream().mapToLong(CityWithUnpublishedReviewsDTO::getUnpublishedCount).sum(),
                    (long) totals.getCell(3).getNumericCellValue());
            assertEquals(1, sheet.getPaneInformation().getHorizontalSplitPosition());
        }
    }
}

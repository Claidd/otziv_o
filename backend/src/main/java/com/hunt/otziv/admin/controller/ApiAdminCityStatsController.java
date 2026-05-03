package com.hunt.otziv.admin.controller;

import com.hunt.otziv.r_review.dto.CityWithUnpublishedReviewsDTO;
import com.hunt.otziv.r_review.services.ReviewCityService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/admin/cities")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class ApiAdminCityStatsController {

    private final ReviewCityService reviewCityService;

    @GetMapping("/board")
    public CityStatsBoardResponse getBoard(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.info("API cities board: search={}, sort={}, direction={}, page={}, size={}",
                search, sort, direction, page, size);

        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 100));
        List<CityWithUnpublishedReviewsDTO> filteredCities = filterAndSort(
                reviewCityService.getCitiesWithUnpublishedReviews(),
                search,
                sort,
                direction
        );

        int totalCities = filteredCities.size();
        int totalPages = (int) Math.ceil((double) totalCities / safeSize);
        int start = Math.min(safePage * safeSize, totalCities);
        int end = Math.min(start + safeSize, totalCities);

        Map<String, Object> statistics = reviewCityService.getCitiesStatistics();

        return new CityStatsBoardResponse(
                filteredCities.subList(start, end).stream().map(this::toCityResponse).toList(),
                toStatisticsResponse(totalCities, statistics),
                new CityStatsPageResponse(safePage, safeSize, totalCities, totalPages, safePage == 0, safePage + 1 >= totalPages),
                search == null ? "" : search,
                sort,
                actualDirection(sort, direction),
                LocalDateTime.now()
        );
    }

    @GetMapping("/export-all")
    public void exportAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            HttpServletResponse response
    ) throws IOException {
        List<CityWithUnpublishedReviewsDTO> cities = reviewCityService
                .getAllCitiesWithUnpublishedReviewsNoPagination(search, sort, direction);

        if (cities.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Нет данных для экспорта");
        }

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Статистика по городам");
            Map<String, CellStyle> styles = createStyles(workbook);
            String[] headers = {
                    "№", "Город", "ID",
                    "Неопубликованных (все)", "Неопубликованных (без архивных)",
                    "Архивные отзывы", "Доступные аккаунты", "Баланс (+/-)",
                    "Статус", "Процент (%)", "Дата выгрузки"
            };

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(styles.get("header"));
            }

            int rowNum = 1;
            long totalUnpublished = 0;
            long totalNotArchive = 0;
            long totalBots = 0;
            long totalBalance = 0;

            for (int i = 0; i < cities.size(); i++) {
                CityWithUnpublishedReviewsDTO city = cities.get(i);
                Row row = sheet.createRow(rowNum++);

                long unpublishedCount = city.getUnpublishedCount();
                long notArchiveCount = city.getUnpublishedNotArchiveCount();
                long activeBotsCount = city.getActiveBotsCount();
                long botBalance = city.getBotBalance();

                row.createCell(0).setCellValue(i + 1);
                row.createCell(1).setCellValue(city.getCityTitle());
                row.createCell(2).setCellValue(city.getCityId());

                Cell allCell = row.createCell(3);
                allCell.setCellValue(unpublishedCount);
                allCell.setCellStyle(getCountStyle((int) unpublishedCount,
                        styles.get("green"), styles.get("yellow"), styles.get("red"), styles.get("data")));

                Cell notArchiveCell = row.createCell(4);
                notArchiveCell.setCellValue(notArchiveCount);
                notArchiveCell.setCellStyle(styles.get("blue"));

                row.createCell(5).setCellValue(unpublishedCount - notArchiveCount);
                row.createCell(6).setCellValue(activeBotsCount);

                Cell balanceCell = row.createCell(7);
                balanceCell.setCellValue(botBalance);
                if (botBalance > 0) {
                    balanceCell.setCellStyle(styles.get("positive"));
                } else if (botBalance < 0) {
                    balanceCell.setCellStyle(styles.get("negative"));
                } else {
                    balanceCell.setCellStyle(styles.get("data"));
                }

                row.createCell(8).setCellValue(city.getBotStatus());
                row.createCell(9).setCellValue(String.format(Locale.ROOT, "%.2f", city.getBotPercentage()));
                row.createCell(10).setCellValue(new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date()));

                totalUnpublished += unpublishedCount;
                totalNotArchive += notArchiveCount;
                totalBots += activeBotsCount;
                totalBalance += botBalance;
            }

            Row totalRow = sheet.createRow(rowNum);
            CellStyle totalStyle = workbook.createCellStyle();
            Font totalFont = workbook.createFont();
            totalFont.setBold(true);
            totalStyle.setFont(totalFont);
            totalStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            totalStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            totalRow.createCell(0).setCellValue("ИТОГО:");
            totalRow.getCell(0).setCellStyle(totalStyle);
            totalRow.createCell(1).setCellValue(cities.size() + " городов");
            totalRow.getCell(1).setCellStyle(totalStyle);
            totalRow.createCell(3).setCellValue(totalUnpublished);
            totalRow.getCell(3).setCellStyle(totalStyle);
            totalRow.createCell(4).setCellValue(totalNotArchive);
            totalRow.getCell(4).setCellStyle(totalStyle);
            totalRow.createCell(5).setCellValue(totalUnpublished - totalNotArchive);
            totalRow.getCell(5).setCellStyle(totalStyle);
            totalRow.createCell(6).setCellValue(totalBots);
            totalRow.getCell(6).setCellStyle(totalStyle);
            totalRow.createCell(7).setCellValue(totalBalance);
            totalRow.getCell(7).setCellStyle(totalStyle);
            totalRow.createCell(10).setCellValue(new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date()));
            totalRow.getCell(10).setCellStyle(totalStyle);

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            sheet.createFreezePane(0, 1);

            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            String fileName = String.format("cities_full_export_%s.xlsx",
                    new SimpleDateFormat("yyyy-MM-dd_HH-mm").format(new Date()));
            response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            workbook.write(response.getOutputStream());
        }
    }

    private List<CityWithUnpublishedReviewsDTO> filterAndSort(
            List<CityWithUnpublishedReviewsDTO> cities,
            String search,
            String sort,
            String direction
    ) {
        String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<CityWithUnpublishedReviewsDTO> filtered = cities.stream()
                .filter(city -> query.isBlank() || city.getCityTitle().toLowerCase(Locale.ROOT).contains(query))
                .collect(Collectors.toList());

        Comparator<CityWithUnpublishedReviewsDTO> comparator;
        if (sort == null || sort.isBlank()) {
            comparator = Comparator.comparing(CityWithUnpublishedReviewsDTO::getUnpublishedCount).reversed();
        } else {
            comparator = getComparator(sort);
            if ("desc".equalsIgnoreCase(actualDirection(sort, direction))) {
                comparator = comparator.reversed();
            }
        }

        return filtered.stream().sorted(comparator).toList();
    }

    private Comparator<CityWithUnpublishedReviewsDTO> getComparator(String sort) {
        return switch (sort) {
            case "name" -> Comparator.comparing(CityWithUnpublishedReviewsDTO::getCityTitle);
            case "countAll" -> Comparator.comparing(CityWithUnpublishedReviewsDTO::getUnpublishedCount);
            case "countArchive" -> Comparator.comparing(CityWithUnpublishedReviewsDTO::getUnpublishedNotArchiveCount);
            case "bots" -> Comparator.comparing(CityWithUnpublishedReviewsDTO::getActiveBotsCount);
            case "balance" -> Comparator.comparing(CityWithUnpublishedReviewsDTO::getBotBalance);
            default -> Comparator.comparing(CityWithUnpublishedReviewsDTO::getUnpublishedCount);
        };
    }

    private String actualDirection(String sort, String direction) {
        if (direction != null && !direction.isBlank()) {
            return direction;
        }

        if (sort == null || sort.isBlank()) {
            return "desc";
        }

        return "name".equals(sort) ? "asc" : "desc";
    }

    private CityStatsItemResponse toCityResponse(CityWithUnpublishedReviewsDTO city) {
        long unpublished = city.getUnpublishedCount();
        long notArchive = city.getUnpublishedNotArchiveCount();
        long balance = city.getBotBalance();
        double percentage = city.getBotPercentage() == null ? 0.0 : city.getBotPercentage();

        return new CityStatsItemResponse(
                city.getCityId(),
                city.getCityTitle(),
                unpublished,
                notArchive,
                city.getActiveBotsCount(),
                balance,
                city.getBotStatus(),
                percentage,
                city.getBotStatusCssClass(),
                unpublished > 15 ? "high" : (unpublished > 5 ? "medium" : "low"),
                balance > 0 ? "positive" : (balance < 0 ? "negative" : "neutral"),
                percentage > 300 ? "green" : (percentage > 100 ? "yellow" : (percentage > 50 ? "blue" : "neutral"))
        );
    }

    private CityStatsResponse toStatisticsResponse(int totalCities, Map<String, Object> statistics) {
        long totalUnpublished = number(statistics.get("totalUnpublished"));
        long totalUnpublishedNotArchive = number(statistics.get("totalUnpublishedNotArchive"));
        return new CityStatsResponse(
                totalCities,
                totalUnpublished,
                totalUnpublishedNotArchive,
                number(statistics.get("totalActiveBots")),
                number(statistics.get("totalBotBalance")),
                number(statistics.get("averagePerCity")),
                number(statistics.get("averageNotArchivePerCity")),
                number(statistics.get("averageBotsPerCity")),
                number(statistics.get("averageBalancePerCity")),
                number(statistics.get("criticalCount")),
                number(statistics.get("excessCount")),
                totalUnpublished - totalUnpublishedNotArchive
        );
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Map<String, CellStyle> createStyles(Workbook workbook) {
        Map<String, CellStyle> styles = new HashMap<>();

        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setBorderBottom(BorderStyle.THIN);
        styles.put("header", headerStyle);

        CellStyle dataStyle = workbook.createCellStyle();
        dataStyle.setAlignment(HorizontalAlignment.CENTER);
        dataStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        dataStyle.setBorderBottom(BorderStyle.THIN);
        styles.put("data", dataStyle);

        CellStyle greenStyle = workbook.createCellStyle();
        greenStyle.cloneStyleFrom(dataStyle);
        greenStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        greenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        styles.put("green", greenStyle);

        CellStyle yellowStyle = workbook.createCellStyle();
        yellowStyle.cloneStyleFrom(dataStyle);
        yellowStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        yellowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        styles.put("yellow", yellowStyle);

        CellStyle redStyle = workbook.createCellStyle();
        redStyle.cloneStyleFrom(dataStyle);
        redStyle.setFillForegroundColor(IndexedColors.LIGHT_ORANGE.getIndex());
        redStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        styles.put("red", redStyle);

        CellStyle blueStyle = workbook.createCellStyle();
        blueStyle.cloneStyleFrom(dataStyle);
        blueStyle.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        blueStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        styles.put("blue", blueStyle);

        CellStyle positiveStyle = workbook.createCellStyle();
        positiveStyle.cloneStyleFrom(dataStyle);
        Font positiveFont = workbook.createFont();
        positiveFont.setColor(IndexedColors.GREEN.getIndex());
        positiveStyle.setFont(positiveFont);
        styles.put("positive", positiveStyle);

        CellStyle negativeStyle = workbook.createCellStyle();
        negativeStyle.cloneStyleFrom(dataStyle);
        Font negativeFont = workbook.createFont();
        negativeFont.setColor(IndexedColors.RED.getIndex());
        negativeStyle.setFont(negativeFont);
        styles.put("negative", negativeStyle);

        return styles;
    }

    private CellStyle getCountStyle(int count, CellStyle green, CellStyle yellow, CellStyle red, CellStyle defaultStyle) {
        if (count <= 5) {
            return green;
        }
        if (count <= 15) {
            return yellow;
        }
        return red == null ? defaultStyle : red;
    }

    public record CityStatsBoardResponse(
            List<CityStatsItemResponse> cities,
            CityStatsResponse statistics,
            CityStatsPageResponse page,
            String search,
            String sort,
            String direction,
            LocalDateTime generatedAt
    ) {
    }

    public record CityStatsItemResponse(
            Long cityId,
            String cityTitle,
            long unpublishedCount,
            long unpublishedNotArchiveCount,
            long activeBotsCount,
            long botBalance,
            String botStatus,
            double botPercentage,
            String botStatusCssClass,
            String countTone,
            String balanceTone,
            String percentageTone
    ) {
    }

    public record CityStatsResponse(
            long totalCities,
            long totalUnpublished,
            long totalUnpublishedNotArchive,
            long totalActiveBots,
            long totalBotBalance,
            long averagePerCity,
            long averageNotArchivePerCity,
            long averageBotsPerCity,
            long averageBalancePerCity,
            long criticalCount,
            long excessCount,
            long archivedCount
    ) {
    }

    public record CityStatsPageResponse(
            int pageNumber,
            int pageSize,
            int totalElements,
            int totalPages,
            boolean first,
            boolean last
    ) {
    }
}

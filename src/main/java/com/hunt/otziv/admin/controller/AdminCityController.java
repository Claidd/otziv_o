package com.hunt.otziv.admin.controller;

import com.hunt.otziv.r_review.dto.CityWithUnpublishedReviewsDTO;
import com.hunt.otziv.r_review.services.ReviewCityService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;


import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/admin/cities")
public class AdminCityController {

    private final ReviewCityService reviewCityService;

    @GetMapping
    public String getCitiesWithUnpublishedReviews(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model) {

        log.info("Получение списка городов с неопубликованными отзывами: "
                        + "search={}, sort={}, direction={}, page={}, size={}",
                search, sort, direction, page, size);

        List<CityWithUnpublishedReviewsDTO> allCities = reviewCityService.getCitiesWithUnpublishedReviews();

        // Применяем поиск
        List<CityWithUnpublishedReviewsDTO> filteredCities = allCities;
        if (search != null && !search.trim().isEmpty()) {
            filteredCities = allCities.stream()
                    .filter(city -> city.getCityTitle().toLowerCase()
                            .contains(search.toLowerCase().trim()))
                    .collect(Collectors.toList());
        }

        // Применяем сортировку с учетом направления
        if (sort != null) {
            Comparator<CityWithUnpublishedReviewsDTO> comparator = getComparator(sort);

            // Устанавливаем направление по умолчанию в зависимости от типа сортировки
            String actualDirection = direction;
            if (direction == null) {
                // Для названия - по возрастанию, для чисел - по убыванию
                actualDirection = "name".equals(sort) ? "asc" : "desc";
            }

            // Определяем нужно ли инвертировать компаратор
            boolean reverse = "desc".equalsIgnoreCase(actualDirection);

            if (reverse) {
                comparator = comparator.reversed();
            }

            filteredCities = filteredCities.stream()
                    .sorted(comparator)
                    .collect(Collectors.toList());

            // Сохраняем фактическое направление для отображения в UI
            model.addAttribute("actualDirection", actualDirection);
        } else {
            // Сортировка по умолчанию: по количеству отзывов (DESC)
            filteredCities = filteredCities.stream()
                    .sorted(Comparator.comparing(CityWithUnpublishedReviewsDTO::getUnpublishedCount)
                            .reversed())
                    .collect(Collectors.toList());
        }

        // Пагинация
        int totalCities = filteredCities.size();
        int start = Math.min(page * size, totalCities);
        int end = Math.min((page + 1) * size, totalCities);
        List<CityWithUnpublishedReviewsDTO> pageContent = filteredCities.subList(start, end);

        // Получаем статистику
        Map<String, Object> statistics = reviewCityService.getCitiesStatistics();

        // Вычисляем общее количество страниц
        int totalPages = (int) Math.ceil((double) totalCities / size);

        // Добавляем данные в модель
        model.addAttribute("cities", pageContent);
        model.addAttribute("totalCities", totalCities);
        model.addAttribute("totalUnpublished", statistics.get("totalUnpublished"));
        model.addAttribute("totalUnpublishedNotArchive", statistics.get("totalUnpublishedNotArchive"));
        model.addAttribute("totalActiveBots", statistics.get("totalActiveBots"));
        model.addAttribute("totalBotBalance", statistics.get("totalBotBalance"));
        model.addAttribute("averagePerCity", statistics.get("averagePerCity"));
        model.addAttribute("averageNotArchivePerCity", statistics.get("averageNotArchivePerCity"));
        model.addAttribute("averageBotsPerCity", statistics.get("averageBotsPerCity"));
        model.addAttribute("averageBalancePerCity", statistics.get("averageBalancePerCity"));
        model.addAttribute("statusStats", statistics.get("statusStats"));
        model.addAttribute("criticalCount", statistics.get("criticalCount"));
        model.addAttribute("excessCount", statistics.get("excessCount"));
        model.addAttribute("searchParam", search);
        model.addAttribute("sort", sort);
        model.addAttribute("direction", direction);
        model.addAttribute("actualDirection", model.containsAttribute("actualDirection") ?
                model.getAttribute("actualDirection") : direction);
        model.addAttribute("currentPage", page);
        model.addAttribute("pageSize", size);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("currentDate", java.time.LocalDateTime.now());

        // Логирование
        log.info("Найдено {} городов. Ботов: {}, Баланс: {}",
                totalCities, statistics.get("totalActiveBots"), statistics.get("totalBotBalance"));

        return "city/cities";
    }

    private Comparator<CityWithUnpublishedReviewsDTO> getComparator(String sort) {
        switch (sort) {
            case "name":
                return Comparator.comparing(CityWithUnpublishedReviewsDTO::getCityTitle);
            case "countAll":
                return Comparator.comparing(CityWithUnpublishedReviewsDTO::getUnpublishedCount);
            case "countArchive":
                return Comparator.comparing(CityWithUnpublishedReviewsDTO::getUnpublishedNotArchiveCount);
            case "bots":
                return Comparator.comparing(CityWithUnpublishedReviewsDTO::getActiveBotsCount);
            case "balance":
                return Comparator.comparing(CityWithUnpublishedReviewsDTO::getBotBalance);
            default:
                return Comparator.comparing(CityWithUnpublishedReviewsDTO::getUnpublishedCount);
        }
    }



    @GetMapping("/export-all")
    public void exportAllCitiesToExcel(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            HttpServletResponse response) throws IOException {

        log.info("Экспорт ВСЕХ городов в Excel: search={}, sort={}, direction={}",
                search, sort, direction);

        // Получаем ВСЕ данные (без пагинации)
        List<CityWithUnpublishedReviewsDTO> cities = reviewCityService
                .getAllCitiesWithUnpublishedReviewsNoPagination(search, sort, direction);

        if (cities.isEmpty()) {
            response.setContentType("text/html; charset=UTF-8");
            response.getWriter().write("<script>alert('Нет данных для экспорта'); history.back();</script>");
            return;
        }

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Статистика по городам");

            // Создаем стили
            Map<String, CellStyle> styles = createStyles(workbook);

            // Заголовки
            Row headerRow = sheet.createRow(0);
            String[] headers = {
                    "№", "Город", "ID",
                    "Неопубликованных (все)", "Неопубликованных (без архивных)",
                    "Архивные отзывы", "Доступные аккаунты", "Баланс (+/-)",
                    "Статус", "Процент (%)", "Дата выгрузки"
            };

            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(styles.get("header"));
            }

            // Заполняем данными
            int rowNum = 1;
            long totalUnpublished = 0;
            long totalNotArchive = 0;
            long totalBots = 0;
            long totalBalance = 0;

            for (int i = 0; i < cities.size(); i++) {
                CityWithUnpublishedReviewsDTO city = cities.get(i);
                Row row = sheet.createRow(rowNum++);

                // №
                row.createCell(0).setCellValue(i + 1);

                // Город
                row.createCell(1).setCellValue(city.getCityTitle());

                // ID
                row.createCell(2).setCellValue(city.getCityId());

                // Неопубликованных (все)
                Cell allCell = row.createCell(3);
                long unpublishedCount = city.getUnpublishedCount() != null ? city.getUnpublishedCount() : 0L;
                allCell.setCellValue(unpublishedCount);
                allCell.setCellStyle(getCountStyle((int) unpublishedCount,
                        styles.get("green"), styles.get("yellow"),
                        styles.get("red"), styles.get("data")));
                totalUnpublished += unpublishedCount;

                // Неопубликованных (без архивных)
                Cell notArchiveCell = row.createCell(4);
                long notArchiveCount = city.getUnpublishedNotArchiveCount() != null ? city.getUnpublishedNotArchiveCount() : 0L;
                notArchiveCell.setCellValue(notArchiveCount);
                notArchiveCell.setCellStyle(styles.get("blue"));
                totalNotArchive += notArchiveCount;

                // Архивные отзывы
                long archiveCount = unpublishedCount - notArchiveCount;
                row.createCell(5).setCellValue(archiveCount);

                // Доступные аккаунты
                long activeBotsCount = city.getActiveBotsCount() != null ? city.getActiveBotsCount() : 0L;
                row.createCell(6).setCellValue(activeBotsCount);
                totalBots += activeBotsCount;

                // Баланс (+/-)
                Cell balanceCell = row.createCell(7);
                long botBalance = city.getBotBalance() != null ? city.getBotBalance() : 0L;
                balanceCell.setCellValue(botBalance);
                if (botBalance > 0) {
                    balanceCell.setCellStyle(styles.get("positive"));
                } else if (botBalance < 0) {
                    balanceCell.setCellStyle(styles.get("negative"));
                } else {
                    balanceCell.setCellStyle(styles.get("data"));
                }
                totalBalance += botBalance;

                // Статус (текстовый)
                row.createCell(8).setCellValue(city.getBotStatus());

                // Процент (%)
                Double botPercentage = city.getBotPercentage() != null ? city.getBotPercentage() : 0.0;
                row.createCell(9).setCellValue(String.format("%.2f", botPercentage));

                // Дата выгрузки
                row.createCell(10).setCellValue(
                        new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date()));
            }

            // Итоговая строка
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

            totalRow.createCell(10).setCellValue(
                    new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date()));
            totalRow.getCell(10).setCellStyle(totalStyle);

            // Авторазмер колонок
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            // Фиксируем заголовок
            sheet.createFreezePane(0, 1);

            // Настройки ответа
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            String fileName = String.format("cities_full_export_%s.xlsx",
                    new SimpleDateFormat("yyyy-MM-dd_HH-mm").format(new Date()));

            // Кодировка для русского языка
            fileName = new String(fileName.getBytes("UTF-8"), "ISO-8859-1");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");

            workbook.write(response.getOutputStream());
        } catch (Exception e) {
            log.error("Ошибка при экспорте в Excel", e);
            response.setContentType("text/html; charset=UTF-8");
            response.getWriter().write("<script>alert('Ошибка при экспорте: " + e.getMessage() + "'); history.back();</script>");
        }
    }

    private Map<String, CellStyle> createStyles(Workbook workbook) {
        Map<String, CellStyle> styles = new HashMap<>();

        // Стиль заголовка
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

        // Базовый стиль данных
        CellStyle dataStyle = workbook.createCellStyle();
        dataStyle.setAlignment(HorizontalAlignment.CENTER);
        dataStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        dataStyle.setBorderBottom(BorderStyle.THIN);
        styles.put("data", dataStyle);

        // Стиль для зеленых ячеек (≤ 5)
        CellStyle greenStyle = workbook.createCellStyle();
        greenStyle.cloneStyleFrom(dataStyle);
        greenStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        greenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        styles.put("green", greenStyle);

        // Стиль для желтых ячеек (6-15)
        CellStyle yellowStyle = workbook.createCellStyle();
        yellowStyle.cloneStyleFrom(dataStyle);
        yellowStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        yellowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        styles.put("yellow", yellowStyle);

        // Стиль для красных ячеек (> 15)
        CellStyle redStyle = workbook.createCellStyle();
        redStyle.cloneStyleFrom(dataStyle);
        redStyle.setFillForegroundColor(IndexedColors.LIGHT_ORANGE.getIndex());
        redStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        styles.put("red", redStyle);

        // Стиль для неархивных (голубой)
        CellStyle blueStyle = workbook.createCellStyle();
        blueStyle.cloneStyleFrom(dataStyle);
        blueStyle.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        blueStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        styles.put("blue", blueStyle);

        // Стиль для положительных значений
        CellStyle positiveStyle = workbook.createCellStyle();
        positiveStyle.cloneStyleFrom(dataStyle);
        Font positiveFont = workbook.createFont();
        positiveFont.setColor(IndexedColors.GREEN.getIndex());
        positiveStyle.setFont(positiveFont);
        styles.put("positive", positiveStyle);

        // Стиль для отрицательных значений
        CellStyle negativeStyle = workbook.createCellStyle();
        negativeStyle.cloneStyleFrom(dataStyle);
        Font negativeFont = workbook.createFont();
        negativeFont.setColor(IndexedColors.RED.getIndex());
        negativeStyle.setFont(negativeFont);
        styles.put("negative", negativeStyle);

        return styles;
    }

    private CellStyle getCountStyle(int count, CellStyle green, CellStyle yellow,
                                    CellStyle red, CellStyle defaultStyle) {
        if (count <= 5) {
            return green;
        } else if (count <= 15) {
            return yellow;
        } else {
            return red;
        }
    }


}

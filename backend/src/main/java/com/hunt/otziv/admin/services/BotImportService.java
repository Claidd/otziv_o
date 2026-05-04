package com.hunt.otziv.admin.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.b_bots.repository.StatusBotRepository;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.repository.CityRepository;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.WorkerRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BotImportService {

    private static final String DEFAULT_FIO = "Впиши Имя Фамилию";
    private static final int DEFAULT_COUNTER = 0;
    private static final boolean DEFAULT_ACTIVE = true;
    private static final long DEFAULT_STATUS_ID = 1L;
    private static final long DEFAULT_WORKER_ID = 5L;
    private static final long DEFAULT_CITY_ID = 325L;
    private static final int MAX_ERROR_SAMPLES = 8;
    private static final int LOGIN_CHUNK_SIZE = 900;

    private static final Map<String, String> HEADER_ALIASES = Map.ofEntries(
            Map.entry("bot_login", "bot_login"),
            Map.entry("login", "bot_login"),
            Map.entry("bot_password", "bot_password"),
            Map.entry("password", "bot_password"),
            Map.entry("bot_fio", "bot_fio"),
            Map.entry("fio", "bot_fio"),
            Map.entry("bot_counter", "bot_counter"),
            Map.entry("counter", "bot_counter"),
            Map.entry("bot_active", "bot_active"),
            Map.entry("active", "bot_active"),
            Map.entry("bot_status", "bot_status"),
            Map.entry("status", "bot_status"),
            Map.entry("bot_worker", "bot_worker"),
            Map.entry("worker", "bot_worker"),
            Map.entry("bot_city_id", "bot_city_id"),
            Map.entry("city_id", "bot_city_id")
    );

    private final BotsRepository botsRepository;
    private final StatusBotRepository statusBotRepository;
    private final WorkerRepository workerRepository;
    private final CityRepository cityRepository;

    @Transactional
    public BotImportResult importBots(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw badRequest("Файл не выбран");
        }

        List<List<String>> rows = readRows(file);
        if (rows.isEmpty()) {
            throw badRequest("Файл не содержит аккаунтов");
        }

        boolean hasHeader = hasHeader(rows.get(0));
        Map<String, Integer> headers = hasHeader ? headerIndexes(rows.get(0)) : Map.of();
        int firstDataRow = hasHeader ? 1 : 0;

        int totalRows = 0;
        int skippedDuplicates = 0;
        int skippedInvalid = 0;
        List<String> errors = new ArrayList<>();
        List<ImportedBotRow> importedRows = new ArrayList<>();
        Set<String> seenLogins = new HashSet<>();

        for (int index = firstDataRow; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (!hasContent(row)) {
                continue;
            }

            totalRows++;
            try {
                ImportedBotRow importedRow = toImportedRow(row, hasHeader, headers, index + 1);
                if (!seenLogins.add(importedRow.login())) {
                    skippedDuplicates++;
                    continue;
                }
                importedRows.add(importedRow);
            } catch (RowImportException exception) {
                skippedInvalid++;
                addError(errors, exception.getMessage());
            }
        }

        if (importedRows.isEmpty()) {
            return new BotImportResult(totalRows, 0, skippedDuplicates, skippedInvalid, errors);
        }

        Set<String> existingLogins = findExistingLogins(importedRows);
        Map<Long, StatusBot> statuses = new HashMap<>();
        Map<Long, Worker> workers = new HashMap<>();
        Map<Long, City> cities = new HashMap<>();
        List<Bot> botsToSave = new ArrayList<>();

        for (ImportedBotRow importedRow : importedRows) {
            if (existingLogins.contains(importedRow.login())) {
                skippedDuplicates++;
                continue;
            }

            try {
                StatusBot status = requiredStatus(statuses, importedRow.statusId(), importedRow.rowNumber());
                Worker worker = requiredWorker(workers, importedRow.workerId(), importedRow.rowNumber());
                City city = requiredCity(cities, importedRow.cityId(), importedRow.rowNumber());

                botsToSave.add(Bot.builder()
                        .login(importedRow.login())
                        .password(importedRow.password())
                        .fio(importedRow.fio())
                        .counter(importedRow.counter())
                        .active(importedRow.active())
                        .status(status)
                        .worker(worker)
                        .botCity(city)
                        .build());
            } catch (RowImportException exception) {
                skippedInvalid++;
                addError(errors, exception.getMessage());
            }
        }

        int added = 0;
        if (!botsToSave.isEmpty()) {
            for (Bot ignored : botsRepository.saveAll(botsToSave)) {
                added++;
            }
        }

        return new BotImportResult(totalRows, added, skippedDuplicates, skippedInvalid, errors);
    }

    private List<List<String>> readRows(MultipartFile file) {
        String extension = extension(file.getOriginalFilename());
        try {
            if ("xlsx".equals(extension) || "xls".equals(extension)) {
                return readWorkbookRows(file);
            }

            if ("csv".equals(extension) || "tsv".equals(extension) || extension.isBlank()) {
                return readDelimitedRows(file);
            }
        } catch (IOException exception) {
            throw badRequest("Файл не удалось прочитать");
        }

        throw badRequest("Поддерживаются только CSV, TSV, XLS и XLSX");
    }

    private List<List<String>> readWorkbookRows(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            if (workbook.getNumberOfSheets() == 0) {
                return List.of();
            }

            Sheet sheet = workbook.getSheetAt(0);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<List<String>> rows = new ArrayList<>();

            for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || row.getLastCellNum() < 0) {
                    continue;
                }

                List<String> values = new ArrayList<>();
                for (int cellIndex = 0; cellIndex < row.getLastCellNum(); cellIndex++) {
                    values.add(cleanCell(formatter.formatCellValue(row.getCell(cellIndex), evaluator)));
                }

                if (hasContent(values)) {
                    rows.add(values);
                }
            }

            return rows;
        } catch (Exception exception) {
            throw badRequest("Excel-файл не удалось прочитать");
        }
    }

    private List<List<String>> readDelimitedRows(MultipartFile file) throws IOException {
        String text = decode(file.getBytes());
        char delimiter = detectDelimiter(text);
        return parseDelimitedRows(text, delimiter);
    }

    private List<List<String>> parseDelimitedRows(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;

        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);

            if (quoted) {
                if (current == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        cell.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(current);
                }
                continue;
            }

            if (current == '"') {
                quoted = true;
            } else if (current == delimiter) {
                row.add(cleanCell(cell.toString()));
                cell.setLength(0);
            } else if (current == '\n' || current == '\r') {
                row.add(cleanCell(cell.toString()));
                cell.setLength(0);
                if (hasContent(row)) {
                    rows.add(row);
                }
                row = new ArrayList<>();
                if (current == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
            } else {
                cell.append(current);
            }
        }

        row.add(cleanCell(cell.toString()));
        if (hasContent(row)) {
            rows.add(row);
        }

        return rows;
    }

    private ImportedBotRow toImportedRow(
            List<String> row,
            boolean hasHeader,
            Map<String, Integer> headers,
            int rowNumber
    ) {
        String login = requiredValue(cell(row, hasHeader, headers, "bot_login", 0), "bot_login", rowNumber);
        String password = requiredValue(cell(row, hasHeader, headers, "bot_password", 1), "bot_password", rowNumber);
        String fio = valueOrDefault(cell(row, hasHeader, headers, "bot_fio", 2), DEFAULT_FIO);
        int counter = parseIntOrDefault(cell(row, hasHeader, headers, "bot_counter", 3), DEFAULT_COUNTER, "bot_counter", rowNumber);
        boolean active = parseBooleanOrDefault(cell(row, hasHeader, headers, "bot_active", 4), DEFAULT_ACTIVE, "bot_active", rowNumber);
        long statusId = parseLongOrDefault(cell(row, hasHeader, headers, "bot_status", 5), DEFAULT_STATUS_ID, "bot_status", rowNumber);
        long workerId = parseLongOrDefault(cell(row, hasHeader, headers, "bot_worker", 6), DEFAULT_WORKER_ID, "bot_worker", rowNumber);
        long cityId = parseLongOrDefault(cell(row, hasHeader, headers, "bot_city_id", 7), DEFAULT_CITY_ID, "bot_city_id", rowNumber);

        if (counter < 0) {
            throw rowError(rowNumber, "bot_counter не может быть меньше нуля");
        }

        return new ImportedBotRow(rowNumber, login, password, fio, counter, active, statusId, workerId, cityId);
    }

    private Set<String> findExistingLogins(List<ImportedBotRow> rows) {
        List<String> logins = rows.stream()
                .map(ImportedBotRow::login)
                .distinct()
                .toList();
        Set<String> existingLogins = new LinkedHashSet<>();

        for (int start = 0; start < logins.size(); start += LOGIN_CHUNK_SIZE) {
            int end = Math.min(start + LOGIN_CHUNK_SIZE, logins.size());
            existingLogins.addAll(botsRepository.findExistingLogins(logins.subList(start, end)));
        }

        return existingLogins;
    }

    private StatusBot requiredStatus(Map<Long, StatusBot> cache, long id, int rowNumber) {
        if (!cache.containsKey(id)) {
            cache.put(id, statusBotRepository.findById(id).orElse(null));
        }

        StatusBot status = cache.get(id);
        if (status == null) {
            throw rowError(rowNumber, "статус аккаунта не найден: " + id);
        }
        return status;
    }

    private Worker requiredWorker(Map<Long, Worker> cache, long id, int rowNumber) {
        if (!cache.containsKey(id)) {
            cache.put(id, workerRepository.findById(id).orElse(null));
        }

        Worker worker = cache.get(id);
        if (worker == null) {
            throw rowError(rowNumber, "владелец аккаунта не найден: " + id);
        }
        return worker;
    }

    private City requiredCity(Map<Long, City> cache, long id, int rowNumber) {
        if (!cache.containsKey(id)) {
            cache.put(id, cityRepository.findById(id));
        }

        City city = cache.get(id);
        if (city == null) {
            throw rowError(rowNumber, "город аккаунта не найден: " + id);
        }
        return city;
    }

    private boolean hasHeader(List<String> row) {
        Set<String> normalizedHeaders = new HashSet<>();
        for (String value : row) {
            String canonical = HEADER_ALIASES.get(normalizeHeader(value));
            if (canonical != null) {
                normalizedHeaders.add(canonical);
            }
        }
        return normalizedHeaders.contains("bot_login") && normalizedHeaders.contains("bot_password");
    }

    private Map<String, Integer> headerIndexes(List<String> row) {
        Map<String, Integer> headers = new LinkedHashMap<>();
        for (int index = 0; index < row.size(); index++) {
            String canonical = HEADER_ALIASES.get(normalizeHeader(row.get(index)));
            if (canonical != null) {
                headers.putIfAbsent(canonical, index);
            }
        }
        return headers;
    }

    private String cell(List<String> row, boolean hasHeader, Map<String, Integer> headers, String header, int fallbackIndex) {
        int index = fallbackIndex;
        if (hasHeader) {
            Integer headerIndex = headers.get(header);
            if (headerIndex == null) {
                return "";
            }
            index = headerIndex;
        }

        if (index < 0 || index >= row.size()) {
            return "";
        }
        return cleanCell(row.get(index));
    }

    private String requiredValue(String value, String field, int rowNumber) {
        String result = cleanCell(value);
        if (result.isBlank()) {
            throw rowError(rowNumber, field + " обязателен");
        }
        return result;
    }

    private String valueOrDefault(String value, String defaultValue) {
        String result = cleanCell(value);
        return result.isBlank() ? defaultValue : result;
    }

    private int parseIntOrDefault(String value, int defaultValue, String field, int rowNumber) {
        String result = cleanCell(value);
        if (result.isBlank()) {
            return defaultValue;
        }

        Long longValue = parseWholeNumber(result);
        if (longValue == null || longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
            throw rowError(rowNumber, field + " должен быть целым числом");
        }
        return longValue.intValue();
    }

    private long parseLongOrDefault(String value, long defaultValue, String field, int rowNumber) {
        String result = cleanCell(value);
        if (result.isBlank()) {
            return defaultValue;
        }

        Long longValue = parseWholeNumber(result);
        if (longValue == null) {
            throw rowError(rowNumber, field + " должен быть целым числом");
        }
        return longValue;
    }

    private boolean parseBooleanOrDefault(String value, boolean defaultValue, String field, int rowNumber) {
        String result = cleanCell(value);
        if (result.isBlank()) {
            return defaultValue;
        }

        String normalized = result.toLowerCase(Locale.ROOT);
        if (Set.of("1", "true", "yes", "y", "on", "да", "активен").contains(normalized)) {
            return true;
        }
        if (Set.of("0", "false", "no", "n", "off", "нет", "выключен").contains(normalized)) {
            return false;
        }

        Long numeric = parseWholeNumber(result);
        if (numeric != null) {
            return numeric != 0;
        }

        throw rowError(rowNumber, field + " должен быть 1/0 или true/false");
    }

    private Long parseWholeNumber(String value) {
        String normalized = value.trim()
                .replace("\u00A0", "")
                .replace(" ", "")
                .replace(',', '.');

        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            try {
                BigDecimal decimal = new BigDecimal(normalized);
                BigDecimal rounded = decimal.setScale(0, RoundingMode.UNNECESSARY);
                return rounded.longValueExact();
            } catch (ArithmeticException | NumberFormatException exception) {
                return null;
            }
        }
    }

    private boolean hasContent(List<String> row) {
        return row.stream().anyMatch(value -> !cleanCell(value).isBlank());
    }

    private String cleanCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\uFEFF", "").trim();
    }

    private String normalizeHeader(String value) {
        return cleanCell(value)
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }

    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                    .replace("\uFEFF", "");
        } catch (CharacterCodingException exception) {
            return Charset.forName("windows-1251").decode(ByteBuffer.wrap(bytes)).toString().replace("\uFEFF", "");
        }
    }

    private char detectDelimiter(String text) {
        int semicolons = 0;
        int commas = 0;
        int tabs = 0;
        boolean quoted = false;
        int lines = 0;

        for (int index = 0; index < text.length() && lines < 6; index++) {
            char current = text.charAt(index);
            if (current == '"') {
                quoted = !quoted;
            } else if (!quoted) {
                if (current == ';') {
                    semicolons++;
                } else if (current == ',') {
                    commas++;
                } else if (current == '\t') {
                    tabs++;
                } else if (current == '\n') {
                    lines++;
                }
            }
        }

        if (tabs > semicolons && tabs > commas) {
            return '\t';
        }
        return semicolons >= commas ? ';' : ',';
    }

    private String extension(String fileName) {
        String safeFileName = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        int dotIndex = safeFileName.lastIndexOf('.');
        return dotIndex < 0 ? "" : safeFileName.substring(dotIndex + 1);
    }

    private void addError(List<String> errors, String message) {
        if (errors.size() < MAX_ERROR_SAMPLES) {
            errors.add(message);
        }
    }

    private RowImportException rowError(int rowNumber, String message) {
        return new RowImportException("Строка " + rowNumber + ": " + message);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record BotImportResult(
            int totalRows,
            int added,
            int skippedDuplicates,
            int skippedInvalid,
            List<String> errors
    ) {
    }

    private record ImportedBotRow(
            int rowNumber,
            String login,
            String password,
            String fio,
            int counter,
            boolean active,
            long statusId,
            long workerId,
            long cityId
    ) {
    }

    private static class RowImportException extends RuntimeException {
        private RowImportException(String message) {
            super(message);
        }
    }
}

package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.model.LeadStatus;
import com.hunt.otziv.l_lead.model.Telephone;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.l_lead.utils.LeadPhoneNormalizer;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.MarketologRepository;
import com.hunt.otziv.u_users.repository.OperatorRepository;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
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
public class LeadImportService {

    private static final String DEFAULT_CITY = "Нет";
    private static final String DEFAULT_STATUS = LeadStatus.NEW.title;
    private static final int MAX_ERROR_SAMPLES = 8;
    private static final int PHONE_CHUNK_SIZE = 900;

    private static final Map<String, String> HEADER_ALIASES = Map.ofEntries(
            Map.entry("telephone_lead", "telephone_lead"),
            Map.entry("phone", "telephone_lead"),
            Map.entry("phone_lead", "telephone_lead"),
            Map.entry("telephone", "telephone_lead"),
            Map.entry("city_lead", "city_lead"),
            Map.entry("city", "city_lead"),
            Map.entry("comments_lead", "comments_lead"),
            Map.entry("comments_lea", "comments_lead"),
            Map.entry("comment", "comments_lead"),
            Map.entry("comments", "comments_lead"),
            Map.entry("lid_status", "lid_status"),
            Map.entry("lead_status", "lid_status"),
            Map.entry("status", "lid_status"),
            Map.entry("create_date", "create_date"),
            Map.entry("created_at", "create_date"),
            Map.entry("update_status", "update_status"),
            Map.entry("updated_at", "update_status"),
            Map.entry("date_new_try", "date_new_try"),
            Map.entry("operator_id", "operator_id"),
            Map.entry("manager_id", "manager_id"),
            Map.entry("marketolog_id", "marketolog_id"),
            Map.entry("telephone_id", "telephone_id")
    );

    private final LeadsRepository leadsRepository;
    private final OperatorRepository operatorRepository;
    private final ManagerRepository managerRepository;
    private final MarketologRepository marketologRepository;
    private final TelephoneRepository telephoneRepository;

    @Transactional
    public LeadImportResult importLeads(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw badRequest("Файл не выбран");
        }

        List<List<String>> rows = readRows(file);
        if (rows.isEmpty()) {
            throw badRequest("Файл не содержит лидов");
        }

        boolean hasHeader = hasHeader(rows.get(0));
        Map<String, Integer> headers = hasHeader ? headerIndexes(rows.get(0)) : Map.of();
        int firstDataRow = hasHeader ? 1 : 0;

        int totalRows = 0;
        int skippedDuplicates = 0;
        int skippedInvalid = 0;
        List<String> errors = new ArrayList<>();
        List<ImportedLeadRow> importedRows = new ArrayList<>();
        Set<String> seenPhones = new HashSet<>();

        for (int index = firstDataRow; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (!hasContent(row)) {
                continue;
            }

            totalRows++;
            try {
                ImportedLeadRow importedRow = toImportedRow(row, hasHeader, headers, index + 1);
                if (!seenPhones.add(importedRow.telephoneLead())) {
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
            return new LeadImportResult(totalRows, 0, skippedDuplicates, skippedInvalid, errors);
        }

        Set<String> existingTelephoneLeads = findExistingTelephoneLeads(importedRows);
        Map<Long, Operator> operators = new HashMap<>();
        Map<Long, Manager> managers = new HashMap<>();
        Map<Long, Marketolog> marketologs = new HashMap<>();
        Map<Long, Telephone> telephones = new HashMap<>();
        List<Lead> leadsToSave = new ArrayList<>();

        for (ImportedLeadRow importedRow : importedRows) {
            if (hasExistingPhone(existingTelephoneLeads, importedRow.telephoneLead())) {
                skippedDuplicates++;
                continue;
            }

            try {
                leadsToSave.add(Lead.builder()
                        .telephoneLead(importedRow.telephoneLead())
                        .cityLead(importedRow.cityLead())
                        .commentsLead(importedRow.commentsLead())
                        .lidStatus(importedRow.lidStatus())
                        .createDate(importedRow.createDate())
                        .updateStatus(importedRow.updateStatus())
                        .dateNewTry(importedRow.dateNewTry())
                        .operator(optionalOperator(operators, importedRow.operatorId(), importedRow.rowNumber()))
                        .manager(optionalManager(managers, importedRow.managerId(), importedRow.rowNumber()))
                        .marketolog(optionalMarketolog(marketologs, importedRow.marketologId(), importedRow.rowNumber()))
                        .telephone(optionalTelephone(telephones, importedRow.telephoneId(), importedRow.rowNumber()))
                        .build());
            } catch (RowImportException exception) {
                skippedInvalid++;
                addError(errors, exception.getMessage());
            }
        }

        int added = 0;
        if (!leadsToSave.isEmpty()) {
            for (Lead ignored : leadsRepository.saveAll(leadsToSave)) {
                added++;
            }
        }

        return new LeadImportResult(totalRows, added, skippedDuplicates, skippedInvalid, errors);
    }

    private ImportedLeadRow toImportedRow(
            List<String> row,
            boolean hasHeader,
            Map<String, Integer> headers,
            int rowNumber
    ) {
        String telephoneLead = LeadPhoneNormalizer.normalize(requiredValue(
                cell(row, hasHeader, headers, "telephone_lead", 0),
                "telephone_lead",
                rowNumber
        ));
        if (telephoneLead.isBlank()) {
            throw rowError(rowNumber, "telephone_lead должен содержать номер телефона");
        }

        String cityLead = valueOrDefault(cell(row, hasHeader, headers, "city_lead", 1), DEFAULT_CITY);
        String commentsLead = cleanCell(cell(row, hasHeader, headers, "comments_lead", 2));
        String lidStatus = parseStatusOrDefault(cell(row, hasHeader, headers, "lid_status", 3), DEFAULT_STATUS, rowNumber);
        LocalDate createDate = null;
        LocalDateTime updateStatus = null;
        LocalDate dateNewTry = null;
        Long operatorId = parseLongOrNull(cell(row, hasHeader, headers, "operator_id", 7), "operator_id", rowNumber);
        Long managerId = parseLongOrNull(cell(row, hasHeader, headers, "manager_id", 8), "manager_id", rowNumber);
        Long marketologId = parseLongOrNull(cell(row, hasHeader, headers, "marketolog_id", 9), "marketolog_id", rowNumber);
        Long telephoneId = parseLongOrNull(cell(row, hasHeader, headers, "telephone_id", 10), "telephone_id", rowNumber);

        return new ImportedLeadRow(
                rowNumber,
                telephoneLead,
                cityLead,
                commentsLead,
                lidStatus,
                createDate,
                updateStatus,
                dateNewTry,
                operatorId,
                managerId,
                marketologId,
                telephoneId
        );
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

    private Set<String> findExistingTelephoneLeads(List<ImportedLeadRow> rows) {
        List<String> phoneVariants = rows.stream()
                .flatMap(row -> LeadPhoneNormalizer.variants(row.telephoneLead()).stream())
                .distinct()
                .toList();
        Set<String> existingPhones = new LinkedHashSet<>();

        for (int start = 0; start < phoneVariants.size(); start += PHONE_CHUNK_SIZE) {
            int end = Math.min(start + PHONE_CHUNK_SIZE, phoneVariants.size());
            existingPhones.addAll(leadsRepository.findExistingTelephoneLeads(phoneVariants.subList(start, end)));
        }

        return existingPhones;
    }

    private boolean hasExistingPhone(Set<String> existingTelephoneLeads, String telephoneLead) {
        return LeadPhoneNormalizer.variants(telephoneLead).stream().anyMatch(existingTelephoneLeads::contains);
    }

    private Operator optionalOperator(Map<Long, Operator> cache, Long id, int rowNumber) {
        if (id == null) {
            return null;
        }

        if (!cache.containsKey(id)) {
            cache.put(id, operatorRepository.findById(id).orElse(null));
        }

        Operator operator = cache.get(id);
        if (operator == null) {
            throw rowError(rowNumber, "оператор не найден: " + id);
        }
        return operator;
    }

    private Manager optionalManager(Map<Long, Manager> cache, Long id, int rowNumber) {
        if (id == null) {
            return null;
        }

        if (!cache.containsKey(id)) {
            cache.put(id, managerRepository.findById(id).orElse(null));
        }

        Manager manager = cache.get(id);
        if (manager == null) {
            throw rowError(rowNumber, "менеджер не найден: " + id);
        }
        return manager;
    }

    private Marketolog optionalMarketolog(Map<Long, Marketolog> cache, Long id, int rowNumber) {
        if (id == null) {
            return null;
        }

        if (!cache.containsKey(id)) {
            cache.put(id, marketologRepository.findById(id).orElse(null));
        }

        Marketolog marketolog = cache.get(id);
        if (marketolog == null) {
            throw rowError(rowNumber, "маркетолог не найден: " + id);
        }
        return marketolog;
    }

    private Telephone optionalTelephone(Map<Long, Telephone> cache, Long id, int rowNumber) {
        if (id == null) {
            return null;
        }

        if (!cache.containsKey(id)) {
            cache.put(id, telephoneRepository.findById(id).orElse(null));
        }

        Telephone telephone = cache.get(id);
        if (telephone == null) {
            throw rowError(rowNumber, "телефон рассылки не найден: " + id);
        }
        return telephone;
    }

    private boolean hasHeader(List<String> row) {
        Set<String> normalizedHeaders = new HashSet<>();
        for (String value : row) {
            String canonical = HEADER_ALIASES.get(normalizeHeader(value));
            if (canonical != null) {
                normalizedHeaders.add(canonical);
            }
        }
        return normalizedHeaders.contains("telephone_lead");
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

    private String parseStatusOrDefault(String value, String defaultValue, int rowNumber) {
        String result = cleanCell(value);
        if (result.isBlank()) {
            return defaultValue;
        }

        for (LeadStatus status : LeadStatus.values()) {
            if (status.title.equalsIgnoreCase(result) || status.name().equalsIgnoreCase(result)) {
                return status.title;
            }
        }

        throw rowError(rowNumber, "неизвестный статус лида: " + result);
    }

    private Long parseLongOrNull(String value, String field, int rowNumber) {
        String result = cleanCell(value);
        if (result.isBlank()) {
            return null;
        }

        Long longValue = parseWholeNumber(result);
        if (longValue == null) {
            throw rowError(rowNumber, field + " должен быть целым числом");
        }
        return longValue;
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

    public record LeadImportResult(
            int totalRows,
            int added,
            int skippedDuplicates,
            int skippedInvalid,
            List<String> errors
    ) {
    }

    private record ImportedLeadRow(
            int rowNumber,
            String telephoneLead,
            String cityLead,
            String commentsLead,
            String lidStatus,
            LocalDate createDate,
            LocalDateTime updateStatus,
            LocalDate dateNewTry,
            Long operatorId,
            Long managerId,
            Long marketologId,
            Long telephoneId
    ) {
    }

    private static class RowImportException extends RuntimeException {
        private RowImportException(String message) {
            super(message);
        }
    }
}
